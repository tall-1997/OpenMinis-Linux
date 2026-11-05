package com.openminis.app.data.repository

import android.database.sqlite.SQLiteDatabase

/**
 * [Wave3-fix-4] Single implementation of the per-session enable/disable
 * override CRUD that [SkillRepository] (`session_skill_overrides`, skills.db)
 * and [MCPRepository] (`mcp_session_overrides`, mcp.db) used to carry as two
 * line-for-line identical copies that had already drifted: MCP's
 * [set] cleared rows matching the global default (sparse-clear) while Skill's
 * copy pinned them forever, so a global toggle flip left stale session
 * overrides stuck at the old value (this class gives Skill the fix for free).
 *
 * The four operations every host needs — bulk map for one session, single
 * stored value, per-session clear, and the draft→real session-id rename —
 * plus the [revision] counter the prompt-fragment caches key on, live here.
 * The host repository keeps only its public facade (unchanged signatures) and
 * the global-default lookup that feeds [set]'s sparse-clear.
 *
 * Table/column names are compile-time constants supplied by the two hosts
 * above; they are never user input, so interpolating them into the statements
 * below is safe (values always go through bound parameters).
 *
 * The override TABLES themselves are still created by each host's
 * SQLiteOpenHelper (they live in different databases, and the helpers own
 * their schema/migrations).
 */
internal class SessionOverrideStore(
    private val db: SQLiteDatabase,
    private val tableName: String,
    private val itemColumn: String,
) {

    /**
     * Bumped by every mutation of [tableName]. The host's prompt-fragment
     * cache uses it as part of its cache key so a toggle invalidates the
     * cached fragment instantly, without any change to the items StateFlow.
     */
    @Volatile
    var revision: Long = 0L
        private set

    /**
     * Bulk-load all overrides for one session in a single query. The
     * per-item stored-value path costs one cursor per item — with 200+
     * installed skills that was 200+ cursor round-trips on EVERY turn
     * (fragment built per request). One query instead.
     */
    fun mapForSession(sessionId: String): Map<String, Boolean> {
        val map = HashMap<String, Boolean>()
        db.rawQuery(
            "SELECT $itemColumn, is_enabled FROM $tableName WHERE session_id=?",
            arrayOf(sessionId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                map[cursor.getString(0)] = cursor.getInt(1) == 1
            }
        }
        return map
    }

    /**
     * The override row's value for ([sessionId], [itemId]), or null when no
     * row exists (caller falls through to the global default).
     */
    fun storedValue(sessionId: String, itemId: String): Boolean? {
        val cursor = db.rawQuery(
            "SELECT is_enabled FROM $tableName WHERE session_id=? AND $itemColumn=?",
            arrayOf(sessionId, itemId),
        )
        val value = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
        cursor.close()
        return value
    }

    /**
     * Record the session's choice for [itemId].
     *
     * Sparse-clear ([T-mcp-review-fixes-android] FIX 3, now shared): when
     * [enabled] equals the item's CURRENT global default ([globalEnabled]),
     * DELETE the override row instead of pinning it — so a later flip of the
     * global default propagates to this session. A permanently-pinned row
     * would leave the session stuck at the old value after the global
     * switch turned. Only store a row when the session value genuinely
     * diverges from the global default (or the global value is unknown).
     * Read precedence (override → global → false) is the host's concern and
     * is unchanged.
     */
    fun set(sessionId: String, itemId: String, enabled: Boolean, globalEnabled: Boolean?) {
        if (globalEnabled != null && enabled == globalEnabled) {
            db.execSQL(
                "DELETE FROM $tableName WHERE session_id=? AND $itemColumn=?",
                arrayOf(sessionId, itemId),
            )
        } else {
            db.execSQL(
                "INSERT OR REPLACE INTO $tableName (session_id, $itemColumn, is_enabled) VALUES (?, ?, ?)",
                arrayOf<Any>(sessionId, itemId, if (enabled) 1 else 0),
            )
        }
        revision++
    }

    /** Drop every override row for one item (item deleted / pruned from disk). */
    fun removeItem(itemId: String) {
        db.execSQL("DELETE FROM $tableName WHERE $itemColumn=?", arrayOf(itemId))
        revision++
    }

    /** Drop every override row for one session. */
    fun clearSession(sessionId: String) {
        db.execSQL("DELETE FROM $tableName WHERE session_id=?", arrayOf(sessionId))
        revision++
    }

    /**
     * [T-android-session-skill-override-init-timing] Re-point every row that
     * was written against the draft session id ([fromDraft], e.g.
     * `__new__<uuid>`) onto the persisted session id ([toReal]) once
     * `ensureSession()` creates the real DB row. Without this hop, a
     * pre-first-message toggle stays bound to the draft key and becomes
     * invisible the next time the chat is opened under its real id.
     */
    fun renameSession(fromDraft: String, toReal: String) {
        if (fromDraft == toReal) return
        db.execSQL(
            "UPDATE OR REPLACE $tableName SET session_id=? WHERE session_id=?",
            arrayOf<Any>(toReal, fromDraft),
        )
        revision++
    }
}
