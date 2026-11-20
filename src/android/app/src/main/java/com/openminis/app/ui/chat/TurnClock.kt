package com.openminis.app.ui.chat

/**
 * [T-time-context-interval] When the model last SAW something, per session.
 *
 * WHY a separate holder instead of a field: `ChatViewModel.kt` sits exactly on
 * its architecture ceiling (4,613 lines, ratchet 4,613), so the clock cannot add
 * a member there. It is also the right shape — this is a single timestamp per
 * session with no ViewModel state to read, exactly like [TimeContextGate].
 *
 * Used only to fill `UserTransformContext.previousMessageAtMs`, which turns the
 * injected timestamp into an ELAPSED reading. Without it the model sees
 * "14:30" and still cannot tell whether the previous message was one minute or
 * one hour ago — the actual complaint behind mis-dated answers in long runs.
 *
 * In-memory and bounded: a process restart costs one "no baseline" reading
 * (the elapsed line is omitted, never fabricated), and a host opening many
 * sessions must not accumulate entries.
 */
internal object TurnClock {

    /** Sessions tracked at once; oldest access is dropped first. */
    private const val MAX_SESSIONS = 64

    private val lastModelVisibleAt = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Long>,
            ): Boolean = size > MAX_SESSIONS
        },
    )

    /** Epoch millis of the previous model-visible message, or null when unknown. */
    fun previousMessageAt(sessionId: String?): Long? {
        if (sessionId.isNullOrBlank()) return null
        return synchronized(lastModelVisibleAt) { lastModelVisibleAt[sessionId] }
    }

    /** Record that a message became model-visible at [nowMs]. */
    fun markModelVisible(sessionId: String?, nowMs: Long) {
        if (sessionId.isNullOrBlank()) return
        synchronized(lastModelVisibleAt) { lastModelVisibleAt[sessionId] = nowMs }
    }

    /** Test seam. */
    internal fun resetForTest() {
        synchronized(lastModelVisibleAt) { lastModelVisibleAt.clear() }
    }
}
