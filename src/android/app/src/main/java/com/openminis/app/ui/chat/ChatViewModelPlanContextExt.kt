package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.goal.withGoalContext
import com.openminis.app.tools.AgentPlanStore

/**
 * [T-plan-board-live] Puts the plan board into every request.
 *
 * The board used to exist only inside `agent_plan` tool results: after the model
 * wrote a plan, the plan itself left the context within a turn or two (and could
 * be summarised away by compaction), leaving the model with nothing to check its
 * current step against. That is the mechanism behind "the model drifts and does
 * not know it".
 *
 * Rendering it from the store on every turn makes the board **structural**: it is
 * rebuilt from `agent_plan/plans.json` at request time, so no amount of history
 * trimming can remove it. It is not a reminder in the prompt; it is data the
 * request carries.
 *
 * Injected only when the board is non-empty — an empty board would otherwise add
 * a "nothing planned" paragraph to every turn of every conversation.
 */
internal fun ChatViewModel.planBoardPrompt(): String? {
    val board = AgentPlanStore.list(activeSessionId, context)
    if (board.isEmpty()) return null
    return AgentPlanStore.render(activeSessionId, context)
}

/**
 * [T-memory-recall-live] Recall recomputed per turn, not frozen per run.
 *
 * The query used to be "the last user message" evaluated once when the system
 * prompt was built. During a long tool loop that message does not change, so the
 * recall result stayed identical for up to `maxTurns` while the subtask moved on —
 * and a user turning the task over with "continue" produced an almost empty
 * query, i.e. no recall at all.
 *
 * The query now folds in where the turn actually is: the latest user text plus
 * the tail of the assistant's own reasoning. That is a cheap heuristic, not a
 * semantic drift detector — but it is recomputed from live state, which is the
 * property that was missing.
 */
internal fun ChatViewModel.liveRecallPrompt(): String? {
    if (!_memoryEnabled.value) return null
    val recentUser = _messages.value.lastOrNull { it.role == "user" && !it.isQueued }?.content.orEmpty()
    val recentAssistant = _messages.value
        .lastOrNull { it.role == "assistant" }
        ?.content
        ?.takeLast(ASSISTANT_TAIL_CHARS)
        .orEmpty()
    val query = (recentUser + "\n" + recentAssistant).trim()
    if (query.length < MIN_QUERY_CHARS) return null
    val engine = com.openminis.app.data.repository.MemoryRecallEngineCache.forSession(
        memoryDir = com.openminis.app.sandbox.SessionWorkspace.memoryDir(
            context.filesDir,
            com.openminis.app.sandbox.ExecutionCoordinator.ownerSessionId(
                realSessionId.ifEmpty { sessionId },
            ),
        ),
        // [T-memory-shared-recall] The app-wide directory joins recall, so a
        // lesson recorded in another chat is reachable from this one.
        sharedDir = com.openminis.app.data.repository.MemoryIndex.sharedDir(context.filesDir),
    ) ?: return null
    val hits = engine.recall(query)
    if (hits.isEmpty()) return null
    return engine.formatAsPromptFragment(hits).takeIf { it.isNotBlank() }
}

private const val ASSISTANT_TAIL_CHARS = 400

private const val MIN_QUERY_CHARS = 4

/**
 * [T-plan-board-live] Goal context plus the live plan board, in one pass.
 *
 * Both blocks ride on the last user message (see [withGoalContext]), and both are
 * stripped before re-adding — otherwise every turn would stack another copy onto
 * the history it already sits in.
 */
internal fun ChatViewModel.withGoalAndPlanContext(
    history: List<LLMMessage>,
    prompt: String?,
    appendNewUser: Boolean = false,
): List<LLMMessage> {
    // [T-prompt-parts] One registry decides what is injected, at which level and
    // in which order; this function only appends the caller's own text last.
    val parts = PromptParts.render(PromptParts.validate(PromptParts.of(this)))
    val blocks = parts + listOfNotNull(prompt)
    val combined = blocks.joinToString("\n\n").takeIf { it.isNotBlank() }
    return withGoalContext(history, combined, appendNewUser)
}

/**
 * [T-work-report] Current stage and last report, rebuilt from the store.
 *
 * Null before the first report, so a chat that has not used the tool pays nothing
 * for it.
 */
internal fun ChatViewModel.workReportPrompt(): String? =
    com.openminis.app.tools.AgentReportStore.stagePrompt(activeSessionId, context)

/**
 * [T-lane-awareness] Lanes that are still working, rendered from the registry.
 *
 * Structural for the same reason as the board and the stage: a dispatch that
 * only exists in an old tool result is gone after compaction, and the model then
 * cannot tell work already handed out from work nobody picked up.
 */
internal fun ChatViewModel.inFlightLanesPrompt(): String? =
    com.openminis.app.tools.SubAgentBatchRegistry.renderInFlight(activeSessionId, context)
