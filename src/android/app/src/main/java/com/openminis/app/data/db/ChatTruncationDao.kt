package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Query

/**
 * [T-m6-pair-removal] / truncation-focused DAO slice, extracted from
 * [ChatDao] when that file hit its architecture ceiling.  Declared as a
 * parent interface the same way [MessageHeadDao] is: Room processes
 * inherited abstract methods, and
 * `ChatDao : MessageHeadDao, ChatTruncationDao` keeps every method on one
 * generated implementation while the source stays below the line budget.
 * Projections reuse [SAFE_MESSAGE_FROM] so there is exactly one copy of
 * the body-ref CASE logic.
 */
@Dao
interface ChatTruncationDao {
    @Query("""
        $SAFE_MESSAGE_FROM
        WHERE session_id = :sessionId
          AND sort_order >= :startInclusive
          AND sort_order < :endExclusive
        ORDER BY sort_order ASC, created_at ASC, id ASC
        LIMIT :limit
    """)
    suspend fun loadMessagesFromSortOrder(
        sessionId: String,
        startInclusive: Int,
        endExclusive: Int,
        limit: Int,
    ): List<MessageEntity>

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND sort_order >= :keepCount")
    suspend fun deleteMessagesAfter(sessionId: String, keepCount: Int)

    /**
     * [T-m6-pair-removal] Single-row delete, used only by pair removal: after
     * a truncation drains an orphaned tool_use out of its row, the row can be
     * left with no parts at all, and then the only correct cleanup is to
     * remove it.  Callers MUST snap the row into message_versions first —
     * ChatRepository.deleteMessageById does that, mirroring
     * [deleteMessagesAfter]-side archiving.
     */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    /**
     * [T-msg-version-archive] Raw rows (no preview substitution) that
     * [deleteMessagesAfter] is about to remove, so the caller can archive
     * them into `message_versions` first.
     */
    // NOTE: explicit column list (never SELECT *) — ResourceBoundaryTest
    // forbids star/raw projections on the messages table. All columns are
    // listed because the archived version needs the full row.
    @Query(
        "SELECT id, session_id, role, parts_json, created_at, token_usage, sort_order, " +
            "reasoning_content, stream_interrupt_count, updated_at, error_info, " +
            "model_id, model_display_name, provider_type, provider_instance_id, " +
            "body_bytes, body_ref, body_sha, preview " +
            "FROM messages WHERE session_id = :sessionId AND sort_order >= :cutoff ORDER BY sort_order ASC"
    )
    suspend fun rowsFrom(sessionId: String, cutoff: Int): List<MessageEntity>

    /**
     * [T-m6-pair-removal] Bounded variant of [rowsFrom]: only the first
     * [limit] doomed rows. Pair repair only needs the rows adjacent to the
     * cut (a surviving part's dead counterpart is the immediately
     * neighbouring row), so loading the entire doomed tail here would
     * duplicate the work [rowsFrom] already does for archiving.
     */
    @Query(
        "SELECT id, session_id, role, parts_json, created_at, token_usage, sort_order, " +
            "reasoning_content, stream_interrupt_count, updated_at, error_info, " +
            "model_id, model_display_name, provider_type, provider_instance_id, " +
            "body_bytes, body_ref, body_sha, preview " +
            "FROM messages WHERE session_id = :sessionId AND sort_order >= :cutoff " +
            "ORDER BY sort_order ASC LIMIT :limit"
    )
    suspend fun firstRowsFrom(sessionId: String, cutoff: Int, limit: Int): List<MessageEntity>

    // [T-archive-trimmed-parts] Full-row read for archiving a row that is
    // about to be rewritten in place (updateMessageParts). Same explicit
    // projection as rowsFrom — never SELECT * (ResourceBoundaryTest).
    @Query(
        "SELECT id, session_id, role, parts_json, created_at, token_usage, sort_order, " +
            "reasoning_content, stream_interrupt_count, updated_at, error_info, " +
            "model_id, model_display_name, provider_type, provider_instance_id, " +
            "body_bytes, body_ref, body_sha, preview " +
            "FROM messages WHERE id = :id"
    )
    suspend fun messageById(id: String): MessageEntity?
}