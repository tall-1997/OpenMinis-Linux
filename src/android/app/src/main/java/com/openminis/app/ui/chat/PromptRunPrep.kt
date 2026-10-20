package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger

/**
 * [T-prompt-cache-turn-context] Prepare the outbound prompt for one agent run.
 *
 * Two things have to happen before the first request:
 *  1. `agentHistory` is rebuilt from Room (the transcript authority). See
 *     [prepareRunHistory] for the stop-cleanup ordering and the
 *     historyAlreadyRebuilt skip.
 *  2. The per-turn context is computed ONCE and stored in
 *     [PromptCacheState.turnContext]; [effectiveAgentHistory] appends it to the
 *     latest user-text message. It deliberately does NOT go into the system
 *     prompt: a per-turn block there sits before the whole conversation and
 *     ends every provider's prefix cache at that byte (OpenAI/DeepSeek
 *     re-ingest all messages; Anthropic's message breakpoints include it too).
 *
 * Also emits the [T-prompt-cache-diag] fingerprint and the context-assembly
 * snapshot. Extracted from runAgentLoop so the oversized-file ratchet stays
 * satisfied; behavior is unchanged.
 */
internal suspend fun ChatViewModel.preparePromptForRun(
    historyAlreadyRebuilt: Boolean,
    systemPrompt: String?,
) {
    prepareRunHistory(historyAlreadyRebuilt)
    promptCache.turnContext = buildTurnContextFragment()
    // [T-prompt-cache-diag] One line per user turn: identical fingerprints
    // across turns mean system + tools are cache-stable. Compare these when a
    // hit-rate report looks low; a changing value names the culprit region.
    AppLogger.info(
        ChatViewModel.TAG_STREAM,
        "[CacheDiag] fingerprint=${promptCacheFingerprint(systemPrompt, agentTools)} " +
            "systemChars=${systemPrompt?.length ?: 0} tools=${agentTools.size} " +
            "turnCtxChars=${promptCache.turnContext?.length ?: 0} history=${agentHistory.size}",
    )
    runCatching {
        com.openminis.app.agent.ContextAssemblySnapshot.capture(
            buildString {
                append(systemPrompt.orEmpty())
                promptCache.turnContext?.let {
                    append("\n\n[per-turn context — attached to the latest user message]\n")
                    append(it)
                }
            },
            activeSessionId,
            context,
        )
    }
}
