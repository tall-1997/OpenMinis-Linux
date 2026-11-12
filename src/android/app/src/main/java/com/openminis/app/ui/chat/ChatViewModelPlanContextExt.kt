package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.goal.withGoalContext
import com.openminis.app.tools.AgentPlanStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
 * The query is the latest real user text only (ZCode
 * findLatestMemoryRecallQuery). The assistant tail is not part of it: the
 * model's own restatement of an old task was keeping that task's memory
 * selected.
 */
/** [T-recall-once] ZCode's rule as data: a delivered line never comes back. */
internal fun <T> recallFreshHits(
    hits: List<T>,
    announced: Set<String>,
    keyOf: (T) -> String,
): List<T> = hits.filter { keyOf(it) !in announced }

/** [T-recall-once] One-shot recall fragment; committed once, never rebuilt. */
internal fun ChatViewModel.newRecallReminder(): String? {
    if (!_memoryEnabled.value) return null
    val input = liveRecallInput() ?: return null
    val engine = recallEngineForLiveRecall() ?: return null
    val hits = recallHitsNotAlreadyResident(
        engine.recallForPromptCached(input.query),
        residentDailyFiles,
    ) { it.source }
    if (hits.isEmpty()) return null
    val fresh = recallFreshHits(hits, announcedRecallKeys) { "${it.day}:${it.line}" }
    if (fresh.isEmpty()) return null
    announcedRecallKeys.addAll(hits.map { "${it.day}:${it.line}" })
    return engine.formatAsPromptFragment(fresh).takeIf { it.isNotBlank() }
}

/**
 * [T-memory-embedding-wiring] The live-recall query plus its turn key, read in
 * ONE snapshot. liveRecallPrompt and the send-path semantic prefetch
 * ([prefetchSemanticRecall]) must build the SAME string, or the prefetch's
 * cache key never matches the render's lookup and the hybrid result is wasted.
 * Both callers evaluate this against the live message window: the prefetch
 * calls it right after the new user row is appended and before any assistant
 * message exists for the turn — exactly the state the first request's render
 * sees — so a fresh send's first turn matches byte-for-byte. Later turns of a
 * tool loop grow the assistant tail and miss the one-slot cache by design;
 * they degrade to the keyword path.
 *
 * [lastUserId] is the id of the user row the query was built from — the
 * turn-dedup carrier key, read from the same snapshot so the announce decision
 * and the query can never disagree about which turn they describe.
 *
 * Null when the fold is too short to be worth a recall ([MIN_QUERY_CHARS]).
 */
internal class LiveRecallInput(
    val query: String,
    val lastUserId: String,
)

internal fun ChatViewModel.liveRecallInput(): LiveRecallInput? {
    // [T-memory-query-real-user] ZCode findLatestMemoryRecallQuery: the query
    // is the latest real user text only. Folding in the assistant tail made
    // the model's own restatement of an old task keep hitting that task's
    // daily log, so the log was injected again and the run resumed it.
    val lastUser = _messages.value.lastOrNull { message ->
        message.role == "user" && !message.isQueued && memoryRecallQuery(message.content) != null
    } ?: return null
    val query = memoryRecallQuery(lastUser.content) ?: return null
    return LiveRecallInput(query, lastUser.id)
}

/**
 * Real-user text eligible as a memory query. Host reminders are not a query,
 * and a blank or tool-result carrier is not one either.
 */
/**
 * Drop recall hits whose file is already in the system prompt verbatim.
 * ZCode does not re-select a memory that is already in context.
 */
internal fun <T> recallHitsNotAlreadyResident(
    hits: List<T>,
    residentFiles: Set<String>,
    sourceOf: (T) -> String,
): List<T> = hits.filter { sourceOf(it) !in residentFiles }

internal fun memoryRecallQuery(userText: String): String? {
    val stripped = userText
        .replace(Regex("<system-reminder>[\\s\\S]*?</system-reminder>"), "")
        .trim()
    if (stripped.length < MIN_QUERY_CHARS) return null
    return stripped
}

/**
 * [T-memory-embedding-wiring] The engine behind live recall, in ONE place.
 * Same construction for render and prefetch ⇒ same [MemoryRecallEngineCache]
 * key ⇒ the same instance holds the prefetch cache the render will consume.
 */
internal fun ChatViewModel.recallEngineForLiveRecall(): com.openminis.app.data.repository.MemoryRecallEngine? {
    return com.openminis.app.data.repository.MemoryRecallEngineCache.forSession(
        memoryDir = com.openminis.app.sandbox.SessionWorkspace.memoryDir(
            context.filesDir,
            com.openminis.app.sandbox.ExecutionCoordinator.ownerSessionId(
                realSessionId.ifEmpty { sessionId },
            ),
        ),
        // [T-memory-shared-recall] The app-wide directory joins recall, so a
        // lesson recorded in another chat is reachable from this one.
        sharedDir = com.openminis.app.data.repository.MemoryIndex.sharedDir(context.filesDir),
    )
}

/**
 * [T-memory-embedding-wiring] Fire-and-forget semantic-recall prefetch, called
 * early in the send pipeline (right after the user row lands, before prompt
 * assembly) so the embeddings request overlaps the DB/OAuth/prompt work and
 * the first turn's render finds the hybrid result already cached.
 *
 * Zero-cost when the semantic feature is off (default): one volatile read
 * returns before any engine lookup or file I/O. Otherwise the engine's own
 * [MemoryRecallEngine.prefetchSemantic] launches onto its IO scope — this
 * coroutine only keeps the engine's first-create sidecar read off the main
 * thread — and every failure stays silent: the send pipeline must not notice
 * the prefetch exists.
 */
internal fun ChatViewModel.prefetchSemanticRecall() {
    if (!_memoryEnabled.value) return
    if (com.openminis.app.data.memory.MemoryEmbeddingRuntime.recallSupportOrNull() == null) return
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val input = liveRecallInput() ?: return@runCatching
            val engine = recallEngineForLiveRecall() ?: return@runCatching
            engine.prefetchSemantic(input.query)
        }
    }
}

/**
 * [T-recall-turn-dedup] Pure decision: true = announce the fragment now.
 * Same-turn requests always announce (each request is a fresh context);
 * a new turn re-announces only when the recalled lines differ from the
 * previous turn's. Compaction resets the state so memories can return.
 *
 * [T-recall-carrier-replay-once] A carrier that fell out of the window (the
 * compaction path evicted it) re-surfaces the block EXACTLY once
 * ([carrierReplayAlreadyDone] latches). Without the latch the condition stays
 * true on every following turn of a compacted session, so the same memories
 * ride along on every request — the model reads the same past-task notes over
 * and over and the context keeps growing.
 */
internal fun shouldAnnounceRecall(
    turnKey: String,
    lastTurnKey: String?,
    keys: Set<String>,
    lastKeys: Set<String>?,
    lastCarrierAlive: Boolean,
    carrierReplayAlreadyDone: Boolean = false,
): Boolean {
    if (turnKey == lastTurnKey) return true
    if (lastKeys == null) return true
    if (keys != lastKeys) return true
    return !lastCarrierAlive && !carrierReplayAlreadyDone
}

private const val ASSISTANT_TAIL_CHARS = 400

private const val MIN_QUERY_CHARS = 4

/**
 * [T-plan-board-live] Goal context plus the live plan board, in one pass.
 *
 * LEGACY ([T-process-tail-injection]): the agent loop no longer calls this.
 * It used to ride every block on the LAST user message, which inside the loop
 * is the tool_result carrier — process narration mixed into tool output, and
 * every turn re-injected it. Process blocks now go through
 * [processInjectedSystemPrompt] instead. Kept for callers/tests that still
 * exercise the history-graft shape; new code must not use it.
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
 * [T-process-tail-injection] The per-request process blocks (stage, lanes,
 * jobs, plan board, recall, plus the routine Goal context) rendered as ONE
 * string for the SYSTEM PROMPT's dynamic tail.
 *
 * Why the system prompt and not the history: inside the agent loop the last
 * user message is the tool_result carrier, so the old `withGoalAndPlanContext`
 * graft made the model read process narration as part of a tool output — and
 * every turn re-injected the same blocks onto a fresh carrier. Sending them
 * once per request in the prompt tail keeps the history byte-clean (no
 * process text mixed into tool results) and keeps them visible to the model on
 * every request, which is what these blocks actually need.
 *
 * Cache contract: the blocks go AFTER `systemPromptStablePrefixLen`, so the
 * stable head is untouched and provider prefix caching still hits.
 */
internal fun ChatViewModel.processInjectionTail(goalPrompt: String? = null): String {
    val parts = PromptParts.render(PromptParts.validate(PromptParts.of(this)))
    val blocks = parts + listOfNotNull(goalPrompt?.takeIf { it.isNotBlank() })
    if (blocks.isEmpty()) return ""
    val raw = blocks.joinToString("\n\n").trim()
    // [T-process-tail-content-dedupe] Content-level dedupe: when nothing the
    // host injects has changed, reuse the previous string so the tail bytes are
    // identical across requests (stable cache suffix, zero re-render churn).
    val out = reuseProcessTail(raw, processTailCache)
    processTailCache = out
    return out
}

/**
 * [T-process-tail-content-dedupe] Pure core of the tail dedupe: identical
 * bytes reuse the previous instance instead of rebuilding a new string.
 */
internal fun reuseProcessTail(raw: String, cached: String?): String =
    if (cached != null && cached == raw) cached else raw

/**
 * [T-process-tail-injection] The outgoing system prompt for one request:
 * the run-level prompt (date refreshed) with the process tail appended.
 *
 * The tail rides after the stable prefix, so `systemPromptStablePrefixLen`
 * keeps pointing at the same byte offset and the provider's prefix cache is
 * unaffected. `goalPrompt` is passed only on the requests that do NOT
 * first-mount it as a user turn (the first turn of a goal run), so the goal
 * context never appears twice in one request.
 */
internal fun ChatViewModel.processInjectedSystemPrompt(
    systemPrompt: String?,
    goalPrompt: String? = null,
): String? {
    val tail = processInjectionTail(goalPrompt)
    if (tail.isBlank()) return systemPrompt
    return if (systemPrompt.isNullOrBlank()) tail else systemPrompt + "\n\n" + tail
}

/**
 * [T-process-tail-injection] True when the host has anything to inject this
 * request. Kept for callers that only need the decision (tests, diagnostics).
 */
internal fun ChatViewModel.hasProcessInjection(): Boolean = processInjectionTail().isNotBlank()

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