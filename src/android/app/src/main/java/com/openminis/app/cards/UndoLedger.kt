package com.openminis.app.cards

import java.util.concurrent.ConcurrentHashMap

/**
 * [G-batch tool result cards] In-memory ledger behind the Change card's undo
 * button. Maps a tool-call id to the old value captured from the result's
 * `was:` line plus the tool name, so the renderer can re-execute the tool
 * with the previous arguments directly — the undo never goes through the
 * model (that is the whole point: a local state restore, not a new turn).
 *
 * Semantics (mirrors the DSH design doc's undo constraints):
 *  - one entry per call id; recording the same call again is a deduped
 *    upsert (identical tool+args are a no-op, a different payload replaces);
 *  - an entry expires after [TTL_MS] — undo is only offered for ~10 minutes
 *    after the call;
 *  - once [markUndone] fires, the entry stays (so the UI can keep showing
 *    the "undone" state) but [undoable] flips false permanently;
 *  - everything lives in RAM only: a process restart simply drops undo
 *    affordances and the plain tool row remains. No persistence, no Room.
 *
 * Time is injected ([nowMs] parameters, defaulting to the wall clock) so the
 * expiry behaviour is unit-testable on the JVM.
 */
object UndoLedger {

    data class Entry(
        val callId: String,
        val toolName: String,
        val oldArgsJson: String,
        val recordedAtMs: Long,
        /** Set when the user's undo tap actually restored the value. */
        val undoneAtMs: Long? = null,
    )

    /** Undo window: 10 minutes after the call, per the design doc. */
    const val TTL_MS: Long = 10 * 60 * 1000L

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Record (or dedup-replace) the old value for [callId]. */
    fun record(callId: String, toolName: String, oldArgsJson: String, nowMs: Long = System.currentTimeMillis()) {
        if (callId.isBlank() || oldArgsJson.isBlank() || toolName.isBlank()) return
        val existing = entries[callId]
        if (existing != null &&
            existing.toolName == toolName &&
            existing.oldArgsJson == oldArgsJson
        ) {
            return // duplicate record for the same call — keep the original timestamp
        }
        entries[callId] = Entry(callId, toolName, oldArgsJson, nowMs)
    }

    /** The live (non-expired, not-yet-undone) entry for [callId], if any. */
    fun entry(callId: String, nowMs: Long = System.currentTimeMillis()): Entry? {
        val e = entries[callId] ?: return null
        if (nowMs - e.recordedAtMs > TTL_MS) {
            entries.remove(callId, e)
            return null
        }
        return e
    }

    /** True when the undo button should be enabled for [callId]. */
    fun undoable(callId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val e = entry(callId, nowMs) ?: return false
        return e.undoneAtMs == null
    }

    /** True as long as a recorded (even expired-but-present) entry exists. */
    fun isUndone(callId: String): Boolean = entries[callId]?.undoneAtMs != null

    /**
     * Mark [callId] undone after a successful restore. Returns false when
     * there is no live entry (already undone, expired, or never recorded).
     */
    fun markUndone(callId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val e = entries[callId] ?: return false
        if (e.undoneAtMs != null) return false
        if (nowMs - e.recordedAtMs > TTL_MS) {
            entries.remove(callId, e)
            return false
        }
        entries[callId] = e.copy(undoneAtMs = nowMs)
        return true
    }

    /** Drop everything (used by tests; the UI keeps the ledger per process). */
    fun clear() = entries.clear()

    /** Current entry count — test/inspection only. */
    fun size(): Int = entries.size
}
