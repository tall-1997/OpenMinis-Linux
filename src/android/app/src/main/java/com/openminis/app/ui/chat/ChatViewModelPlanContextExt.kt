package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

/** Request-local recall; merely selecting a hit must neither persist nor suppress it. */
internal fun ChatViewModel.newRecallReminder(
    daily: DailyMemoryDelivery?,
    query: String? = liveRecallInput()?.query,
    turnId: String? = null,
): RecallReminder? {
    if (!_memoryEnabled.value || query == null) return null
    val session = activeSessionId
    val now = daily?.projectedAt ?: System.currentTimeMillis()
    val engine = recallEngineForLiveRecall() ?: return null
    val residentRefs = daily?.entryRefs.orEmpty()
    val hits = engine.recallCandidatesForDelivery(query, now, turnId).filterNot { hit ->
        val ref = hit.ref
        if (ref != null) ref in residentRefs else hit.source in daily?.residentSources.orEmpty()
    }
    val announced = if (noticesSessionId == session) announcedRecallKeys else emptySet()
    val rendered = engine.formatAsPromptFragmentWithHits(recallFreshHits(hits, announced) { it.announcementKey }, now)
    if (rendered.text.isBlank()) return null
    return RecallReminder("<system-reminder>\n${rendered.text}\n</system-reminder>", rendered.included, session, now)
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
    //
    // [T-recall-thin-followup] A bare follow-up ("继续" / "continue" / "ok")
    // names no topic. Treating it as the query selected nothing (too short)
    // or, worse, matched every daily log. Walk back to the newest user turn
    // that actually names a task; its memories are what "continue" refers to.
    val lastUser = _messages.value.lastOrNull { message ->
        message.role == "user" &&
            !message.isQueued &&
            TaskAnchor.isSubstantive(message.content) &&
            memoryRecallQuery(message.content) != null
    } ?: return null
    val query = memoryRecallQuery(lastUser.content) ?: return null
    return LiveRecallInput(query, lastUser.id)
}

/** Newest substantive user text from the same pre-projection history (including a composer draft). */
internal fun memoryRecallQueryForHistory(history: List<LLMMessage>): String? = history.asReversed().firstNotNullOfOrNull { message ->
    if (!isMemoryUserInput(message)) return@firstNotNullOfOrNull null
    val query = memoryRecallQuery(message.content) ?: return@firstNotNullOfOrNull null
    query.takeIf(TaskAnchor::isSubstantive)
}

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
    // [T-recall-cjk-query] Chinese has no spaces: a complete instruction can
    // be two characters ("部署", "回滚"). The old flat 4-char floor rejected
    // those as "no query". Latin still needs 4 characters to mean anything.
    val floor = if (stripped.any { it.code in 0x4E00..0x9FFF }) MIN_CJK_QUERY_CHARS else MIN_QUERY_CHARS
    if (stripped.length < floor) return null
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
    if (!_memoryEnabled.value || com.openminis.app.data.memory.MemoryEmbeddingRuntime.indexerOrNull() == null) return
    prefetchSemanticRecallForRequest(agentHistorySnapshot())
}

internal fun memoryRecallTurnIdForHistory(history: List<LLMMessage>): String? =
    history.lastOrNull(::isMemoryUserInput)?.dbMessageId?.takeIf { it.isNotBlank() }

private fun isMemoryUserInput(message: LLMMessage): Boolean =
    message.role == LLMMessage.Role.USER && !message.isHostGenerated && !HarnessNoteMarkers.isUserHarnessNote(message) &&
        message.contentParts.none { it is AgentContentPart.ToolUse || it is AgentContentPart.ToolResult } &&
        (message.content.isNotBlank() || message.contentParts.any { it !is AgentContentPart.Text || it.text.isNotBlank() } ||
            message.imageParts.isNotEmpty() || message.audioParts.isNotEmpty())

/** Request-side only; estimates can read the completed cache but never launch prefetch. */
internal fun ChatViewModel.prefetchSemanticRecallForRequest(history: List<LLMMessage>) {
    if (!_memoryEnabled.value || com.openminis.app.data.memory.MemoryEmbeddingRuntime.indexerOrNull() == null) return
    val turnId = memoryRecallTurnIdForHistory(history) ?: return
    val query = memoryRecallQueryForHistory(history) ?: return
    val session = activeSessionId
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            if (session == activeSessionId && _memoryEnabled.value) recallEngineForLiveRecall()?.prefetchSemantic(query, turnId)
        }
    }
}


private const val ASSISTANT_TAIL_CHARS = 400

private const val MIN_QUERY_CHARS = 4

/** [T-recall-cjk-query] A 2-character CJK verb can be a complete request. */
private const val MIN_CJK_QUERY_CHARS = 2
