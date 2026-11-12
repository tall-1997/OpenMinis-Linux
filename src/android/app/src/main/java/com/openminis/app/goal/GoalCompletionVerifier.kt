package com.openminis.app.goal

/**
 * [T-goal-completion-verifier] Host-side check that runs when the model
 * declares the Goal complete (`update_goal(op=complete)`).
 *
 * ZCode parity: the model's completion call is NOT the last word — the host
 * runs a verifier and only then accepts the completion. Here the one
 * structured signal this app has is the plan board
 * ([com.openminis.app.tools.AgentPlanStore]): a board the model opened for
 * the task is the model's own checklist, and "complete" while steps are
 * still pending or failed is exactly the premature completion the verifier
 * exists to catch.
 *
 * Honest boundary: the objective itself is free text with NO parsed
 * acceptance criteria, so there is nothing to re-verify it against — a
 * session with no board (or an empty one) passes on the strength of the
 * model's call. The verifier only arbitrates the layer it can actually read:
 * the board. Natural-language checking is deliberately out of scope.
 */
object GoalCompletionVerifier {

    /**
     * Consecutive host rejections tolerated before the model's call is
     * accepted anyway. Same number as the blocked-condition confirmations
     * ([GoalStateMachine.BLOCKED_CONFIRMATIONS]): three host verdicts in a
     * row mean the verifier and the model disagree persistently, and
     * dead-locking the Goal against its own agent helps nobody — the model
     * may know the board is stale (steps the plan outgrew) in a way the
     * diff cannot see. Forced acceptance carries the verifier's last
     * verdict so the transcript records the disagreement.
     */
    const val MAX_REJECTIONS = GoalStateMachine.BLOCKED_CONFIRMATIONS

    /**
     * One verifier verdict. [allowed] means the completion is accepted;
     * [reason] names the gap when it is not (and is null when there is
     * nothing to check). The reason text is shown to the model verbatim —
     * it is the rejection's only channel back to the model.
     */
    data class Verification(val allowed: Boolean, val reason: String? = null)

    /**
     * The decision for a `complete` call, given the plan board statuses the
     * caller read for this session (empty list = no board / empty board).
     *
     * - Board with items: every step done AND none failed → allowed. A
     *   pending step is unfinished work; a failed step is a reported
     *   failure the completion would paper over. Either way the completion
     *   is rejected with the board's progress named.
     * - No board: allowed with no reason — there is no structured signal
     *   to check (the honest boundary above).
     */
    fun verifyCompletion(planStatuses: List<String>): Verification {
        if (planStatuses.isEmpty()) return Verification(allowed = true)
        val done = planStatuses.count { it == "done" }
        val failed = planStatuses.count { it == "failed" }
        if (done == planStatuses.size && failed == 0) return Verification(allowed = true)
        return Verification(
            allowed = false,
            reason = "Host verification rejected the completion: plan board is " +
                "$done/${planStatuses.size} done" +
                (if (failed > 0) ", $failed failed" else "") +
                ". Finish the remaining steps or keep working; do not re-report " +
                "complete until every board step is done.",
        )
    }

    /**
     * The verdict text for a FORCED acceptance (the rejection limit was
     * reached): the model's call is accepted, and the transcript records
     * the verifier's standing objection so the disagreement stays visible.
     */
    fun forcedAcceptanceReason(planStatuses: List<String>): String {
        if (planStatuses.isEmpty()) return ""
        val done = planStatuses.count { it == "done" }
        val failed = planStatuses.count { it == "failed" }
        return "Host verification could not confirm the board after " +
            "$MAX_REJECTIONS attempts (plan board is $done/${planStatuses.size} done" +
            (if (failed > 0) ", $failed failed" else "") +
            ") — accepting the model's call anyway; the Goal is marked complete."
    }
}
