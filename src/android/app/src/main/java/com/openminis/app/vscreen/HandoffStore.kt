package com.openminis.app.vscreen

import org.json.JSONObject

/**
 * [T-android-vscreen2] One hand_to_user handoff, persisted per display.
 *
 * The record is the single source of truth for the tool-surface annotations:
 * every state change goes through [HandoffStore.put], which persists to the
 * vscreen prefs file AND fires a package-scoped state-change broadcast
 * (see [HandoffNotifier.ACTION_STATE_CHANGED]) so other components can
 * re-read the fresh state. The broadcast is informational — readers always
 * load from prefs, so a missed broadcast degrades to staleness, not loss.
 */
data class HandoffRecord(
    val displayId: Int,
    val taskId: Int,
    val packageName: String,
    val topActivity: String,
    val kind: String,
    val reason: String,
    val state: HandoffStateMachine.State,
    val startedAtMs: Long,
    val lastChangedAtMs: Long,
    val note: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("display_id", displayId)
        put("task_id", taskId)
        put("package", packageName)
        put("top_activity", topActivity)
        put("kind", kind)
        put("reason", reason)
        put("state", state.name)
        put("started_at_ms", startedAtMs)
        put("last_changed_at_ms", lastChangedAtMs)
        if (note.isNotEmpty()) put("note", note)
    }.toString()

    companion object {
        fun fromJson(raw: String): HandoffRecord? = runCatching {
            val o = JSONObject(raw)
            HandoffRecord(
                displayId = o.getInt("display_id"),
                taskId = o.getInt("task_id"),
                packageName = o.optString("package"),
                topActivity = o.optString("top_activity"),
                kind = o.optString("kind", "other"),
                reason = o.optString("reason"),
                state = HandoffStateMachine.State.valueOf(o.getString("state")),
                startedAtMs = o.getLong("started_at_ms"),
                lastChangedAtMs = o.getLong("last_changed_at_ms"),
                note = o.optString("note"),
            )
        }.getOrNull()
    }
}

/**
 * Prefs-backed handoff record store with a change broadcast hook.
 *
 * [broadcaster] receives every record right after it is written (and after a
 * removal, with null) — production sends the package-scoped
 * [HandoffNotifier.ACTION_STATE_CHANGED] broadcast there; tests count calls.
 */
class HandoffStore(
    private val prefs: VscreenPrefs,
    private val broadcaster: (HandoffRecord?) -> Unit = {},
) {
    fun get(displayId: Int): HandoffRecord? =
        prefs.getString(key(displayId))?.let { HandoffRecord.fromJson(it) }

    fun all(): List<HandoffRecord> =
        prefs.all().keys.filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { prefs.getString(it)?.let(HandoffRecord::fromJson) }

    /** Persists the record and broadcasts the new state. */
    fun put(record: HandoffRecord) {
        prefs.putString(key(record.displayId), record.toJson())
        broadcaster(record)
    }

    /** Removes the record and broadcasts the clearing (null payload). */
    fun remove(displayId: Int) {
        prefs.remove(key(displayId))
        broadcaster(null)
    }

    /**
     * Waiting records whose display is gone can never resolve (the move-back
     * target no longer exists) — clear them and return what was cleared so
     * the caller can log it. The app itself stays wherever the user has it.
     */
    fun expireDead(liveDisplayIds: Set<Int>, nowMs: Long): List<HandoffRecord> {
        val dead = all().filter { it.displayId !in liveDisplayIds }
        dead.forEach { old ->
            remove(old.displayId)
        }
        return dead
    }

    private fun key(displayId: Int) = "$KEY_PREFIX$displayId"

    companion object {
        const val KEY_PREFIX = "handoff_"
    }
}
