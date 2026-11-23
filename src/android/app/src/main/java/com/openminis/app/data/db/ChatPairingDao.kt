package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Query

/**
 * [T-pairing-writeback] Queries for persisting interrupted tool-call results
 * next to their use row, so the request projection's pairing repair finds the
 * call already matched and stops injecting a synthetic user turn on every
 * request. Split from [ChatDao] to keep that file under its line ceiling;
 * registered on [AppDatabase] as `pairingDao`.
 */
@Dao
public interface ChatPairingDao {

    @Query("SELECT sort_order FROM messages WHERE id = :id")
    suspend fun sortOrderOf(id: String): Int?

    /** [T-pairing-writeback] Make room right after [afterSort]: shift every
     *  later row down by one, inside the caller's transaction. */
    @Query("UPDATE messages SET sort_order = sort_order + 1 WHERE session_id = :sessionId AND sort_order > :afterSort")
    suspend fun shiftSortOrdersAfter(sessionId: String, afterSort: Int)

    /**
     * [T-pairing-writeback] Every persisted result row's partsJson for the
     * session, user rows in sort order. The idempotence scan matches tool-use
     * ids as EXACT serialized tokens — a LIKE '%id%' would false-positive on
     * ids embedded in another row's input/output text and false-negative on
     * ids that merely contain the needle as a substring.
     */
    @Query("SELECT parts_json FROM messages WHERE session_id = :sessionId AND role = 'user' ORDER BY sort_order ASC, created_at ASC, id ASC")
    suspend fun resultRowsJson(sessionId: String): List<String>
}
