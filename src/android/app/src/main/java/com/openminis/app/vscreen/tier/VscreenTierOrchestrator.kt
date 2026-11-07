package com.openminis.app.vscreen.tier

import android.content.Context

/**
 * [T-android-vscreen-tier] The create attempt chain: walk the tiers the
 * state machine says are attemptable, in priority order; on the first
 * success register the tier on the screen; on failure mark the tier
 * degraded (so the NEXT create skips it without re-paying its timeout) and
 * fall through to the next tier. The chain ends at NATIVE — the terminal
 * fallback that is structurally always available — so "all tiers failed"
 * can only mean the device itself refused the display.
 *
 * Extracted from VirtualScreenManager (architecture line ceiling): the
 * manager keeps the registry, sanitization and post-create upkeep; this
 * file owns ONLY the tier ladder.
 */
class VscreenTierOrchestrator(
    private val state: TierStateMachine,
    private val providers: Map<VscreenTier, DisplayProvider>,
) {
    /** A screen was created on [tier]; [fallbackNotes] records what failed before it. */
    data class Orchestrated(
        val tier: VscreenTier,
        val displayId: Int,
        val note: String?,
        val handle: com.openminis.app.vscreen.VirtualScreenManager.DisplayHandle?,
        val fallbackNotes: List<String>,
    ) : Step()

    /** Every attemptable tier failed — [summary] carries one honest line per tier. */
    data class OrchestratedFailure(val summary: String, val fallbackNotes: List<String>) : Step()

    sealed class Step

    fun create(request: ProviderCreateRequest, context: Context?): Step {
        val fallback = mutableListOf<String>()
        for (tier in TierSelector.planOrder(state.sheet(context))) {
            val outcome = runCatching { providers.getValue(tier).create(request, context) }
                .getOrElse { ProviderOutcome.Failed("tier crashed: ${it.message}") }
            when (outcome) {
                is ProviderOutcome.Created -> return Orchestrated(
                    tier, outcome.displayId, outcome.note, outcome.handle, fallback,
                )
                is ProviderOutcome.Failed -> {
                    state.markDegraded(tier, outcome.reason)
                    fallback += "${tier.name.lowercase()}: ${outcome.reason}"
                }
            }
        }
        return OrchestratedFailure(TierSelector.failureSummary(state.sheet(context)), fallback)
    }
}
