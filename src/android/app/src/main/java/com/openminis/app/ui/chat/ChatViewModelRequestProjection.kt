package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.agent.ContextSaverRuntime
import com.openminis.app.agent.HarnessConfig
import com.openminis.app.agent.ToolResultProjection
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.ContextSaverPrefs
import com.openminis.app.data.ContextTokenEstimate
import com.openminis.app.data.ContextTokenMeter
import com.openminis.app.data.ContextUsageAttempt
import com.openminis.app.data.OutboundMaskPrefs
import com.openminis.app.data.calibratedContextTokens
import com.openminis.app.data.captureContextUsageAttempt
import com.openminis.app.data.estimateProjectedContext
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.repository.PairingWriteBackRow
import com.openminis.app.data.repository.writeBackInterruptedResults
import kotlinx.coroutines.launch
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.ToolIdNormalization
import com.openminis.app.data.repository.CompactMarkerView
import com.openminis.app.data.repository.RequestCompactState
import com.openminis.app.data.repository.requestCompactState
import com.openminis.app.data.repository.toView
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.api.ChatRequest
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.projectOutboundRequest
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
    val hasRequestLocalContext: Boolean = false,
    val clockNote: RequestClockNote? = null,
    val pairingRepair: PairingRepair? = null,
    val carriesChatContext: Boolean = true,
    val summaryChars: Int = 0,
    val outboundMask: Boolean = false,
    val outboundRequest: ChatRequest = request,
) {
    // A retry of the same immutable projection need not hash the request again.
    val usageAttempt: ContextUsageAttempt? by lazy {
        if (carriesChatContext) captureContextUsageAttempt(outboundRequest, model, markerId, rawTokens, hasRequestLocalContext) else null
    }
}

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
    val requested = if (forced || provider.model.supportsReasoning != false) _thinkingLevel.value else ThinkingLevel.OFF
    return provider.clampThinkingLevel(requested)
}

internal fun ChatViewModel.publishRequestProjection(projection: ProjectedChatRequest) {
    ContextSaverRuntime.recordProjection(projection.agingStats)
    projection.imageElision?.let { _requestBudgetEvent.tryEmit(it) }
    refreshContextUsage(projection.estimate, projection.window)
}

/** The Room marker and its coverage belong to the same read, not to the display cache. */
internal suspend fun ChatViewModel.requestCompactStateFor(
    history: List<LLMMessage>,
    sessionId: String,
): RequestCompactState = chatRepository.requestCompactState(sessionId, history.firstNotNullOfOrNull { it.dbMessageId })

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
    uncoveredBeforeResident: Boolean = false,
    persistImageSpillover: Boolean = false,
    requestSessionId: String = activeSessionId,
    clockNote: RequestClockNote? = null,
    attribution: ModelAttributionSnapshot = ProviderFactory.requestAttribution(provider),
    harnessConfig: HarnessConfig = activeHarnessConfig(requestEntryId(attribution)),
    window: Int = requestContextWindow(provider, attribution),
    policy: ContextPolicy = ContextPolicy.forContextWindow(window, effectiveCompactPercent()),
    historyDigest: String? = HistoryDigest.render(llmDigestLines, llmDigestOmitted),
    saverMode: ContextSaverPrefs.Mode = ContextSaverPrefs.mode(),
    outboundMask: Boolean = OutboundMaskPrefs.isEnabled,
    marker: CompactMarkerView? = _cachedLatestMarker?.toView(),
): ProjectedChatRequest {
    // Codex image generation has its own instructions and only forwards the last user text.
    if ((provider as? OpenAIProvider)?.isCodexImageModel == true) {
        val input = history.lastOrNull { it.role == LLMMessage.Role.USER }
        val request = ChatRequest(listOfNotNull(input), maxTokens = 0)
        val outbound = projectOutboundRequest(request, outboundMask)
        val raw = ContextTokenMeter.rawRequestTokens(outbound)
        return ProjectedChatRequest(
            request, null, null, attribution, marker?.id, window, policy, raw,
            ContextTokenEstimate(raw), false, ContextSaverRuntime.Stats(), null,
            carriesChatContext = false, outboundMask = outboundMask, outboundRequest = outbound,
        )
    }
    val memoryQuery = memoryRecallQueryForHistory(history)
    val prompt = basePrompt?.let {
        projectMemoryDeliveryPrompt(wrapMemoryDeliveryPromptForOAuth(provider, it, publish = false), query = memoryQuery)
    }
    val recall = newRecallReminder(prompt?.daily, query = memoryQuery, turnId = memoryRecallTurnIdForHistory(history))
    // The marker does not cover the active tail. Preserve it through the local reducers;
    // the dispatch gate checks the resulting payload and any actual gap in resident history.
    val assembled = effectiveAgentHistoryUncounted(
        history = history.withoutStoredRecallReminders(),
        windowTokens = window,
        marker = marker,
        applyTailBudget = false,
        forRequest = persistImageSpillover,
        harnessConfig = harnessConfig,
    )
    // A legacy marker may have removed the matching call from this projection.
    val repair = repairToolPairing(neutralizeRemoteToolResults(projectLegacyOrphanedStops(assembled)))
    // [T-pairing-writeback] Persist the neutral results this repair just
    // synthesized: once the rows land, later projections find the call already
    // matched and stop injecting a synthetic user turn on every request.
    // Fire-and-forget — the repair above still renders a correct request even
    // if the write fails, and the next projection retries.
    if (repair.interruptedUseRefs.isNotEmpty()) {
        viewModelScope.launch {
            val rows = repair.interruptedUseRefs.mapNotNull { ref ->
                val partsJson = buildToolResultPartsJson(
                    listOf(
                        AgentContentPart.ToolResult(
                            id = ref.toolUseId,
                            name = ref.toolName,
                            content = "[Tool execution was interrupted before resume]",
                            isError = false,
                        ),
                    ),
                ) ?: return@mapNotNull null
                PairingWriteBackRow(
                    assistantRowId = ref.assistantRowId,
                    toolUseId = ref.toolUseId,
                    partsJson = partsJson,
                )
            }
            if (rows.isNotEmpty()) {
                chatRepository.writeBackInterruptedResults(requestSessionId, rows)
            }
        }
    }
    val scrubbed = TextifiedImageGuard.scrub(repair.history).history
    val normalized = ToolIdNormalization.normalizeMessages(
        HistoryDigest.inject(scrubbed, historyDigest).map(::projectAssistantText),
    )
    val placedClock = clockNote?.takeIf { it.sessionId == requestSessionId && normalized.any { m -> m.role == LLMMessage.Role.USER } }
    fun addReminders(messages: List<LLMMessage>): List<LLMMessage> = appendRequestReminders(messages, recall, placedClock, stallNote)
    val keepRecent = if (saverMode == ContextSaverPrefs.Mode.OFF) ToolResultProjection.DEFAULT_KEEP_RECENT else saverMode.keepRecent
    val pressure = calibratedContextTokens(attribution, ContextTokenMeter.rawRequestTokens(addReminders(normalized), prompt?.prompt, tools))
    val microTriggerTokens = (policy.compactThreshold * MICRO_TRIGGER_FRACTION_OF_COMPACT).toInt()
    val prunable = (microTriggerTokens > 0 && pressure >= microTriggerTokens) ||
        ContextSaverRuntime.willAge(normalized, window, pressure, config = harnessConfig)
    val verifiedPaths = if (prunable) {
        verifiedRequestSpillPaths(normalized, ToolResultProjection.protectedSuffixStart(normalized, keepRecent), requestSessionId)
    } else emptySet()
    val aged = ContextSaverRuntime.planAge(normalized, window, pressure, verifiedPaths, keepRecent, config = harnessConfig)
    // [T-micro-prune-post-aging-pressure] The prune trigger must meter the list
    // it is about to prune. Aging runs FIRST and folds old results, so the
    // pre-aging `pressure` is always an OVERESTIMATE of the aged request: reusing
    // it re-fires the (lossy) head/tail trim on requests aging already brought
    // under the trigger, breaking microPruneOldToolResults' pass-through
    // contract. Same instance = aging folded nothing = the reading is current.
    val prunePressure = if (aged.messages === normalized) pressure
    else calibratedContextTokens(attribution, ContextTokenMeter.rawRequestTokens(addReminders(aged.messages), prompt?.prompt, tools))
    val pruned = microPruneOldToolResults(
        history = aged.messages,
        usedTokens = prunePressure.toLong(),
        windowTokens = window.toLong(),
        keepRecent = keepRecent,
        triggerTokens = microTriggerTokens.toLong(),
        verifiedSpillPaths = verifiedPaths,
    )
    var imageElision: ImageBudget.RequestBudgetPlan? = null
    val stableMessages = applyRequestImageBudget(pruned, persistImageSpillover && activeSessionId == requestSessionId) { imageElision = it }
    val request = ChatRequest(addReminders(stableMessages), prompt?.prompt, maxTokens = 0, tools = tools, thinkingLevel = thinking)
    val outbound = projectOutboundRequest(request, outboundMask)
    val raw = ContextTokenMeter.rawRequestTokens(outbound)
    val hasRequestLocalContext = request.messages !== stableMessages
    // Model the pre-reminder rows under the same privacy policy as the full request.
    val stableOutbound = if (hasRequestLocalContext) {
        projectOutboundRequest(request.copy(messages = stableMessages), outboundMask)
    } else outbound
    val stableRaw = if (stableOutbound === outbound) raw else ContextTokenMeter.rawRequestTokens(stableOutbound)
    val requestLocalTokens = (raw - stableRaw).coerceAtLeast(0)
    val stableEstimate = estimateProjectedContext(stableOutbound, attribution, marker?.id, stableRaw)
    val estimate = if (stableEstimate.measuredPrefixTokens == null) {
        ContextTokenEstimate(calibratedContextTokens(attribution, raw))
    } else {
        val extra = calibratedContextTokens(attribution, requestLocalTokens)
        stableEstimate.copy(tokens = (stableEstimate.tokens.toLong() + extra).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
    return ProjectedChatRequest(
        request = request,
        prompt = prompt,
        recall = recall,
        model = attribution,
        markerId = marker?.id,
        window = window,
        policy = policy,
        rawTokens = raw,
        estimate = estimate,
        hasCoverageGap = uncoveredBeforeResident,
        agingStats = aged.stats,
        imageElision = imageElision,
        hasRequestLocalContext = hasRequestLocalContext,
        clockNote = placedClock,
        pairingRepair = repair.takeIf { it.changed },
        summaryChars = marker?.summary?.length ?: 0,
        outboundMask = outboundMask,
        outboundRequest = outbound,
    )
}

/** Do not infer the original rows by stripping matching text from a projected request. */
internal fun appendRequestReminders(
    messages: List<LLMMessage>,
    recall: RecallReminder?,
    clockNote: RequestClockNote?,
    stallNote: String?,
): List<LLMMessage> {
    val reminded = messages.withMemoryRecallReminder(recall)
        .withRequestReminder(clockNote?.text)
    if (stallNote.isNullOrBlank()) return reminded
    if (reminded.lastOrNull()?.role == LLMMessage.Role.USER) return reminded.withRequestReminder(stallNote)
    return reminded + LLMMessage(
        role = LLMMessage.Role.USER,
        content = stallNote,
        contentParts = listOf(AgentContentPart.Text(stallNote)),
        isHostGenerated = true,
    )
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

private fun List<LLMMessage>.withRequestReminder(text: String?): List<LLMMessage> {
    if (text.isNullOrBlank()) return this
    val index = indexOfLast { it.role == LLMMessage.Role.USER }
    if (index < 0) return this
    return toMutableList().apply { this[index] = appendUserText(this[index], text) }
}

/** Only the prefix outside the protected suffix is eligible for folding. */
internal fun requestSpillCandidates(messages: List<LLMMessage>, protectedFrom: Int): Sequence<String> =
    messages.asSequence().take(protectedFrom).flatMap { it.contentParts.asSequence() }
        .filterIsInstance<AgentContentPart.ToolResult>()
        .filterNot {
            it.isError || it.imageData != null || it.imageMimeType != null || it.imageLinuxPath != null ||
                ToolResultProjection.isProjected(it.content)
        }
        .mapNotNull { ToolResultProjection.spillPathForRecovery(it.content, it.name) }
        .distinct()

private fun ChatViewModel.verifiedRequestSpillPaths(
    messages: List<LLMMessage>,
    protectedFrom: Int,
    sessionId: String,
): Set<String> {
    val candidates = requestSpillCandidates(messages, protectedFrom).iterator()
    if (!candidates.hasNext() || !ToolOutputSpill.canReadInChat(context, sessionId)) return emptySet()
    val filesDir = context.filesDir
    val resolver = com.openminis.app.sandbox.PathResolver.forSessionLayout(filesDir, sessionId)
    return candidates.asSequence().filter { ToolOutputSpill.hostFile(resolver, filesDir, sessionId, it) != null }.toSet()
}
