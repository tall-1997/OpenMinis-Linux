package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Query

/**
 * [Wave3-fix-12] Hard ceiling on [UsageStatsDao.allUsageRecords] — the
 * deprecated unbounded usage JOIN stops here instead of materialising a row
 * per assistant turn ever persisted. Generous on purpose; the windowed
 * [UsageStatsDao.usageRecordsBetween] is the real fix.
 */
private const val USAGE_RECORDS_HARD_CAP = 100_000

/**
 * Usage-aggregation queries, extracted from [ChatDao] when that file hit its
 * architecture ceiling (same parent-interface pattern as [MessageHeadDao]).
 */
@Dao
interface UsageStatsDao {
    /**
     * Fetch token usage records joined with session model_id for aggregation.
     *
     * [T-android-usage-orphan-rows] GH#168 (iOS a192fad0f): LEFT JOIN, not
     * INNER JOIN. The Usage page is built from this query, so any row it
     * drops is silently missing from the user's totals. With an INNER JOIN,
     * a message whose `sessions` row is gone — orphaned by a failed sync or
     * migration, or a partially-deleted session — vanished from the totals even
     * though its `token_usage` is still sitting in the table. Those tokens were
     * really billed, so the page under-reported with nothing to indicate it.
     *
     * LEFT JOIN keeps those rows, which is why [UsageRecord.modelId] is
     * nullable: it comes back NULL for an orphan, and the caller groups those
     * under "Unknown" rather than discarding them.
     *
     * This does NOT recover usage from sessions the user deleted outright —
     * deleting a session removes its message rows too. It only stops
     * orphaned-but-present rows from being thrown away.
     *
     * [T-token-attribution-snapshot] The model now comes from
     * `COALESCE(m.model_id, s.model_id)`, preferring the per-message snapshot
     * written when the turn was persisted.
     *
     * `s.model_id` remains only as the fallback for rows written before that
     * column existed. It is a single MUTABLE column per session, rewritten on
     * every model switch (including silent failover) with no history, and the
     * join carries no time dimension — so on its own it re-attributed a
     * session's entire history to whatever model it currently pointed at.
     * That is why `hasSnapshot` is selected alongside: the UI must show
     * fallback rows as ESTIMATED rather than passing them off as measured.
     *
     * [Wave3-fix-12] DEPRECATED for the Usage page: this is an UNBOUNDED
     * whole-table JOIN — on a long-history install it materialises one row per
     * assistant turn ever persisted, growing without limit. It now carries a
     * hard [USAGE_RECORDS_HARD_CAP] so a pathological history cannot allocate
     * without bound (rows past the cap are DROPPED, i.e. totals silently
     * under-report at that point — that is exactly why the cap is very large
     * and why the windowed [usageRecordsBetween] is the preferred query).
     * New callers should use [usageRecordsBetween].
     */
    @Query("""
        SELECT COALESCE(m.model_id, s.model_id) AS modelId,
               m.model_display_name  AS modelDisplayName,
               m.provider_type       AS providerType,
               m.provider_instance_id AS providerInstanceId,
               (m.model_id IS NOT NULL) AS hasSnapshot,
               m.token_usage AS tokenUsage, m.created_at AS createdAt, m.session_id AS sessionId
        FROM messages m LEFT JOIN sessions s ON m.session_id = s.id
        WHERE m.token_usage IS NOT NULL
        LIMIT $USAGE_RECORDS_HARD_CAP
    """)
    @Deprecated(
        message = "Unbounded whole-table JOIN; use usageRecordsBetween(fromMs, toMs) with the UI's time window",
        replaceWith = ReplaceWith("usageRecordsBetween(null, null)"),
    )
    suspend fun allUsageRecords(): List<UsageRecord>

    /**
     * [Wave3-fix-12] Windowed variant of [allUsageRecords] for the Usage page:
     * same projection and orphan-preserving LEFT JOIN, restricted to
     * `m.created_at` in [fromMs]..[toMs] (both INCLUSIVE, both independently
     * optional — a NULL bound means unbounded on that side, so one query
     * serves every UsageTimeRange including "All"). Rows outside the window
     * never cross the CursorWindow, so a time-filtered page costs O(window)
     * instead of O(entire history).
     *
     * The UI still aggregates per-row in Kotlin (token_usage is per-model JSON
     * that SQL cannot meaningfully GROUP BY on older devices), so the intended
     * switch is: query THIS with the selected range's start, then keep the
     * existing aggregation — see ui/settings/UsageStatsScreen.kt.
     */
    @Query("""
        SELECT COALESCE(m.model_id, s.model_id) AS modelId,
               m.model_display_name  AS modelDisplayName,
               m.provider_type       AS providerType,
               m.provider_instance_id AS providerInstanceId,
               (m.model_id IS NOT NULL) AS hasSnapshot,
               m.token_usage AS tokenUsage, m.created_at AS createdAt, m.session_id AS sessionId
        FROM messages m LEFT JOIN sessions s ON m.session_id = s.id
        WHERE m.token_usage IS NOT NULL
          AND (:fromMs IS NULL OR m.created_at >= :fromMs)
          AND (:toMs IS NULL OR m.created_at <= :toMs)
        ORDER BY m.created_at ASC
    """)
    suspend fun usageRecordsBetween(fromMs: Long?, toMs: Long?): List<UsageRecord>
}
