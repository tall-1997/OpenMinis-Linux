package com.openminis.app.ui.chat

import com.openminis.app.agent.ContextSaverRuntime
import com.openminis.app.agent.HarnessConfig
import com.openminis.app.data.HarnessConfigStore
import com.openminis.app.data.model.LLMMessage

/**
 * [T-harness-config-resolve] The context budget for the model this chat is
 * currently using.
 *
 * Resolved ONCE per assembly and passed down explicitly, never installed into a
 * global: a global is what let the previous attempt (a) stay unreachable while
 * nothing wrote it and (b) leak one chat's numbers into another.
 *
 * Falls back to the shipped defaults when the entry id is unknown (tests, a
 * one-shot caller) or the stored file is missing/invalid.
 */
internal fun ChatViewModel.activeHarnessConfig(): HarnessConfig =
    HarnessConfigStore.forModel(context.filesDir, _activeEntryId.value)

/**
 * [T-harness-config-resolve] Aging with the model's own thresholds.
 *
 * Kept here because ChatViewModel is at its line ceiling, and because
 * "which config does this chat's model carry" belongs next to the resolver.
 */
internal fun ChatViewModel.agedHistoryForRequest(history: List<LLMMessage>): List<LLMMessage> =
    ContextSaverRuntime.age(
        history, currentModelContextWindow ?: 0, _lastTurnContextTokens.value, activeHarnessConfig(),
    )

/** [T-compact-detached-anchor] Detached-marker assembly with the model's budget. */
internal fun ChatViewModel.detachedCompactHistoryWithHarness(
    summaryWrappedText: String,
): List<LLMMessage> {
    val history = agentHistorySnapshot()
    val harness = activeHarnessConfig()
    val tailSize = detachedTailSizeForWindow(history, effectiveContextWindowTokens(), harness)
    return buildDetachedCompactHistory(summaryWrappedText, history, tailSize, config = harness)
}

/** [T-memory-usage-ui] Delivered memory lines for THIS chat: (lines, times shown). */
internal fun ChatViewModel.memoryUsageSummary(): Pair<Int, Int> = runCatching {
    val sid = realSessionId.ifEmpty { sessionId }
    val dir = com.openminis.app.sandbox.SessionWorkspace.memoryDir(context.filesDir, sid)
    com.openminis.app.data.repository.MemoryUsageLedger.summaryFor(dir)
}.getOrDefault(0 to 0)
