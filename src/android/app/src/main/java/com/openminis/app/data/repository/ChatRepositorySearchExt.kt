package com.openminis.app.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageSnippetRow

/**
 * [T-#26-session-search-and] Multi-word AND session search.
 *
 * Previously the whole query was one substring: `searchSessions` wrapped the
 * raw string in `%…%` and the message-level snippet query ran a single
 * `instr(lower(preview), lower(:query))`, so `词1 词2` only matched when the
 * words appeared ADJACENT in the text. These extensions fork the two search
 * call sites (`ChatRepository.searchSessions` and the
 * `firstSnippetPerSession` walk in `SessionListViewModel.buildContentHits`)
 * on the tokenised words:
 *
 *  - single word (no whitespace in the query) → the ORIGINAL DAO methods with
 *    the original arguments, byte-for-byte unchanged behaviour;
 *  - whitespace query with ≥1 surviving word → the `*Multi` `@RawQuery`
 *    methods on [com.openminis.app.data.db.ChatSearchDao], where every word
 *    must be present (`instr(lower(col), lower(word)) > 0` AND-chained).
 *
 * Token rules: split on whitespace, trim, a word only participates when it is
 * ≥ [SEARCH_MIN_WORD_CHARS] long, at most [SEARCH_MAX_WORDS] words (extras
 * truncated — the 5th+ words are NOT required, they are simply not searched).
 * A whitespace query whose tokens all die in tokenization (e.g. `a b`) has
 * nothing to AND, so it falls back to the original whole-string path rather
 * than degenerating into "match everything".
 *
 * The fork lives here instead of in the UI layer so the single public entry
 * points keep their signatures and every search caller benefits; it lives in
 * the repository package (not on [ChatRepository] itself) because
 * ChatRepository.kt is at its architecture-policy line ceiling — a separate
 * file costs nothing, and the extension-receiver form keeps the call sites
 * one-line.
 */

/** Words above this count are truncated away, never required. */
internal const val SEARCH_MAX_WORDS = 4

/** Shorter tokens are dropped: they are noise, not search terms. */
internal const val SEARCH_MIN_WORD_CHARS = 2

/**
 * The words that participate in a multi-word AND search, in bind order:
 * whitespace-split, trimmed, `length >= [SEARCH_MIN_WORD_CHARS]`, capped at
 * [SEARCH_MAX_WORDS]. Pure and JVM-testable — the SQL the `*Multi` DAO
 * methods run is composed from exactly this list.
 */
internal fun searchWords(query: String): List<String> =
    query
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length >= SEARCH_MIN_WORD_CHARS }
        .take(SEARCH_MAX_WORDS)

/**
 * The spec's fork condition, made explicit: any whitespace in the query routes
 * to the multi-word path, unless tokenization left nothing to AND.
 */
internal fun isMultiWordSearch(query: String): Boolean =
    query.contains(Regex("\\s")) && searchWords(query).isNotEmpty()

/**
 * A composed raw query: SQL text plus positional bind args, in order. Kept as
 * a plain holder (instead of returning [SimpleSQLiteQuery] directly) so the
 * composition is assertable in a plain JVM test without touching Android
 * SQLite wrapper classes.
 */
internal data class RawSearchSql(val sql: String, val args: List<Any>)

/**
 * Multi-word AND sessions search, shape-copied from
 * `ChatSearchDao.searchSessions`' `@Query` (`SELECT DISTINCT s.*` + the same
 * LEFT JOIN / ORDER BY / LIMIT 200) with the predicate replaced by
 * AND-of-words on each column and OR between the columns. Each word binds
 * twice: once in the title conjunct, once in the preview conjunct — the arg
 * order below IS the `?` order in the SQL, do not reorder one side alone.
 */
internal fun buildSessionsMultiQuery(words: List<String>): RawSearchSql {
    val titleAnd = words.joinToString(" AND ") { "instr(lower(s.title), lower(?)) > 0" }
    val previewAnd = words.joinToString(" AND ") {
        "instr(lower(COALESCE(m.preview, m.parts_json)), lower(?)) > 0"
    }
    val sql = "SELECT DISTINCT s.* FROM sessions s " +
        "LEFT JOIN messages m ON m.session_id = s.id " +
        "WHERE ($titleAnd) OR ($previewAnd) " +
        "ORDER BY s.updated_at DESC LIMIT 200"
    return RawSearchSql(sql, words + words)
}

/**
 * Multi-word AND variant of `ChatSearchDao.firstSnippetPerSession`, copied
 * shape-for-shape (projection aliases `session_id`/`id`/`head_text`, the
 * GROUP BY + `HAVING m.sort_order = MIN(m.sort_order)` earliest-row trick)
 * with the WHERE extended by one `instr(...) > 0` conjunct per word. The
 * `substr` window centres on the FIRST word's match, so [RawSearchSql.args]
 * order is: first word, radius, windowChars, the IN-list session ids, then
 * every word again for the AND conjuncts.
 */
internal fun buildSnippetMultiQuery(
    words: List<String>,
    sessionIds: List<String>,
    radius: Int,
    windowChars: Int,
): RawSearchSql {
    val inList = sessionIds.joinToString(",") { "?" }
    val ands = words.joinToString(" AND ") { "instr(lower(m.preview), lower(?)) > 0" }
    val sql = "SELECT m.session_id AS session_id, m.id AS id, " +
        "substr(m.preview, max(1, instr(lower(m.preview), lower(?)) - ?), ?) AS head_text " +
        "FROM messages m " +
        "WHERE m.session_id IN ($inList) AND m.preview IS NOT NULL AND $ands " +
        "GROUP BY m.session_id HAVING m.sort_order = MIN(m.sort_order)"
    return RawSearchSql(
        sql,
        listOf(words.first(), radius, windowChars) + sessionIds + words,
    )
}

/**
 * Word-aware fork over `ChatRepository.searchSessions`. Whitespace queries run
 * [com.openminis.app.data.db.ChatSearchDao.searchSessionsMulti]; everything
 * else — including the degenerate all-short-tokens case — runs the ORIGINAL
 * DAO method with the original `%query%` pattern, so single-word behaviour is
 * byte-identical to before this change.
 */
suspend fun ChatRepository.searchSessionsWordAware(
    query: String,
): List<ChatSessionEntity> {
    if (!isMultiWordSearch(query)) {
        return searchDao().searchSessions("%$query%")
    }
    val composed = buildSessionsMultiQuery(searchWords(query))
    return searchDao().searchSessionsMulti(
        SimpleSQLiteQuery(composed.sql, composed.args.toTypedArray()),
    )
}

/**
 * Word-aware fork over `ChatSearchDao.firstSnippetPerSession` with the same
 * routing as [searchSessionsWordAware], so the snippet predicate always
 * matches the predicate that selected the sessions. `sessionIds` empty returns
 * empty instead of letting an empty `IN ()` reach SQLite.
 */
suspend fun ChatRepository.firstSnippetPerSessionWordAware(
    sessionIds: List<String>,
    query: String,
    radius: Int,
    windowChars: Int,
): List<MessageSnippetRow> {
    if (sessionIds.isEmpty()) return emptyList()
    if (!isMultiWordSearch(query)) {
        return searchDao().firstSnippetPerSession(sessionIds, query, radius, windowChars)
    }
    val composed = buildSnippetMultiQuery(searchWords(query), sessionIds, radius, windowChars)
    return searchDao().firstSnippetPerSessionMulti(
        SimpleSQLiteQuery(composed.sql, composed.args.toTypedArray()),
    )
}
