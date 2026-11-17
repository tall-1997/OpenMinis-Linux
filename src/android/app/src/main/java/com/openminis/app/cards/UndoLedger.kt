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
 *  - [T-undo-staleness-ordering] only the LATEST record per (tool, subject)
 *    is undoable: undoing an older card of the same subject would silently
 *    clobber whatever the newer call (or the user, outside the app) changed
 *    since. A successful undo also voids every older record of the same
 *    subject — restoring them would re-apply even older values on top;
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
        /** [T-undo-staleness-ordering] Subject key this record restores. */
        val subjectKey: String = "",
        /** [T-undo-staleness-ordering] Monotonic sequence within [subjectKey]. */
        val seq: Long = 0L,
    )

    /** Undo window: 10 minutes after the call, per the design doc. */
    const val TTL_MS: Long = 10 * 60 * 1000L

    private val entries = ConcurrentHashMap<String, Entry>()

    /** [T-undo-staleness-ordering] Latest sequence ever assigned per subject key. */
    private val sequences = ConcurrentHashMap<String, Long>()

    /**
     * [T-undo-staleness-ordering] Which piece of device state an undo of this
     * record would restore. Two records for the same subject must never both
     * be undoable — restoring the older one would clobber the newer change.
     *
     * Derived from the tool name plus the one argument that selects the
     * subject WITHIN a tool (volume's `stream`; the undo payload is the
     * original args merged with the old value, so the selector survives).
     * Every other Change tool addresses a single device-wide state, so the
     * tool name alone is the subject; unknown tools fall back to the tool
     * name too — conservative (one subject per tool) and deterministic.
     * Garbage args JSON degrades to the tool-level subject instead of throwing.
     */
    internal fun subjectKeyOf(toolName: String, argsJson: String): String {
        if (toolName == "volume_set") {
            val stream = runCatching {
                org.json.JSONObject(argsJson).optString("stream")
            }.getOrNull()?.takeIf { it.isNotEmpty() }
            if (stream != null) return "volume:$stream"
        }
        return when (toolName) {
            "volume_set" -> "volume"
            "brightness_set" -> "brightness"
            "dnd_set" -> "dnd"
            "sound_mode" -> "sound_mode"
            "wifi_toggle" -> "wifi"
            "bluetooth_toggle" -> "bluetooth"
            "mobile_data_toggle" -> "mobile_data"
            else -> toolName
        }
    }

    /** Record (or dedup-replace) the old value for [callId]. */
    fun record(callId: String, toolName: String, oldArgsJson: String, nowMs: Long = System.currentTimeMillis()) {
        if (callId.isBlank() || oldArgsJson.isBlank() || toolName.isBlank()) return
        val existing = entries[callId]
        if (existing != null &&
            existing.toolName == toolName &&
            existing.oldArgsJson == oldArgsJson
        ) {
            return // duplicate record for the same call — keep the original timestamp (and sequence)
        }
        // [T-undo-staleness-ordering] A new (or payload-replaced) record for a
        // subject becomes that subject's latest — it is the most recent state
        // change the ledger knows about. Only called from composition on the
        // main thread, so the read-modify-write needs no extra locking.
        val subjectKey = subjectKeyOf(toolName, oldArgsJson)
        val seq = (sequences[subjectKey] ?: 0L) + 1L
        sequences[subjectKey] = seq
        entries[callId] = Entry(callId, toolName, oldArgsJson, nowMs, subjectKey = subjectKey, seq = seq)
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
        if (e.undoneAtMs != null) return false
        // [T-undo-staleness-ordering] A stale record (a newer record exists
        // for the same tool+subject) is refused: undoing it would restore an
        // even older value on top of the newer change. Unknown-subject
        // entries (seq defaulted, subject never sequenced) keep the old
        // behaviour instead of becoming permanently refused.
        return e.seq == (sequences[e.subjectKey] ?: e.seq)
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
        // [T-undo-staleness-ordering] Post-undo invalidation: every OLDER
        // record of the same subject is voided — its stored value predates
        // both this record and the restore, so re-running it would layer an
        // even older value on top. Removing (not "undone"-marking) keeps the
        // truthful UI: those cards show no undo affordance and the raw tool
        // output stays visible in the detail sheet.
        entries.entries
            .asSequence()
            .filter { it.key != callId && it.value.subjectKey == e.subjectKey && it.value.seq < e.seq }
            .map { it.key }
            .toList()
            .forEach { entries.remove(it) }
        return true
    }

    /** Drop everything (used by tests; the UI keeps the ledger per process). */
    fun clear() {
        entries.clear()
        sequences.clear()
    }

    /** Current entry count — test/inspection only. */
    fun size(): Int = entries.size
}
