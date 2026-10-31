package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Query

/**
 * Bounded head reads of a session's messages, split out of [ChatDao] so that
 * file stays under its line ceiling. Every query here returns [MessageHeadRow]
 * and bounds the payload at the cursor — callers that only need a label or a
 * snippet must never pull the full `parts_json` blob.
 */
@Dao
interface MessageHeadDao {

    /**
     * [T-android-huge-session-load-oom] Title / snippet / harvest callers only
     * need the first USER text head of a session (plus its char length to
     * detect blankness). Extracting `value` in SQL via json_extract would be
     * ideal but org.json-style parts are a JSON ARRAY whose text parts are
     * objects; SQLite's json1 IS available on Android 9+ (minSdk 26), so this
     * stays a plain substring head and the caller re-parses only [limit]
     * bounded rows. `head_chars` bounds each row's payload at the CURSOR, so a
     * 5.4M-char parts_json costs <= head_chars + overhead per row here — never
     * the full blob.
     */
    @Query("""
        SELECT id, role, created_at,
               substr(parts_json, 1, :headChars) AS head_text
        FROM messages
        WHERE session_id = :sessionId
        ORDER BY sort_order ASC, created_at ASC
        LIMIT :limit
    """)
    suspend fun loadMessageHeads(sessionId: String, headChars: Int, limit: Int): List<MessageHeadRow>

    @Query("""
        SELECT id, role, created_at,
               substr(parts_json, 1, :headChars) AS head_text
        FROM messages
        WHERE session_id = :sessionId AND role = :role
        ORDER BY sort_order ASC, created_at ASC
        LIMIT :limit
    """)
    suspend fun loadMessageHeadsByRole(
        sessionId: String,
        role: String,
        headChars: Int,
        limit: Int,
    ): List<MessageHeadRow>

    @Query("""
        SELECT id, role, created_at,
               substr(parts_json, 1, :headChars) AS head_text
        FROM messages
        WHERE session_id = :sessionId AND role = :role
        ORDER BY sort_order DESC, created_at DESC
        LIMIT :limit
    """)
    suspend fun loadMessageHeadsByRoleNewest(
        sessionId: String,
        role: String,
        headChars: Int,
        limit: Int,
    ): List<MessageHeadRow>
}
