package com.openminis.app.harness.agent

import java.util.concurrent.atomic.AtomicLong

/**
 * Shared rollout token budget across all sub-agents of one dispatch fan-out
 * (Codex `rollout_budget` semantics, rewritten in Kotlin).
 *
 * Every sub-agent turn records its weighted usage here via [recordUsage].
 * Weighting mirrors Codex: output tokens are charged at a higher rate than
 * prefill, because a fan-out of N workers each streaming 8K output burns
 * battery and provider quota far faster than their shared context reads.
 *
 * The verdict is a HARD stop, not an advisory: when [recordUsage] returns
 * false the caller (SubAgentRunner) aborts the current sub-agent lane and
 * wraps up with a partial report, exactly like Codex `SessionBudgetExceeded`.
 * The old advisory-only `<budget_warning>` remains as the softer, earlier
 * signal at 80%/95% of the TURN budget; this class governs the TOKEN budget
 * shared across the whole wave.
 *
 * Budget scope: one `spawn_agent` dispatch, OPT-IN. The coordinator creates a
 * [SubAgentTokenBudget] only when the dispatch carries an explicit
 * `token_budget`; otherwise every lane runs to its turn budget with no token
 * cap at all (a silent default cap would surface to the user as lost work).
 */
class SubAgentTokenBudget(
    limitTokens: Long,
) {
    init {
        require(limitTokens > 0) { "token budget must be positive" }
    }

    private val weightedUsedMilli = AtomicLong(0)

    val limitTokens: Long = limitTokens

    /** Remaining weighted tokens, floored at 0. */
    val remainingTokens: Long
        get() = (limitTokens - usedTokens).coerceAtLeast(0)

    val usedTokens: Long
        get() = weightedUsedMilli.get() / 1000

    val exhausted: Boolean
        get() = weightedUsedMilli.get() >= limitTokens * 1000

    /** Codex default weights: sampling costs 1.0, prefill 0.1. */
    fun recordUsage(outputTokens: Int, prefillTokens: Int): Boolean =
        recordWeighted(
            outputTokens.toLong() * SAMPLING_WEIGHT_MILLI +
                prefillTokens.toLong() * PREFILL_WEIGHT_MILLI,
        )

    /**
     * Record raw weighted usage. Returns false when the budget is exhausted
     * AFTER this record — the caller must stop the lane. The usage is still
     * recorded (Codex semantics: "the usage was recorded and the shared
     * budget is now exhausted").
     */
    fun recordWeighted(milliTokens: Long): Boolean {
        val before = weightedUsedMilli.getAndAdd(milliTokens.coerceAtLeast(0))
        val after = before + milliTokens.coerceAtLeast(0)
        return after < limitTokens * 1000
    }

    companion object {
        const val SAMPLING_WEIGHT_MILLI = 1000L
        const val PREFILL_WEIGHT_MILLI = 100L

        /** Only used when the model explicitly requests a cap. */
        // [T-subagent-budget-constant-role] This is the CEILING applied to a
        // requested cap (and the value substituted for a malformed one) — NOT
        // a dispatch default. Dispatch stays opt-in by design: when no lane
        // passes token_budget there is deliberately NO shared cap, because a
        // silent default cut long-running workers off mid-task with a
        // partial report (see the ChatViewModelSubAgentExt dispatch site).
        const val DEFAULT_LIMIT_TOKENS = 400_000L

        /** Hard ceiling the coordinator may request (keeps runaway briefs bounded). */
        const val MAX_LIMIT_TOKENS = 2_000_000L

        fun clamp(requested: Long?): Long = when {
            requested == null || requested <= 0 -> DEFAULT_LIMIT_TOKENS
            else -> requested.coerceIn(1_000L, MAX_LIMIT_TOKENS)
        }
    }
}
