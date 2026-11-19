package com.openminis.app.ui.chat

import com.openminis.app.agent.ContextSaverRuntime
import com.openminis.app.agent.ToolResultProjection
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.ContextSaverPrefs
import com.openminis.app.data.ContextTokenEstimate
import com.openminis.app.data.ContextTokenMeter
import com.openminis.app.data.calibratedContextTokens
import com.openminis.app.data.contextUsageIdentity
import com.openminis.app.data.estimateProjectedContext
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.ToolIdNormalization
import com.openminis.app.data.repository.hasUncoveredCompactPrefix
import com.openminis.app.data.repository.toView
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.api.ChatRequest
import com.openminis.app.tools.ToolOutputSpill

internal data class ProjectedChatRequest(
    val request: ChatRequest,
    val prompt: MemoryDeliveryPrompt?,
    val recall: RecallReminder?,
    val model: ModelAttributionSnapshot,
    val markerId: String?,
    val window: Int,
    val policy: ContextPolicy,
    val rawTokens: Int,
    val estimate: ContextTokenEstimate,
    val hasCoverageGap: Boolean,
    val agingStats: ContextSaverRuntime.Stats,
    val imageElision: ImageBudget.RequestBudgetPlan?,
    /**
     * [T-anchor-persisted-fingerprint] Fingerprint over the request WITHOUT its
     * request-local appended text (recall reminder, receipt notice, stall
     * nudge). Those bytes ride one request and never persist, so hashing them
     * made the next request's prefix fingerprint永远不匹配 and silently killed
     * the measured-prefix anchor on the default (memory-on) configuration.
     */
    val anchorFingerprint: String? = null,
    /** Metered tokens the request-local appends added on top of the persisted form. */
    val requestLocalTokens: Int = 0,
)

internal fun ChatViewModel.requestContextWindow(provider: LLMProvider, model: ModelAttributionSnapshot): Int {
    val config = providerRepository.config.value
    val live = config.modelEntries.firstOrNull {
        it.providerInstanceId == model.providerInstanceId && it.model.id == model.modelId
    }?.model
    val providerWindow = (live ?: provider.model).contextWindowTokens
    val groupLimit = config.modelGroups.firstOrNull { it.id == _selectedGroupId.value }
        ?.contextLimitTokens?.takeIf { it > 0 }
    return if (groupLimit == null) providerWindow else minOf(providerWindow, groupLimit)
}

internal fun ChatViewModel.requestEntryId(model: ModelAttributionSnapshot): String? {
    val entries = providerRepository.config.value.modelEntries
    val matches = entries.filter { it.providerInstanceId == model.providerInstanceId && it.model.id == model.modelId }
    return matches.firstOrNull { it.id == _activeEntryId.value }?.id ?: matches.firstOrNull()?.id
}

internal fun ChatViewModel.requestThinkingLevel(provider: LLMProvider): ThinkingLevel {
    val model = ProviderFactory.requestAttribution(provider)
    val forced = model.providerInstanceId?.let { providerRepository.instance(it)?.forceThinkingEnabled } == true
    return if (forced || provider.model.supportsReasoning != false) _thinkingLevel.value else ThinkingLevel.OFF
}

internal fun ChatViewModel.publishRequestProjection(projection: ProjectedChatRequest) {
    ContextSaverRuntime.recordProjection(projection.agingStats)
    projection.imageElision?.let { _requestBudgetEvent.tryEmit(it) }
    refreshContextUsage(projection.estimate, projection.window)
}

/** A coverage check is separate from the token estimate; a smaller request does not prove coverage. */
internal suspend fun ChatViewModel.hasRequestHistoryGap(history: List<LLMMessage>): Boolean {
    if (llmHistoryStartOffset <= 0) return false
    return chatRepository.hasUncoveredCompactPrefix(activeSessionId, history.firstNotNullOfOrNull { it.dbMessageId })
}

/**
 * Shared by the read-only composer estimate and each real attempt. Input is a stable transcript
 * snapshot, never the live mutable history. The only optional write is to recover elided images
 * on the dispatch path; keeping a preview must not change the transcript or publish UI events.
 */
internal fun ChatViewModel.projectChatRequest(
    provider: LLMProvider,
    basePrompt: MemoryDeliveryPrompt?,
    history: List<LLMMessage>,
    tools: List<AgentToolDefinition>,
    thinking: ThinkingLevel,
    stallNote: String? = null,
    receiptNotice: String? = null,
    uncoveredBeforeResident: Boolean = false,
    persistImageSpillover: Boolean = false,
): ProjectedChatRequest {
    val attribution = ProviderFactory.requestAttribution(provider)
    val window = requestContextWindow(provider, attribution)
    val policy = ContextPolicy.forContextWindow(window, effectiveCompactPercent())
    val marker = _cachedLatestMarker?.toView()
    val memoryQuery = memoryRecallQueryForHistory(history)
    val prompt = basePrompt?.let {
        projectMemoryDeliveryPrompt(wrapMemoryDeliveryPromptForOAuth(provider, it, publish = false), query = memoryQuery)
    }
    val recall = newRecallReminder(prompt?.daily, query = memoryQuery, turnId = memoryRecallTurnIdForHistory(history))
    val untrustedCleaned = neutralizeRemoteToolResults(history.withoutStoredRecallReminders())
    // The marker does not cover the active tail. Preserve it through the local reducers;
    // the dispatch gate checks the resulting payload and any actual gap in resident history.
    val assembled = effectiveAgentHistoryUncounted(
        history = untrustedCleaned,
        windowTokens = window,
        marker = marker,
        applyTailBudget = false,
    )
    val repaired = dropOrphanedToolParts(assembled)
    val scrubbed = TextifiedImageGuard.scrub(repaired).history
    val cleaned = HistoryDigest.inject(scrubbed, HistoryDigest.render(llmDigestLines, llmDigestOmitted))
        .map(::projectAssistantText)
        .withMemoryRecallReminder(recall)
        .withRequestReminder(receiptNotice)
        .let { applyStallNudge(it, stallNote) }
    val normalized = ToolIdNormalization.normalizeMessages(cleaned)
    val mode = ContextSaverPrefs.mode()
    val keepRecent = if (mode == ContextSaverPrefs.Mode.OFF) ToolResultProjection.DEFAULT_KEEP_RECENT else mode.keepRecent
    val pressure = calibratedContextTokens(attribution, ContextTokenMeter.rawRequestTokens(normalized, prompt?.prompt, tools))
    // Path verification does disk I/O (workspace resolution, canRead): run it
    // only when a tier will actually project something, and only for results
    // OUTSIDE the protected recent suffix — a candidate check, never a
    // full-history scan. The workspace resolver is built once per request.
    val microTriggerTokens = (policy.compactThreshold * MICRO_TRIGGER_FRACTION_OF_COMPACT).toInt()
    val pruningLikely = pressure >= microTriggerTokens || ContextSaverRuntime.willAge(normalized, window, pressure)
    val verifiedPaths = if (pruningLikely) {
        verifiedRequestSpillPaths(normalized, ToolResultProjection.protectedSuffixStart(normalized, keepRecent))
    } else {
        emptySet()
    }
    val aged = ContextSaverRuntime.planAge(normalized, window, pressure, verifiedPaths, keepRecent)
    val pruned = microPruneOldToolResults(
        history = aged.messages,
        usedTokens = pressure.toLong(),
        windowTokens = window.toLong(),
        keepRecent = keepRecent,
        triggerTokens = microTriggerTokens.toLong(),
        verifiedSpillPaths = verifiedPaths,
    )
    var imageElision: ImageBudget.RequestBudgetPlan? = null
    val imagesBudgeted = applyRequestImageBudget(pruned, persistImageSpillover) { imageElision = it }
    val request = ChatRequest(imagesBudgeted, prompt?.prompt, maxTokens = 0, tools = tools, thinkingLevel = thinking)
    val raw = ContextTokenMeter.rawRequestTokens(request)
    // [T-anchor-persisted-fingerprint] The anchor must describe the PERSISTED
    // prefix: recall reminders, the receipt notice and the stall nudge ride one
    // request and never persist, so they are stripped before fingerprinting and
    // their metered size is settled out of the measured input.
    val persistedForm = request.messages.withoutRequestLocalAppends(recall, receiptNotice, stallNote)
    val anchorEligible = attribution.contextUsageIdentity() != null && marker?.id?.isBlank() != true
    val anchorFingerprint = if (anchorEligible) {
        ContextTokenMeter.requestFingerprint(persistedForm, request.systemPrompt, request.tools, request.imageParts)
    } else null
    val requestLocalTokens = if (anchorFingerprint != null) {
        (raw - ContextTokenMeter.rawRequestTokens(persistedForm, request.systemPrompt, request.tools)).coerceAtLeast(0)
    } else 0
    return ProjectedChatRequest(
        request = request,
        prompt = prompt,
        recall = recall,
        model = attribution,
        markerId = marker?.id,
        window = window,
        policy = policy,
        rawTokens = raw,
        estimate = estimateProjectedContext(request, attribution, marker?.id, raw),
        hasCoverageGap = uncoveredBeforeResident,
        agingStats = aged.stats,
        imageElision = imageElision,
        anchorFingerprint = anchorFingerprint,
        requestLocalTokens = requestLocalTokens,
    )
}

/**
 * Exact inverse of the request-local appends the projector made: the stall
 * nudge, receipt notice and recall reminder all end up appended to the last
 * user row's content and parts tail (the stall row merges into it during
 * normalization). Removing exactly those texts reconstructs the persisted form
 * the next request's prefix will project.
 */
private fun List<LLMMessage>.withoutRequestLocalAppends(
    recall: RecallReminder?,
    receiptNotice: String?,
    stallNote: String?,
): List<LLMMessage> {
    // ASSEMBLY order: recall rides in first, then the receipt notice, and the
    // stall nudge merges last — the strip below walks them LIFO from the tail.
    val appended = buildList {
        if (recall != null) add(recall.framed)
        if (receiptNotice != null) add("<system-reminder>\n$receiptNotice\n</system-reminder>")
        if (stallNote != null) add(stallNote)
    }
    if (appended.isEmpty()) return this
    val index = indexOfLast { it.role == LLMMessage.Role.USER }
    if (index < 0) return this
    val row = this[index]
    var parts = row.contentParts
    var content = row.content
    for (text in appended.asReversed()) {
        val last = parts.lastOrNull()
        if (last is AgentContentPart.Text && last.text == text) parts = parts.dropLast(1)
        val suffix = "\n\n" + text
        if (content.endsWith(suffix)) content = content.removeSuffix(suffix)
    }
    val rebuilt = row.copy(content = content, contentParts = parts)
    if (rebuilt == row) return this
    return toMutableList().also { it[index] = rebuilt }
}

private fun projectAssistantText(message: LLMMessage): LLMMessage {
    if (message.role != LLMMessage.Role.ASSISTANT) return message
    val text = com.openminis.app.agent.MessageTransformerChain.apply(message.content)
    val parts = message.contentParts.map { part ->
        if (part is AgentContentPart.Text) AgentContentPart.Text(com.openminis.app.agent.MessageTransformerChain.apply(part.text))
        else part
    }
    return if (text == message.content && parts == message.contentParts) message else message.copy(content = text, contentParts = parts)
}

private fun List<LLMMessage>.withRequestReminder(notice: String?): List<LLMMessage> {
    if (notice.isNullOrBlank()) return this
    val index = indexOfLast { it.role == LLMMessage.Role.USER }
    if (index < 0) return this
    return toMutableList().apply { this[index] = appendUserText(this[index], "<system-reminder>\n$notice\n</system-reminder>") }
}

/**
 * Verify only the declared spill references the two tiers could actually
 * project: everything from [candidatesStart] to the end. The chat read gate
 * and one workspace resolver per request replace the per-path rebuilds.
 */
private fun ChatViewModel.verifiedRequestSpillPaths(messages: List<LLMMessage>, candidatesStart: Int): Set<String> {
    if (!ToolOutputSpill.canReadInChat(context, activeSessionId)) return emptySet()
    val filesDir = context.filesDir
    val resolver = com.openminis.app.sandbox.PathResolver.forSessionLayout(filesDir, activeSessionId)
    val seen = HashSet<String>()
    return buildSet {
        for (index in candidatesStart until messages.size) {
            for (result in messages[index].contentParts) {
                if (result !is AgentContentPart.ToolResult) continue
                val path = ToolResultProjection.spillPathForRecovery(result.content, result.name) ?: continue
                if (seen.add(path) && ToolOutputSpill.hostFile(resolver, filesDir, activeSessionId, path) != null) add(path)
            }
        }
    }
}
