package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.db.GoalStatus
import com.openminis.app.goal.GoalManager

/**
 * [T-plan-host-derive] Host-side board reconciliation from work evidence.
 *
 * ## The root cause this closes
 *
 * The board's DONE state was writable ONLY by the model (`agent_plan op=update
 * status=done`), and the Goal's close ONLY by `update_goal(op=complete)`.
 * Every prior fix — status aliases, the reference ladder, auto-activation,
 * nudges, the completion verifier — helps only when the model actually SENDS
 * that bookkeeping call. The dominant real failure mode is the model moving
 * on to the next step's WORK without sending it: the user sees the board
 * stuck a step behind reality, and the Goal open after the work is done.
 * Prompt-shaped fixes (nudges, wording) cannot reach it by construction.
 *
 * ## The two derivation rules (both deterministic, both host-side)
 *
 * 1. **Advance attribution** — the just-executed tool's `tool_title` is run
 *    through the SAME reference ladder the board tool uses
 *    ([AgentPlanStore.resolveIndex]: exact → number → title prefix →
 *    substring). A UNIQUE match on a plan item at a LATER position than the
 *    active one is the model visibly working that later step: the active step
 *    is marked done host-side, and [AgentPlanStore.updateWithAdvance] walks
 *    the activation cursor in the same save. When nothing is active, a
 *    unique match on a PENDING item activates it (a board seeded all-pending
 *    starts walking on first work evidence).
 * 2. **Goal close-out** — a non-empty board with every step done and none
 *    failed is exactly [com.openminis.app.goal.GoalCompletionVerifier]'s own
 *    completion criterion. When the board reaches it and the session Goal is
 *    still ACTIVE, the host completes the Goal through the verifier path —
 *    no `update_goal` dependency for the close-out.
 *
 * ## Guards
 *
 * - Only a SUCCESSFUL tool call is evidence (a failed one proves nothing).
 * - Bookkeeping tools ([AgentPlanTool.NAME], [GoalTool.NAME]) are exempt
 *   from rule 1: their own titles narrate the board, not the work, and the
 *   board write itself is the bookkeeping.
 * - Attribution is forward-only (later position), unique (ambiguous titles
 *   fall through the ladder as -1), and the action is user/model reversible
 *   (any later status update wins). The receipt names the host action so the
 *   transcript records that the host — not the model — moved the board.
 *
 * The nudge ([PlanBoardClosePrompt]) stays as the last-resort fallback for
 * runs no evidence can resolve; with these rules live it becomes rare.
 */
internal object PlanBoardAdvance {

    /**
     * Run after a successful tool execution. Returns the receipt suffix to
     * append to the tool result (host actions named for the transcript), or
     * null when nothing moved.
     */
    suspend fun reconcileAfterTool(
        sessionId: String,
        context: Context?,
        toolName: String,
        toolTitle: String,
        succeeded: Boolean,
        goals: GoalManager,
    ): String? {
        if (!succeeded) return null
        val plans = AgentPlanStore.list(sessionId, context)
        if (plans.isEmpty()) return null
        var receipt: String? = null

        // Rule 1: advance attribution from the tool's own narration. Skipped
        // for the bookkeeping tools — their titles narrate the BOARD, and the
        // board write itself is the bookkeeping this rule replaces.
        if (toolName != AgentPlanTool.NAME && toolName != GoalTool.NAME) {
            receipt = attributeAdvance(sessionId, context, plans, toolTitle)
        }

        // Rule 2: an all-done board closes an ACTIVE Goal through the
        // verifier path (an all-done board IS its criterion — Accepted).
        val plansNow = AgentPlanStore.list(sessionId, context)
        if (plansNow.isNotEmpty() && plansNow.all { it.status == "done" } && plansNow.none { it.status == "failed" }) {
            val goal = goals.get(sessionId)
            if (goal?.status == GoalStatus.ACTIVE) {
                val verified = goals.completeByModelVerified(sessionId, plansNow.map { it.status })
                if (verified is GoalManager.VerifiedCompletion.Accepted ||
                    verified is GoalManager.VerifiedCompletion.Forced
                ) {
                    receipt = (receipt?.plus("\n") ?: "") +
                        "Goal closed by the host: the plan board checked out fully done."
                }
            }
        }
        return receipt
    }

    /**
     * Rule 1. The matcher is the REVERSE of the board tool's ref ladder: a
     * step title quoted IN the tool narration (the narration is longer and
     * names the step it works on — "start implementing fix in auth module"
     * quotes "implement fix"). A narration naming SEVERAL steps attributes to
     * the FURTHEST one (work progresses forward). Requires an actionable
     * target: working a later step while an earlier one is active → the
     * active step is done; nothing active → a named pending step activates.
     * Returns the receipt line, or null.
     */
    private fun attributeAdvance(
        sessionId: String,
        context: Context?,
        plans: List<AgentPlanStore.Plan>,
        toolTitle: String,
    ): String? {
        if (toolTitle.isBlank()) return null
        val named = plans.filter {
            it.title.isNotBlank() && toolTitle.contains(it.title.trim(), ignoreCase = true)
        }
        // Furthest named step: a narration quoting two steps has reached the
        // later one. A short title quoted by chance loses to position order.
        val target = named.maxByOrNull { it.position } ?: return null
        val active = plans.firstOrNull { it.status == "active" }
        return when {
            // The narration names the active step itself: no movement.
            active != null && target.position <= active.position -> null
            // Working a later step while an earlier one is active: the model
            // moved on — mark the active step done host-side; the cursor
            // auto-activates the next pending item in the same save. A target
            // already marked done by the model advances the stale cursor too.
            active != null && target.status != "failed" -> {
                val advanced = AgentPlanStore.updateWithAdvance(sessionId, context, active.id, status = "done")
                    ?: return null
                "Plan board auto-advanced by the host: \"${advanced.first.title}\" marked done" +
                    (advanced.second?.let { " — next step \"${it.title}\" activated." } ?: ".")
            }
            // Nothing active and the narration names a pending step: the board
            // starts walking on first work evidence.
            active == null && target.status == "pending" -> {
                val activated = AgentPlanStore.update(sessionId, context, target.id, status = "active")
                    ?: return null
                "Plan board started by the host: \"${activated.title}\" activated on first work evidence."
            }
            else -> null
        }
    }
}
