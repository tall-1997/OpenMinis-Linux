package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.AgentPlanStore
import com.openminis.app.tools.ExitPlanModeTool
import com.openminis.app.tools.PlanModeStore
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolErrorCode
import kotlinx.coroutines.CompletableDeferred

/**
 * Suspend half of the plan-mode exit gate: [executeExitPlanMode] parks the
 * agent loop on a [CompletableDeferred] until the user answers the review
 * card, and [resolvePlanReview] is the single funnel every decision goes
 * through — the card, and any future notification/voice surface, all land
 * here so the side effects (mode flip, board seeding) cannot diverge.
 *
 * Split from [ExitPlanModeTool] (pure parsing/formatting) so the pure half
 * unit-tests without a ViewModel; split from ChatViewModel.kt because that
 * file is at its architecture-policy ceiling.
 */
private const val TAG_PLAN = "PlanReview"

internal suspend fun ChatViewModel.executeExitPlanMode(argsJson: String): ToolExecutionResult {
    val (submission, parseError) = ExitPlanModeTool.parse(argsJson)
    if (submission == null) {
        return ToolExecutionResult(
            "exit_plan_mode failed: $parseError",
            false,
            errorCode = ToolErrorCode.INVALID_ARGUMENTS,
            recoveryHint = "Fix the arguments and call exit_plan_mode again.",
            toolTitle = ExitPlanModeTool.NAME,
        )
    }
    val sid = activeSessionId
    val deferred = CompletableDeferred<ExitPlanModeTool.Decision>()
    PlanReviewGate.register(sid, submission, deferred)
    AppLogger.info(TAG_PLAN, "plan review pending (sid=$sid, items=${submission.items.size})")
    try {
        val decision = deferred.await()
        return ToolExecutionResult(
            ExitPlanModeTool.formatDecision(decision),
            true,
            toolTitle = ExitPlanModeTool.NAME,
        )
    } finally {
        PlanReviewGate.unregister(sid)
    }
}

/**
 * Resolves the pending review for this session. Side effects on approval /
 * rejection: plan mode flips OFF, and approval replaces the board with the
 * approved items (clear first — a stale board from an earlier plan must not
 * survive a fresh approval). Revise keeps the mode ON.
 */
internal fun ChatViewModel.resolvePlanReview(decision: ExitPlanModeTool.Decision) {
    val sid = activeSessionId
    if (!PlanReviewGate.resolve(sid, decision)) return
    when (decision) {
        is ExitPlanModeTool.Decision.Approved -> {
            PlanModeStore.setEnabled(sid, context, false)
            AgentPlanStore.clear(sid, context)
            decision.items.forEach { item ->
                AgentPlanStore.add(sid, context, item.title, item.description, "pending")
            }
            AppLogger.info(TAG_PLAN, "plan approved (sid=$sid, items=${decision.items.size})")
        }
        is ExitPlanModeTool.Decision.Revise ->
            AppLogger.info(TAG_PLAN, "plan revision requested (sid=$sid)")
        is ExitPlanModeTool.Decision.Rejected -> {
            PlanModeStore.setEnabled(sid, context, false)
            AppLogger.info(TAG_PLAN, "plan rejected (sid=$sid)")
        }
    }
}
