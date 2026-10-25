package com.openminis.app.data.db

import com.openminis.app.data.body.ResourceLimits

/**
 * The SELECT head of every hand-written SQL string handed to a `@RawQuery`
 * method in [ChatDao]. Each constant covers `SELECT … FROM <table>` and stops
 * there; callers append their own dynamic `WHERE` / `ORDER BY` / `LIMIT`.
 *
 * # Why these are centralised
 *
 * `@RawQuery` opts out of Room's compile-time SQL verification. Room maps the
 * returned cursor onto the projection **by column name**, and a column it
 * cannot find is silently assigned `null` — no compiler error, no lint, no
 * warning. When the projection declares that field non-null (every projection
 * here does), constructing the row throws
 * `Parameter specified as non-null is null: … parameter partsJson`.
 *
 * The failure is also *conditional on having hits*, which is what let it ship:
 * a query returning zero rows constructs nothing and looks exactly like "the
 * search found nothing". [MESSAGE_SEARCH_SELECT] was broken this way — its
 * `substr(COALESCE(…))` expression was never aliased, so no column named
 * `parts_json` ever existed in the cursor, and every single successful hit
 * threw instead of returning a match.
 *
 * # The rule
 *
 * Every **expression** column — `substr`, `COALESCE`, `CASE`, a scalar
 * subquery, an aggregate — must carry `AS <projection column name>`. SQLite
 * names an unaliased expression column after its own source text
 * (`substr(COALESCE(m.preview, m.parts_json), 1, 65536)`), which matches
 * nothing. Plain `table.column` picks are already correctly named.
 *
 * `RawQueryColumnContractTest` runs each head against a real SQLite with
 * `LIMIT 0` and compares the cursor's actual column names with what the
 * projection consumes — for [MESSAGE_PAGE_SELECT] the expectation comes from
 * `PRAGMA table_info`, so it tracks schema changes by itself. Because the
 * check reads SQLite's own output rather than parsing Kotlin source, it cannot
 * be fooled by string concatenation or formatting. Add new raw SQL here and it
 * is covered automatically.
 */
internal object RawQuerySql {

    /**
     * Backs [ChatDao.runMessageSearchQuery] → [MessageSearchRow]; used by
     * `ChatRepository.searchMessages` (the `search_sessions` tool and
     * `minis-sessions-cli search`).
     *
     * `parts_json` is `NOT NULL` in the schema, so `COALESCE(preview,
     * parts_json)` can never be null and the projection's non-null field is
     * honest — the alias is the only thing that was missing. The cell is
     * clipped to [ResourceLimits.SQL_CELL_BYTES] because the whole point of
     * `preview` is to keep spilled bodies off the search path.
     *
     * # Why callers split the match by `body_ref`
     *
     * A body over [ResourceLimits.INLINE_BODY_BYTES] (2 KiB — most assistant
     * turns and every large tool result) is written to `filesDir/bodies` and
     * its `parts_json` column is replaced by a `"body stored (N bytes)"` stub.
     * The readable text then survives only in `preview` (the first
     * [ResourceLimits.PREVIEW_BYTES]).
     *
     * `WHERE parts_json LIKE ?` therefore matched the *stub* and silently
     * skipped every spilled message. Both `searchMessages` and
     * `querySessionsMeta` now use
     * `(body_ref IS NULL AND parts_json LIKE ?) OR preview LIKE ?`:
     * inline rows are matched in full through `parts_json`, spilled rows
     * through `preview`, and a spilled row's stub is deliberately *not*
     * matched — otherwise every one of them hits on the meaningless phrase
     * "body stored". Text past the preview prefix lives only in the body file
     * and is not scanned; that is the intended ceiling of a DB-side search,
     * not a defect to paper over.
     */
    const val MESSAGE_SEARCH_SELECT =
        "SELECT m.session_id, m.id, m.role, m.created_at, " +
            "substr(COALESCE(m.preview, m.parts_json), 1, " +
            ResourceLimits.SQL_CELL_BYTES + ") AS parts_json " +
            "FROM messages m"

    /**
     * Backs [ChatDao.runSessionsMetaQuery] → [SessionMetaRow]; used by
     * `ChatRepository.querySessionsMeta`. Both scalar subqueries are aliased.
     */
    const val SESSIONS_META_SELECT =
        "SELECT s.id, s.title, " +
            "(SELECT m2.parts_json FROM messages m2 " +
            "WHERE m2.session_id = s.id AND m2.role = 'user' " +
            "ORDER BY m2.sort_order ASC LIMIT 1) AS first_user_msg, " +
            "s.source, s.created_at, s.updated_at, " +
            "(SELECT COUNT(*) FROM messages m3 WHERE m3.session_id = s.id) AS msg_count " +
            "FROM sessions s"

    /**
     * Backs [ChatDao.runMessagesQuery] → [MessageEntity]; used by the
     * `sessions messages` debug RPC. This is an explicit column list (not
     * `SELECT *`) so the body-spill `CASE` can stand in for a heavy
     * `parts_json`, which means every other [MessageEntity] column has to be
     * named here or it comes back null.
     */
    const val MESSAGE_PAGE_SELECT =
        "SELECT id, session_id, role, " +
            "CASE WHEN body_ref IS NOT NULL THEN parts_json " +
            "WHEN body_bytes <= 2048 THEN parts_json " +
            "ELSE COALESCE(preview, " +
            "'[{\"type\":\"text\",\"value\":\"[body kept on disk]\"}]') END AS parts_json, " +
            "created_at, token_usage, sort_order, reasoning_content, " +
            "stream_interrupt_count, updated_at, error_info, model_id, " +
            "model_display_name, provider_type, provider_instance_id, " +
            "body_bytes, body_ref, body_sha, preview " +
            "FROM messages"

    /** Backs [ChatDao.runMessageCountQuery] → [MessageCountRow]. */
    const val MESSAGE_COUNT_SELECT = "SELECT COUNT(*) AS count FROM messages"
}
