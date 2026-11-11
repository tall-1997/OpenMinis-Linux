package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.AgentPlanStore
import kotlinx.coroutines.flow.StateFlow

/**
 * [T-plan-step-execution] Plan-step execution on the ChatViewModel side.
 *
 * The board data (AgentPlanStore) and its auto-advance semantics live in the
 * tools layer; this ext carries the one piece that needs a ViewModel: the
 * user-facing retry of a FAILED step. The state itself ([ChatViewModel._planStepRetry])
 * is a StateFlow on the VM — extensions cannot add fields.
 *
 * Flow: the board row's retry affordance calls [retryPlanStep], which resets
 * the failed step to active and arms a one-shot steering request. Whether the
 * model actually sees it depends on the run state:
 *
 * - **Streaming** — the next in-run request picks the steering up at the
 *   request-assembly boundary ([consumePlanStepRetryPrompt] is called from
 *   `planBoardPrompt`, which the agent loop re-renders every round).
 * - **Idle** — the loop is kicked with the same internal-run send the Goal
 *   sheet uses (`sendMessage("", skipContextCheck = true, internalGoalRun =
 *   true)`): no user bubble, the plan-board block IS the instruction.
 *
 * The steering rides INSIDE the `<plan_board>` element, so the per-turn strip
 * in withGoalContext (which removes the whole block) also removes the steering
 * — a standalone line would stack copies turn after turn.
 */

/** One user-requested retry of one failed board step; consumed once. */
data class PlanStepRetryRequest(
    val planId: String,
    val title: String,
    /** 0-based board position; the prompt prints it 1-based like the snapshot. */
    val position: Int,
    val stamp: Long = System.currentTimeMillis(),
) {
    /** Line injected into the request when the retry fires (model-directed). */
    internal fun promptLine(): String =
        "RETRY REQUESTED by the user: plan step ${position + 1} \"$title\" failed earlier and was " +
            "reset to active. Work on exactly this step now; flip it to done when it lands, or to " +
            "failed with a short reason if it cannot be completed."
}

/** Read-only view of the pending retry request (UI/testing introspection). */
val ChatViewModel.planStepRetryRequest: StateFlow<PlanStepRetryRequest?>
    get() = planStepRetry

/**
 * Retry a FAILED plan step. No-op unless [planId] names a board item that is
 * currently in the failed state — retry is a deliberate second attempt at
 * something that errored, not a way to re-open landed or pending work.
 *
 * The store flip is synchronous, so the row the user tapped shows the active
 * spinner on the very next frame (AgentPlanStore.version drives the reload);
 * the model-side steering is armed before the kick so the kicked run's first
 * request already carries it.
 */
fun ChatViewModel.retryPlanStep(planId: String) {
    val sid = activeSessionId
    val plan = runCatching { AgentPlanStore.list(sid, context) }.getOrDefault(emptyList())
        .firstOrNull { it.id == planId } ?: return
    if (plan.status != "failed") return
    val updated = runCatching { AgentPlanStore.retryStep(sid, context, planId) }
        .onFailure { AppLogger.warning(ChatViewModel.TAG_STREAM, "retryPlanStep: store flip failed: ${it.message}") }
        .getOrNull() ?: return
    // Arm the one-shot BEFORE the kick so the kicked run's first request
    // assembly (which re-renders the plan-board part) carries the steering.
    _planStepRetry.value = PlanStepRetryRequest(
        planId = updated.id,
        title = updated.title,
        position = updated.position,
    )
    AppLogger.info(
        ChatViewModel.TAG_STREAM,
        "retryPlanStep: armed retry for \"${updated.title}\" (sid=$sid, streaming=${_isStreaming.value})",
    )
    if (!_isStreaming.value) {
        // Idle: no run to piggyback on — kick one with the Goal-sheet trigger
        // (empty internal run; the plan-board block is the instruction).
        sendMessage("", skipContextCheck = true, internalGoalRun = true)
    }
}

/**
 * One-shot steering for the armed retry; consumed on the first render that
 * can deliver it (the plan-board block exists — a failed step always leaves
 * open work, so the snapshot is present exactly when the retry is deliverable).
 * A request that fails after consumption loses the steering: the step is
 * already back to active, so the board still says what to work on.
 */
internal fun ChatViewModel.consumePlanStepRetryPrompt(): String? {
    val request = _planStepRetry.value ?: return null
    _planStepRetry.value = null
    return request.promptLine()
}
