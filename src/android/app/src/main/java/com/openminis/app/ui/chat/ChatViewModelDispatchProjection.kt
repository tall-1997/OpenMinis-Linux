package com.openminis.app.ui.chat

import com.openminis.app.agent.HarnessConfig
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.ContextSaverPrefs
import com.openminis.app.data.OutboundMaskPrefs
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

internal data class DispatchProjectionKey(
    val sessionId: String,
    val model: ModelAttributionSnapshot,
    val harness: HarnessConfig,
    val policy: ContextPolicy,
    val window: Int,
    val markerId: String?,
    val thinking: ThinkingLevel,
    val saver: ContextSaverPrefs.Mode,
    val outboundMask: Boolean,
    val stall: String?,
    val coverageGap: Boolean,
    val date: LocalDate,
    val digest: String?,
)

/** Single-run cache for immutable projections. Dynamic memory and unsent clock notes are rebuilt. */
internal class DispatchProjectionCache {
    private class Entry(
        val key: DispatchProjectionKey,
        val history: List<LLMMessage>,
        val prompt: MemoryDeliveryPrompt?,
        val tools: List<AgentToolDefinition>,
        val projection: ProjectedChatRequest,
    )

    private var entry: Entry? = null

    fun reuse(
        key: DispatchProjectionKey,
        history: List<LLMMessage>,
        prompt: MemoryDeliveryPrompt?,
        tools: List<AgentToolDefinition>,
    ): ProjectedChatRequest? = entry?.takeIf {
        it.key == key && it.prompt === prompt && it.tools === tools &&
            it.history.size == history.size && history.indices.all { i -> it.history[i] === history[i] }
    }?.projection

    fun store(
        key: DispatchProjectionKey,
        history: List<LLMMessage>,
        prompt: MemoryDeliveryPrompt?,
        tools: List<AgentToolDefinition>,
        projection: ProjectedChatRequest,
    ) {
        entry = Entry(key, history, prompt, tools, projection)
    }

    fun clear() { entry = null }
}

internal suspend fun ChatViewModel.buildDispatchProjection(
    cache: DispatchProjectionCache,
    sessionId: String,
    provider: LLMProvider,
    prompt: MemoryDeliveryPrompt?,
    tools: List<AgentToolDefinition>,
    stallNote: String?,
): ProjectedChatRequest? = withContext(Dispatchers.IO) {
    if (activeSessionId != sessionId) return@withContext null
    val history = agentHistorySnapshot()
    val model = ProviderFactory.requestAttribution(provider)
    val window = requestContextWindow(provider, model)
    val policy = ContextPolicy.forContextWindow(window, effectiveCompactPercent())
    val harness = activeHarnessConfig(requestEntryId(model))
    val compact = requestCompactStateFor(history, sessionId)
    if (activeSessionId != sessionId) return@withContext null
    val clock = requestClockNote(sessionId, history)
    val digest = HistoryDigest.render(llmDigestLines, llmDigestOmitted)
    // [T-digest-covers-gap] Coverage gaps are no longer an input to compaction,
    // so the digest is the only thing standing between the model and a silent
    // hole. Log the one state that would break that contract.
    if (coverageGapWithoutDigest(compact.hasCoverageGap, digest)) {
        com.openminis.app.logging.AppLogger.warning(
            "HistoryDigest",
            "[T-digest-covers-gap] coverage gap without a rendered digest: " +
                "lines=${llmDigestLines.size} omitted=$llmDigestOmitted session=$sessionId",
        )
    }
    val key = DispatchProjectionKey(
        sessionId, model, harness, policy, window, compact.marker?.id,
        requestThinkingLevel(provider), ContextSaverPrefs.mode(), OutboundMaskPrefs.isEnabled, stallNote,
        compact.hasCoverageGap, LocalDate.now(), digest,
    )
    // On disk, diary governance and TTL may have changed without editing the transcript.
    val reusable = !_memoryEnabled.value && clock == null && model.contextRouteId != null
    if (reusable) cache.reuse(key, history, prompt, tools)?.let { return@withContext it }
    else cache.clear()

    prefetchSemanticRecallForRequest(history)
    val projected = projectChatRequest(
        provider, prompt, history, tools, key.thinking,
        stallNote = stallNote, uncoveredBeforeResident = compact.hasCoverageGap,
        persistImageSpillover = true, requestSessionId = sessionId, clockNote = clock,
        attribution = model, harnessConfig = harness, window = window, policy = policy,
        historyDigest = digest, saverMode = key.saver, outboundMask = key.outboundMask, marker = compact.marker,
    )
    if (activeSessionId != sessionId) return@withContext null
    if (reusable && projected.markerId == key.markerId) cache.store(key, history, prompt, tools, projected)
    projected
}
