package com.openminis.app.agent

/**
 * [T-time-context-interval] Per-session throttle for the wall-clock note, and
 * the elapsed formatter that turns a timestamp into a usable clock.
 *
 * WHY this exists: the clock note used to require a KEYWORD match ("now",
 * "today", "几点", "weather", "remind", …). A long tool chain asked to "watch
 * the build for 20 minutes" carries none of those words, so the model had no
 * clock at all and answered from the timestamp of the conversation's first
 * message — it would say "just now" about something that happened an hour
 * earlier. The reference harness (dsh `time-context`) injects on an interval
 * instead, with a keyword fast path.
 *
 * Design:
 *  - keyword hit  → inject immediately (the old behaviour, unchanged);
 *  - otherwise    → inject at most once per [MIN_INTERVAL_MS] per session.
 *
 * Deliberately in-memory: a missed tick after a process restart costs one
 * extra timestamp, and persisting it would need another file per session for
 * no correctness gain. `resetForTest` is the only mutation seam.
 *
 * State is keyed by session and bounded, because a host that opens many
 * sessions (sub-agents, scheduled chats) must not accumulate entries forever.
 */
internal object TimeContextGate {

    /**
     * Minimum gap between two interval-driven injections.
     *
     * 10 minutes matches the reference default. Shorter intervals would stamp
     * the same hour onto every tool round (noise the model learns to skip);
     * longer ones let a genuinely long turn lose track of elapsed time.
     */
    const val MIN_INTERVAL_MS: Long = 10 * 60_000L

    /** Sessions tracked at once; oldest access is dropped first. */
    private const val MAX_SESSIONS = 64

    private val lastInjectedAt = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Long>,
            ): Boolean = size > MAX_SESSIONS
        },
    )

    /**
     * True when this request may carry an interval-driven clock note.
     *
     * A null/blank session id returns false: without a session there is no
     * "previous injection" to space against, and stamping an unknown-owner
     * request would put a timestamp on every message of every caller that does
     * not model sessions (JVM tests, one-shot helpers).
     */
    fun shouldInject(sessionId: String?, nowMs: Long): Boolean {
        if (sessionId.isNullOrBlank()) return false
        val last = synchronized(lastInjectedAt) { lastInjectedAt[sessionId] } ?: return true
        return nowMs - last >= MIN_INTERVAL_MS
    }

    /** Record that a clock note was injected for [sessionId] at [nowMs]. */
    fun markInjected(sessionId: String?, nowMs: Long) {
        if (sessionId.isNullOrBlank()) return
        synchronized(lastInjectedAt) { lastInjectedAt[sessionId] = nowMs }
    }

    /**
     * [T-time-context-interval] Compact elapsed label for the clock note.
     *
     * Formats the two units a model actually reasons about — minutes and hours
     * — and never invents precision it does not have:
     *  - under a minute → "Xs"
     *  - under an hour  → "Xm" (optionally "Xm Ys" when under 10 minutes, where
     *    seconds still change how the model reads "just now")
     *  - otherwise      → "Xh Ym", dropping the minutes when zero
     *
     * Negative input clamps to zero: a wall-clock jump backwards (NTP, manual
     * change, emulator skew) must not produce "-3h".
     */
    fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs.coerceAtLeast(0L)) / 1000L
        if (totalSeconds < 60) return "${totalSeconds}s"
        val totalMinutes = totalSeconds / 60
        if (totalMinutes < 10) return "${totalMinutes}m ${totalSeconds % 60}s"
        if (totalMinutes < 60) return "${totalMinutes}m"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
    }

    /** Test seam. */
    internal fun resetForTest() {
        synchronized(lastInjectedAt) { lastInjectedAt.clear() }
    }
}