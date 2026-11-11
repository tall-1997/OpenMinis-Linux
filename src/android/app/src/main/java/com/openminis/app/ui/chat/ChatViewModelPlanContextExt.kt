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
 * Injected only when the board has open work — an empty board would otherwise add
 * a "nothing planned" paragraph to every turn of every conversation, and a
 * fully-done board would re-announce landed work while the card retires.
 */
internal fun ChatViewModel.planBoardPrompt(): String? = runCatching {
    // [T-plan-snapshot-diet] Slim fuel: the snapshot strips uuids, drops
    // landed descriptions and goes silent on empty/all-done boards, so the
    // per-turn rider costs O(open work) instead of O(board). render() stays
    // the on-demand full view behind op=list. list() throws on a corrupt
    // board (by design, so the tool surfaces it); the prompt path degrades
    // to no block instead of bricking the request.
    val snapshot = AgentPlanStore.boardSnapshotPrompt(AgentPlanStore.list(activeSessionId, context))
        ?: return@runCatching null
    // [T-plan-step-retry] A user-requested step retry rides INSIDE the
    // <plan_board> element, one-shot (consumed here). Inside the block — not
    // as a standalone line — because withGoalContext strips the whole
    // <plan_board> element before re-adding it each turn; a bare line would
    // stack a copy on every request. A failed step always leaves open work,
    // so the snapshot is non-null whenever a retry can be pending: when the
    // board vanished (cleared concurrently) the request stays armed for the
    // next board instead of being dropped.
    consumePlanStepRetryPrompt()?.let { retryLine ->
        return@runCatching snapshot.replace("</plan_board>", "$retryLine\n</plan_board>")
    }
    snapshot
}.getOrNull()

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
    val lastUser = _messages.value.lastOrNull { it.role == "user" && !it.isQueued }
    val recentUser = lastUser?.content.orEmpty()
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
    // [T-memory-recall-anti-nag] recallForPrompt, not recall: the query-keyed
    // memo cannot dedupe across turns (the query changes every turn with the
    // assistant tail), so the same few memories were re-announced every turn.
    // The hit-keyed streak inside the engine stops a memory after three
    // consecutive announcements and re-arms it when it drops out of the hits.
    val hits = engine.recallForPrompt(query)
    if (hits.isEmpty()) return null
    // [T-recall-turn-dedup] The same memories are announced once per topic.
    // Every request of one turn must carry the block (each request is a fresh
    // context), but a NEW turn re-announces only when the recalled lines
    // changed — otherwise the fragment would repeat verbatim every round.
    // The engine's hit-keyed cap (recallForPrompt) composes on top of this:
    // when the recalled set drifts every round, each memory still stops being
    // re-injected after RECALL_REPEAT_CAP consecutive rounds and re-arms when
    // it drops out of the hits (task pivot).
    val turnKey = lastUser?.id ?: ""
    val keys = hits.map { "${it.day}:${it.line}" }.toSet()
    // If the message that carried the previous block fell out of the window,
    // the memories are gone from context — announce again regardless.
    val carrierAlive = lastRecallTurnKey == null ||
        _messages.value.any { it.id == lastRecallTurnKey }
    if (!shouldAnnounceRecall(turnKey, lastRecallTurnKey, keys, lastRecallTurnKeys, carrierAlive)) return null
    lastRecallTurnKey = turnKey
    lastRecallTurnKeys = keys
    // [T-memory-recall-count] formatAsPromptFragment already accepts the
    // entries it actually rendered (post fragment budget); accepting the FULL
    // hit list on top used to count every injection twice, with the extra
    // keys never shown anywhere.
    return engine.formatAsPromptFragment(hits).takeIf { it.isNotBlank() }
}

/**
 * [T-recall-turn-dedup] Pure decision: true = announce the fragment now.
 * Same-turn requests always announce (each request is a fresh context);
 * a new turn re-announces only when the recalled lines differ from the
 * previous turn's. Compaction resets the state so memories can return.
 */
internal fun shouldAnnounceRecall(
    turnKey: String,
    lastTurnKey: String?,
    keys: Set<String>,
    lastKeys: Set<String>?,
    lastCarrierAlive: Boolean,
): Boolean {
    if (turnKey == lastTurnKey) return true
    if (lastKeys == null) return true
    return keys != lastKeys || !lastCarrierAlive
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
internal fun ChatViewModel.workReportPrompt(): String? = runCatching {
    // [review 2.3 corrupt-report] stagePrompt re-loads report.json and THROWS
    // on a corrupt file ("... delete it to reset") — unwrapped, that IOException
    // would brick EVERY request assembly for the session with no self-heal.
    // Same policy as [planBoardPrompt] above: the prompt path degrades to no
    // block instead of failing the turn; the tool surfaces the corruption.
    com.openminis.app.tools.AgentReportStore.stagePrompt(activeSessionId, context)
}.getOrNull()

/**
 * [T-lane-awareness] Lanes that are still working, rendered from the registry.
 *
 * Structural for the same reason as the board and the stage: a dispatch that
 * only exists in an old tool result is gone after compaction, and the model then
 * cannot tell work already handed out from work nobody picked up.
 */
internal fun ChatViewModel.inFlightLanesPrompt(): String? =
    com.openminis.app.tools.SubAgentBatchRegistry.renderInFlight(activeSessionId, context)

/**
 * [T-job-awareness] Background jobs for this chat, from the registry.
 *
 * Null when nothing was ever started, so a chat that never ran a job pays
 * nothing for this block.
 */
internal fun ChatViewModel.backgroundJobsPrompt(): String? {
    val jobs = runCatching {
        com.openminis.app.sandbox.jobs.JobRegistry.list(activeSessionId)
    }.getOrDefault(emptyList())
    if (jobs.isEmpty()) return null
    return BackgroundJobsPrompt.render(
        jobs.map { job ->
            BackgroundJobsPrompt.Row(
                id = job.id,
                state = job.state.name,
                exitCode = job.exitCode,
                command = job.command,
            )
        },
    )
}