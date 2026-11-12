package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
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
    // Mark delivered only after the fragment actually rendered: a blank render
    // (envelope budget) must not burn the lines out of the session.
    val fragment = engine.formatAsPromptFragment(fresh).takeIf { it.isNotBlank() } ?: return null
    announcedRecallKeys.addAll(hits.map { "${it.day}:${it.line}" })
    return fragment
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


private const val ASSISTANT_TAIL_CHARS = 400

private const val MIN_QUERY_CHARS = 4
