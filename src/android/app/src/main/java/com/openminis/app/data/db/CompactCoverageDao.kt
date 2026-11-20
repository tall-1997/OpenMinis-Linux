package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Query
import com.openminis.app.data.body.ResourceLimits

/** Columns needed to verify the exact compact input without materializing UI or media bodies. */
data class CompactSourceRow(
    val id: String,
    val role: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long?,
    @ColumnInfo(name = "body_bytes") val bodyBytes: Long,
    @ColumnInfo(name = "body_ref") val bodyRef: String?,
    @ColumnInfo(name = "body_sha") val bodySha: String?,
    @ColumnInfo(name = "terminal_assistant") val terminalAssistant: Boolean,
)

data class CompactInlineProbe(
    val size: Long,
    val parts: ByteArray?,
)

private const val COMPACT_SMALL_INLINE_BYTES = ResourceLimits.INLINE_BODY_BYTES
private const val COMPACT_SQL_CHUNK_BYTES = ResourceLimits.SQL_CELL_BYTES
private const val INTERRUPTED_TOKEN_USAGE = PersistedMessageStatus.INTERRUPTED

interface CompactCoverageDao {
    @Query("SELECT sort_order FROM messages WHERE session_id = :sessionId AND id = :id")
    suspend fun compactOrderForId(sessionId: String, id: String): Int?

    // Sort order is unique in a session. Both bounds use its index; no offset
    // paging, whole-session id set, unbounded cells, or reasoning/media reads.
    @Query("""
        SELECT id, role, sort_order, updated_at, body_bytes, body_ref, body_sha,
            CASE WHEN role = 'assistant' AND
                (COALESCE(TRIM(error_info), '') <> '' OR token_usage = '$INTERRUPTED_TOKEN_USAGE')
                THEN 1 ELSE 0 END AS terminal_assistant
        FROM messages
        WHERE session_id = :sessionId AND sort_order >= :start AND sort_order <= :end
        ORDER BY sort_order ASC LIMIT :limit
    """)
    suspend fun compactSourceRows(sessionId: String, start: Long, end: Long, limit: Int): List<CompactSourceRow>

    @Query("SELECT MAX(sort_order) FROM messages WHERE session_id = :sessionId")
    suspend fun compactTailOrder(sessionId: String): Int?

    @Query("""
        SELECT length(CAST(parts_json AS BLOB)) AS size,
            CASE WHEN length(CAST(parts_json AS BLOB)) <= $COMPACT_SMALL_INLINE_BYTES
                 THEN CAST(parts_json AS BLOB) END AS parts
        FROM messages WHERE session_id = :sessionId AND id = :id AND body_ref IS NULL
    """)
    suspend fun compactInlineProbe(sessionId: String, id: String): CompactInlineProbe?

    // Bytes, not UTF-16 offsets: a multibyte UTF-8 character may span chunks.
    @Query("""
        SELECT substr(CAST(parts_json AS BLOB), :offset, MIN(:count, $COMPACT_SQL_CHUNK_BYTES)) FROM messages
        WHERE session_id = :sessionId AND id = :id AND body_ref IS NULL
    """)
    suspend fun compactInlineBodyChunk(sessionId: String, id: String, offset: Int, count: Int): ByteArray?

    @Query("""
        SELECT EXISTS(SELECT 1 FROM messages
            WHERE session_id = :sessionId AND sort_order >= :start AND sort_order < :endExclusive)
    """)
    suspend fun hasCompactSourceRows(sessionId: String, start: Long, endExclusive: Long): Boolean
}
