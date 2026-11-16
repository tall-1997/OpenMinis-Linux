package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.ActiveRun
import com.openminis.app.tools.AgentPlanStore

/** Outcome of one plan close-out check at natural turn end. */
enum class PlanBoardCloseResult {
    /** A nudge was persisted and history appended — continue the loop. */
    NUDGED,

    /** The run stopped mid-nudge; exit the loop normally. */
    STOPPED,

    /** Nothing to nudge — fall through to the next tail check. */
    NONE,
}

/**
 * [T-plan-close-nudge] Host-side close-out check for the plan board, pinned
 * to the agent-loop tail entry — the one place every natural turn end passes
 * through. Mirrors the shape of the sub-agent collect nudge, but for the
 * board: a model that answers in its final turn without reconciling the
 * board leaves done work looking unfinished (steps unchecked) and the Goal
 * permanently ACTIVE, because [GoalCompletionVerifier] only ever *rejects*
 * premature completes — it cannot invent a completion the model never calls
 * for. This nudge gives the model one explicit, bounded push to reconcile
 * before the turn ends, exactly like the sub-agent collect nudge gives it a
 * bounded push to collect finished lanes.
 *
 * Pure-function core ([PlanBoardClosePrompt]) is JVM-testable without a
 * Context; the loop-tail plumbing in this file loads the board and the Goal
 * snapshot itself (GoalManager built from chatRepository, matching
 * ChatViewModelGoalExt's pattern) and persists the nudge turn.
 */
object PlanBoardClosePrompt {

    /**
     * The nudge the loop appends before the final turn when the board is not
     * closed out. Plain-text (no localization): it instructs the agent, not
     * the user, so it must match the at-a-glance row's vocabulary.
     */
    fun nudge(
        plans: List<AgentPlanStore.Plan>,
        goalActive: Boolean,
        nudgesUsed: Int,
        maxNudges: Int,
    ): String? {
        if (plans.isEmpty()) return null
        if (nudgesUsed >= maxNudges) return null
        val total = plans.size
        val done = plans.count { it.status == "done" }
        val failed = plans.count { it.status == "failed" }

        // Fully done board: the only thing left is closing the Goal — but only
        // when the Goal is still active (complete/clear already handled).
        if (done == total && failed == 0) {
            if (!goalActive) return null
            return buildString {
                append("<system-reminder>\n")
                append("Plan board is fully done ($done/$total) and your turn is ending without closing the Goal. ")
                append("Call update_goal(op=complete) to mark the objective met; if the objective is ")
                append("obsolete, say so and use update_goal(op=clear) instead.\n")
                append("</system-reminder>")
            }
        }

        // Board has unfinished steps: reconcile before the final answer. The
        // board outlives its Goal (the user cleared it, or none was ever
        // created), so the closing instruction is gated on goalActive — the
        // update_goal call would fail anyway (modelUpdate requires an ACTIVE
        // Goal; clear reports "none exists") and only burn a tool call.
        val openItems = plans.filter { it.status != "done" }.joinToString(", ") { it.title }
        return buildString {
            append("<system-reminder>\n")
            append("Plan board is $done/$total done as your turn ends. Reconcile it before the final answer:\n")
            append("- agent_plan op=update status=done for every step whose work has landed, including this turn's work.\n")
            if (failed > 0) {
                append("- A failed step holds the Goal open: re-run it if the work is unfinished, ")
                append("or mark done only if the work actually landed.\n")
            }
            append("Open items: $openItems.\n")
            if (goalActive) {
                append("- Once every open step above reads done, call update_goal(op=complete) so the Goal closes; ")
                append("if the objective changed shape, say so and use update_goal(op=clear).\n")
            } else {
                append("- No active Goal — just reconcile the board above.\n")
            }
            append("A board left with pending steps while the work is done reads as unfinished to the user.\n")
            append("</system-reminder>")
        }
    }

    /** True when the model may still be holding the turn open for a nudge. */
    fun goalActive(status: String?): Boolean = status == "active"

    /**
     * Failure-bounded board snapshot for the loop tail — a store hiccup must
     * never fail the turn itself.
     */
    fun snapshot(
        sessionId: String,
        context: Context?,
    ): List<AgentPlanStore.Plan> =
        runCatching { AgentPlanStore.list(sessionId, context) }.getOrElse { emptyList() }

    /** Keep the loop's counter bounded by the same knob tests pin. */
    internal const val DEFAULT_MAX_NUDGES = 2
}

/**
 * Loop-tail plumbing for the plan close-out nudge: reads the board and the
 * Goal, decides, and — when a nudge is owed — persists the assistant turn and
 * appends the nudge as a user message exactly like the sub-agent collect
 * nudge does, so history never carries two consecutive user messages. The
 * caller advances its counter and continues/breaks on the returned outcome.
 * Receiver is [ChatViewModel] so repository-backed state (persistence, bounded
 * history, the Goal via GoalManager(chatRepository)) stays reachable without a
 * parameter parade.
 */
internal suspend fun ChatViewModel.nudgePlanBoardCloseFor(
    isFinalTurn: Boolean,
    goalSessionId: String,
    nudgesUsed: Int,
    allToolBlocks: List<AssistantBlock>,
    turnStartBlockIndex: Int,
    allToolInputs: Map<String, String>,
    lastUsage: LLMUsage?,
    turnReasoningContent: String?,
    turnThinkingLevel: ThinkingLevel,
    accumulatedText: String,
    activeRun: ActiveRun?,
): PlanBoardCloseResult {
    if (!isFinalTurn || nudgesUsed >= PlanBoardClosePrompt.DEFAULT_MAX_NUDGES || activeRun?.isStopped == true) {
        return PlanBoardCloseResult.NONE
    }
    val boardPlans = PlanBoardClosePrompt.snapshot(goalSessionId, context)
    val goalForClose = runCatching {
        // goalManager is a loop-local, not a ChatViewModel member — build the
        // manager from the repository here, matching ChatViewModelGoalExt.
        com.openminis.app.goal.GoalManager(chatRepository).get(goalSessionId)
    }.getOrNull()
    val planNudge = PlanBoardClosePrompt.nudge(
        plans = boardPlans,
        goalActive = goalForClose?.let { PlanBoardClosePrompt.goalActive(it.status) } ?: false,
        nudgesUsed = nudgesUsed,
        maxNudges = PlanBoardClosePrompt.DEFAULT_MAX_NUDGES,
    ) ?: return PlanBoardCloseResult.NONE

    val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, allToolInputs)
    val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
    persistAssistantTurnForRun(
        checkNotNull(activeRun) { "Agent loop has no persistence owner" }, turnParts, lastUsage, turnReasoningContent, blockMeta, accumulatedText,
        requestedLevel = turnThinkingLevel,
    )
    if (activeRun?.isStopped == true) return PlanBoardCloseResult.STOPPED
    appendBoundedHistory(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = planNudge,
            contentParts = listOf(AgentContentPart.Text(planNudge)),
        ),
    )
    val openCount = boardPlans.count { it.status != "done" }
    AppLogger.info(
        ChatViewModel.TAG_STREAM,
        "plan close-out nudge ${nudgesUsed + 1}/${PlanBoardClosePrompt.DEFAULT_MAX_NUDGES} " +
            "(session=$goalSessionId, open=$openCount)",
    )
    return PlanBoardCloseResult.NUDGED
}
