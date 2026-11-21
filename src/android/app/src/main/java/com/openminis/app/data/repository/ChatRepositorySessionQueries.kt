package com.openminis.app.data.repository

import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.data.db.RawQuerySql

/**
 * The three high-level queries surfaced to SessionsOffloadHandler, backing
 * `minis-sessions-cli list` / `search` / `messages`. The DAO side handles raw
 * SQL + result projection; these implementations add the JSON parsing, text
 * extraction, and snippet trimming. Mirrors iOS `ChatStore.swift` L774-1023
 * line-by-line so the offload tool's output shape is identical across
 * platforms.
 *
 * Extracted verbatim from ChatRepository.kt when that file hit its
 * architecture line ceiling. The public entry points stay as one-line member
 * delegates there ([ChatRepository.querySessionsMeta],
 * [ChatRepository.searchMessages], [ChatRepository.loadMessagePage]), so
 * every caller — SessionsOffloadHandler, SessionLookupTool,
 * ScheduledTasksViewModel, MessageSearchIntegrationTest — is unchanged. The
 * bodies are internal extensions on [ChatRepository] rather than free
 * functions because they read `dao` and `extractTextForOffload` off the
 * repository instance.
 */

/**
 * Backs `minis-sessions-cli list`. Returns sessions ordered by
 * last_active DESC, optionally filtered by id list, keyword AND, and
 * a date range on `updated_at` (so the user's "show me sessions
 * touched in March 2026" works on the timestamp the session-list UI
 * already exposes).
 *
 * Keyword AND semantics: each keyword has to land *somewhere* — in
 * the title or in any message's parts_json. Two keywords mean both
 * must match (possibly in different messages). This matches iOS,
 * which intentionally avoids requiring keywords to co-occur in one
 * row so a multi-turn session about "python" + "flask" still hits.
 *
 * [keywordsMatchBody] = false restricts keyword matching to titles.
 * `minis-sessions-cli list` passes false: that command is ungated
 * (1.36.25 — "list stays open: only returns id and title"), so matching
 * — and thereby revealing hits against — message bodies of foreign
 * sessions would hand an unauthorized caller a content oracle that
 * defeats the session_read boundary.
 */
internal suspend fun ChatRepository.querySessionsMetaIn(
    sessionIds: List<String>?,
    keywords: List<String>?,
    limit: Int,
    startMs: Long?,
    endMs: Long?,
    keywordsMatchBody: Boolean = true,
): List<SessionMeta> {
    val conditions = mutableListOf<String>()
    val args = mutableListOf<Any>()

    if (!sessionIds.isNullOrEmpty()) {
        // Android 11 and older cap SQLite host parameters at 999. Chunk the
        // id filter so a caller passing a large id set cannot turn list/search
        // into a SQLiteException.
        val idFilter = sessionIds.chunked(400).joinToString(" OR ") { chunk ->
            "s.id IN (${chunk.joinToString(",") { "?" }})"
        }
        conditions += "($idFilter)"
        args.addAll(sessionIds)
    }
    if (startMs != null) {
        conditions += "s.updated_at >= ?"
        args += startMs
    }
    if (endMs != null) {
        conditions += "s.updated_at <= ?"
        args += endMs
    }
    if (!keywords.isNullOrEmpty()) {
        for (kw in keywords) {
            val pat = "%$kw%"
            if (keywordsMatchBody) {
                conditions +=
                    "(s.title LIKE ? OR EXISTS (SELECT 1 FROM messages m " +
                    "WHERE m.session_id = s.id AND " +
                    "((m.body_ref IS NULL AND m.parts_json LIKE ?) OR " +
                    "m.preview LIKE ?)))"
                args += pat
                args += pat
                args += pat
            } else {
                conditions += "s.title LIKE ?"
                args += pat
            }
        }
    }
    val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
    val sql = RawQuerySql.SESSIONS_META_SELECT +
        " $where ORDER BY s.updated_at DESC LIMIT ?"
    args += limit

    val rows = dao.runSessionsMetaQuery(
        androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray()),
    )
    return rows.map { r ->
        val preview = r.firstUserMsg?.let { extractTextForOffload(it) }
            ?.takeIf { it.isNotBlank() }
            ?.take(60)
        SessionMeta(
            id = r.id,
            title = r.title,
            preview = preview,
            source = r.source,
            startedAt = r.createdAt,
            lastActive = r.updatedAt,
            messageCount = r.msgCount,
        )
    }
}

/**
 * Backs `minis-sessions-cli search`. Over-fetches `limit * 3` rows
 * because the LIKE matches can hit tool-call JSON metadata
 * (e.g. a tool name that happens to contain the keyword) rather than
 * actual user-visible text. We parse each row's parts_json on the
 * Kotlin side, drop rows whose extracted text is blank or whose
 * keyword didn't survive the parse, and trim to [limit] on the way
 * out. Mirrors iOS ChatStore.searchMessages.
 *
 * Searchable text is what the row kept in the database — see
 * [RawQuerySql.MESSAGE_SEARCH_SELECT].
 */
internal suspend fun ChatRepository.searchMessagesIn(
    sessionIds: List<String>?,
    keywords: List<String>,
    limit: Int,
    startMs: Long?,
    endMs: Long?,
): List<MessageSearchMatch> {
    if (keywords.isEmpty()) return emptyList()
    val budget = limit.coerceIn(1, 32).toLong() * ResourceLimits.SQL_CELL_BYTES
    if (!com.openminis.app.data.body.Admission.tryAdmit(budget)) {
        // INTENTIONAL BEHAVIOR CHANGE — this used to `return emptyList()`, so
        // an admission refusal was indistinguishable from "no matches" and the
        // model concluded the topic does not exist. The throw is deliberate:
        // SessionLookupTool's `catch (Exception)` turns it into an
        // "Error: …" tool result, which the agent retries, while a genuine
        // empty result stays a clean empty list. Empty means "no matches" to
        // the model; refusal must stay distinct so it can retry instead of
        // concluding the topic does not exist.
        throw com.openminis.app.data.SearchAdmissionUnavailable(
            "Search is temporarily unavailable: another bounded IO operation holds the admission budget.",
        )
    }
    return try {
    val conditions = mutableListOf<String>()
    val args = mutableListOf<Any>()

    for (kw in keywords) {
        conditions += "((m.body_ref IS NULL AND m.parts_json LIKE ?) OR " +
            "m.preview LIKE ?)"
        args += "%$kw%"
        args += "%$kw%"
    }
    if (!sessionIds.isNullOrEmpty()) {
        val idFilter = sessionIds.chunked(400).joinToString(" OR ") { chunk ->
            "m.session_id IN (${chunk.joinToString(",") { "?" }})"
        }
        conditions += "($idFilter)"
        args.addAll(sessionIds)
    }
    if (startMs != null) {
        conditions += "m.created_at >= ?"
        args += startMs
    }
    if (endMs != null) {
        conditions += "m.created_at <= ?"
        args += endMs
    }
    val where = conditions.joinToString(" AND ")
    // SELECT head + the alias/preview contract live in RawQuerySql.
    val sql = RawQuerySql.MESSAGE_SEARCH_SELECT +
        " WHERE $where ORDER BY m.created_at DESC LIMIT ?"
    args += (limit * 3)

    val rows = dao.runMessageSearchQuery(
        androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray()),
    )
    val out = mutableListOf<MessageSearchMatch>()
    for (r in rows) {
        val text = extractTextForOffload(r.partsJson)
        if (text.isBlank()) continue
        val snip = keywordSnippet(text, keywords, ChatRepository.SNIPPET_MAX)
        if (snip.isBlank()) continue
        out += MessageSearchMatch(r.sessionId, r.id, r.role, r.createdAt, snip)
        if (out.size >= limit) break
    }
    out
    } finally {
        com.openminis.app.data.body.Admission.release(budget)
    }
}

/**
 * Center a snippet of [maxLength] chars on the earliest keyword
 * match (case-insensitive). Tail/head ellipses indicate truncation
 * boundaries. If no keyword survives the parts_json → text reduction
 * (rare but possible — a SQL LIKE hit on tool-use JSON that the text
 * extractor strips), we return the leading [maxLength] chars so the
 * offload caller still sees *something*.
 */
internal fun keywordSnippet(text: String, keywords: List<String>, maxLength: Int): String {
    if (text.isEmpty()) return ""
    val lower = text.lowercase()
    var earliest = text.length
    for (kw in keywords) {
        val pos = lower.indexOf(kw.lowercase())
        if (pos in 0 until earliest) earliest = pos
    }
    if (earliest == text.length) return text.take(maxLength)
    val half = maxLength / 2
    val start = (earliest - half).coerceAtLeast(0)
    val end = (start + maxLength).coerceAtMost(text.length)
    var s = text.substring(start, end)
    if (start > 0) s = "…$s"
    if (end < text.length) s = "$s…"
    return s
}

/**
 * Backs `minis-sessions-cli messages --id ... --offset --limit`.
 * Skips messages whose extracted text is blank (system-only reminder
 * content, all-tool-use turns) so the agent sees a contiguous
 * user-visible transcript.
 */
internal suspend fun ChatRepository.loadMessagePageIn(
    sessionId: String,
    offset: Int,
    limit: Int,
    maxChars: Int,
    startMs: Long?,
    endMs: Long?,
): List<MessagePageItem> {
    val rows = if (startMs == null && endMs == null) {
        dao.loadMessagesPage(sessionId, offset, limit)
    } else {
        dao.loadMessagesPageInRange(sessionId, offset, limit, startMs, endMs)
    }
    return rows.mapNotNull { e ->
        val text = extractTextForOffload(e.partsJson)
        if (text.isBlank()) return@mapNotNull null
        MessagePageItem(
            e.id, e.role, e.createdAt, text.take(maxChars),
            // Mark messages that exceeded the cap so the caller can emit
            // "truncated": true (mirrors iOS SessionsOffloadBridge).
            truncated = text.length > maxChars,
            bodyOffloaded = e.bodyRef != null,
        )
    }
}
