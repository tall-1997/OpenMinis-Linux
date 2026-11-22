package com.openminis.app.goal

import com.openminis.app.tools.AgentPlanStore
import com.openminis.app.util.Digest

/**
 * [T-goal-drift-reconciler] Mechanical per-turn reconciliation of the three
 * host-held artifacts — the session Goal, the plan board ([AgentPlanStore])
 * and the tool-flow ledger — against each other. NO model involvement: the
 * reconciler answers "did the board move" from host data alone, at O(1) cost
 * per tool turn. Delivery of a nudge happens at the natural turn end in
 * [ChatViewModelAgentLoopExt] (the proven persist-then-append path), so a
 * stall accumulating mid-run costs nothing until a boundary speaks.
 *
 * Scope honesty: this catches STRUCTURAL drift only — steps stalled, board
 * frozen while tokens burn. Semantic drift (every step plausible, direction
 * wrong) is the model's to catch via the per-request `<goal_context>` mount;
 * natural-language checking is the completion verifier's deliberate
 * out-of-scope, and it stays out of scope here too.
 *
 * Signals deliberately NOT duplicated here:
 *  - budget exhaustion → GoalStateMachine BUDGET_LIMITED + shouldExitForGoalState
 *  - tool-call loops → ToolLoopDetector (WARNING/CRITICAL levels)
 */
internal object GoalDriftReconciler {

    /**
     * Tool turns of an unchanged board tolerated before a drift nudge is owed.
     * [STALL_TURNS_BEFORE_NUDGE] is the DEFAULT — the knob is user-configurable
     * ([GoalDriftPrefs], clamped to [STALL_TURNS_MIN]..[STALL_TURNS_MAX]) and
     * flows into [nudgeIfOwed] as a parameter. 8 turns ≈ two plans of three
     * steps each going silent; below that, ordinary between-step pauses
     * (reflection turns, user replies) would trip it.
     */
    internal const val STALL_TURNS_BEFORE_NUDGE = 8
    internal const val STALL_TURNS_MIN = 4
    internal const val STALL_TURNS_MAX = 32

    /** Nudge text must stay bounded even for a runaway objective blob. */
    private const val OBJECTIVE_MAX_LINES = 12

    /**
     * Fingerprint over the board's (id, status) pairs. Any status change —
     * progress OR a step flipped to failed — changes it and resets the stall
     * counter: the host cannot tell "moved forward" from "moved sideways", and
     * must not — a sideways move is still the model tending the board, which
     * is exactly what the counter exists to notice. Null when there is no
     * board at all (nothing to reconcile against).
     */
    fun boardFingerprint(plans: List<AgentPlanStore.Plan>): String? {
        if (plans.isEmpty()) return null
        val payload = plans.sortedBy { it.position }.joinToString("|") { "${it.id}:${it.status}" }
        return Digest.sha256Hex(payload).take(16)
    }

    /**
     * Recheck the current Goal and board without counting a turn. Losing the
     * ACTIVE goal or the board resets everything; a changed board starts a
     * new baseline at zero and clears the delivered-once marker.
     */
    fun observe(
        state: GoalDriftStore.DriftState,
        goalActive: Boolean,
        fingerprint: String?,
    ): GoalDriftStore.DriftState = when {
        !goalActive || fingerprint == null -> GoalDriftStore.DriftState()
        fingerprint != state.lastBoardSha -> GoalDriftStore.DriftState(lastBoardSha = fingerprint)
        else -> state
    }

    /** Count one committed tool turn, but only if it left the board unchanged. */
    fun next(
        state: GoalDriftStore.DriftState,
        goalActive: Boolean,
        fingerprint: String?,
    ): GoalDriftStore.DriftState {
        val observed = observe(state, goalActive, fingerprint)
        return if (observed.lastBoardSha != null && observed.lastBoardSha == state.lastBoardSha) {
            observed.copy(stallTurns = observed.stallTurns + 1)
        } else observed
    }

    /**
     * The drift nudge text, or null when nothing is owed: below the stall
     * threshold, or already delivered for THIS stalled fingerprint. The
     * delivered-once marker is the repeated-injection lesson of #315/#316/#330
     * — a stall that persists must not re-banner every turn; the model was
     * told, and re-telling burns the attention the nudge exists to redirect.
     */
    fun nudgeIfOwed(
        state: GoalDriftStore.DriftState,
        objective: String,
        pendingCount: Int,
        threshold: Int = STALL_TURNS_BEFORE_NUDGE,
    ): String? {
        // Clamp the user knob: a caller wiring an unvalidated setting must not
        // be able to disable the net (0/negative) or bury it (huge).
        val effective = threshold.coerceIn(STALL_TURNS_MIN, STALL_TURNS_MAX)
        if (state.stallTurns < effective) return null
        if (state.lastBoardSha == null || state.nudgedBoardSha == state.lastBoardSha) return null
        return buildString {
            append("<system-reminder>\n")
            append("Goal reconciliation: the session goal is still ACTIVE, but the plan board has not changed ")
            append("for ").append(state.stallTurns).append(" consecutive tool turns (pending steps: ")
            append(pendingCount).append(").\n")
            // [T-goal-drift-reconciler] The objective IS the definition of done:
            // acceptance checks ride inside it as lines (no dedicated column),
            // so render it in full, line-bounded, instead of a one-line snippet.
            append("Objective — the definition of done for this goal:\n")
            val lines = objective.lines().filter { it.isNotBlank() }
            lines.take(OBJECTIVE_MAX_LINES).forEach { append("- ").append(it.trim()).append('\n') }
            if (lines.size > OBJECTIVE_MAX_LINES) append("- …(truncated)\n")
            append(
                "Before this turn ends, do exactly one of: (a) name the pending step the recent work advanced " +
                    "and continue it, (b) change course — update the board to match the real path, or (c) call " +
                    "update_goal(op=blocked) with the specific blocker. Do not keep producing turns that do " +
                    "not move the board.",
            )
            append("\n</system-reminder>")
        }
    }
}
