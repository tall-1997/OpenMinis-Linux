package com.openminis.app.ui.chat

import com.openminis.app.agent.GenerationRunLifecycle
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.provider.LLMProvider

/**
 * [T-generation-run] Entry point for one model run, and the boundary that owns
 * the generation ledger.
 *
 * [runAgentLoopBody] closes its own ledger on the paths it can see: a completed
 * stream records DONE, and a turn-cap stop records FAILED at the tail. What it
 * cannot see is the path where the provider's terminal error is thrown straight
 * out of the loop — that throw skips the tail entirely, leaving the row RUNNING.
 * At the next launch that row is indistinguishable from a process that died
 * mid-run, so the user gets an "interrupted generation" hint for a request that
 * simply failed.
 *
 * Wrapping the body here closes that hole with one hook instead of one per call
 * site (send / resume / retry / queue-drain / rerun-tail), and without
 * re-indenting the loop to install a `try/finally` inside it.
 *
 * The wrapper owns no policy of its own: parameters are forwarded verbatim, and
 * [GenerationRunLifecycle.closeOpen] is a no-op whenever the body already
 * recorded a verdict.
 */
internal suspend fun ChatViewModel.runAgentLoop(
    provider: LLMProvider,
    systemPrompt: String?,
    fallbackProviders: List<ChatViewModel.FallbackCandidate> = emptyList(),
    fallbackStrategy: FallbackStrategy = FallbackStrategy.default,
    goalExecutionRun: Boolean = false,
    overrideAssistantId: String? = null,
) {
    // Captured BEFORE the run: the ledger must describe the chat the run was
    // started on even if the user switches sessions while it streams.
    val ledgerSessionIds = listOf(activeSessionId, sessionId, realSessionId)
        .filter { it.isNotBlank() }
        .distinct()
    try {
        runAgentLoopBody(
            provider = provider,
            systemPrompt = systemPrompt,
            fallbackProviders = fallbackProviders,
            fallbackStrategy = fallbackStrategy,
            goalExecutionRun = goalExecutionRun,
            overrideAssistantId = overrideAssistantId,
        )
    } finally {
        // Non-suspending by design — this may run in a cancelled coroutine.
        GenerationRunLifecycle.closeOpen(context, ledgerSessionIds)
    }
}
