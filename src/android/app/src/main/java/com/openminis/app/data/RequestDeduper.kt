package com.openminis.app.data

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session duplicate-submission guard, a simplified port of pi's
 * requestId-deduplicated Submission state machine (pi durable/types.ts:518-619).
 * pi gives every admitted input a host-provided `requestId` scoped to the
 * conversation and fails a second submission carrying the same id; our enqueue
 * path has no natural per-attempt id (message ids only exist after a row is
 * persisted), so the dedup key is derived: the prompt's content fingerprint.
 *
 * Semantics:
 *  - A [check] that finds the same fingerprint admitted within [windowMs]
 *    returns [Decision.Reject] instead of recording — the caller drops the
 *    duplicate enqueue and surfaces the rejected signal (queued→unanswered
 *    equivalent: the second copy never enters the transcript twice).
 *  - After the window expires the same content is admitted again (a real
 *    re-send, e.g. "run this again" is legitimate; the window only absorbs
 *    accidental double-taps and enqueue/drain replays).
 *  - Sessions are isolated: one session's fingerprints never affect another's.
 *  - Per-session capacity is capped ([maxEntriesPerSession]) FIFO-style so a
 *    pathological burst cannot grow the map without bound; the window prune on
 *    every check keeps steady-state cost O(capacity).
 *
 * Thread-safety: the per-session ring is only mutated inside
 * `synchronized(deque)`; sessions are created via [ConcurrentHashMap.computeIfAbsent]
 * so two threads racing on a fresh session share one ring. Recording happens
 * ONLY on [Decision.Accept], inside the same critical section as the lookup —
 * N threads checking the same content see exactly one Accept.
 *
 * Pure JVM, injectable clock (`nowMs` parameter) so tests never sleep.
 */
class RequestDeduper(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxEntriesPerSession: Int = DEFAULT_MAX_ENTRIES_PER_SESSION,
) {

    /** Outcome of one admission check. */
    sealed class Decision {
        /** Not seen within the window; recorded. */
        object Accept : Decision()

        /** Same content already admitted inside the window. */
        data class Reject(val existingAtMs: Long) : Decision()
    }

    private data class Entry(val fingerprint: String, val admittedAtMs: Long)

    private val sessions = ConcurrentHashMap<String, ArrayDeque<Entry>>()

    /**
     * Check-and-record one prospective submission of [content] to [sessionId].
     * Returns [Decision.Reject] when the same fingerprint was admitted within
     * [windowMs]; otherwise records the fingerprint and returns [Decision.Accept].
     */
    fun check(sessionId: String, content: String, nowMs: Long): Decision {
        val fingerprint = fingerprint(content)
        val ring = sessions.computeIfAbsent(sessionId) { ArrayDeque() }
        synchronized(ring) {
            val cutoff = nowMs - windowMs
            while (ring.isNotEmpty() && ring.first().admittedAtMs < cutoff) ring.removeFirst()
            for (entry in ring) {
                if (entry.fingerprint == fingerprint) return Decision.Reject(entry.admittedAtMs)
            }
            ring.addLast(Entry(fingerprint, nowMs))
            while (ring.size > maxEntriesPerSession) ring.removeFirst()
            return Decision.Accept
        }
    }

    /**
     * Check-only probe: true when [content] was already admitted within
     * [windowMs]. Records NOTHING — the caller decides when (and whether) the
     * fingerprint is committed via [record]. The split exists for the steering
     * offer path: there the record point is acceptance (not arrival), so a
     * check that ends in a failed offer must leave the fingerprint unrecorded
     * or the enqueue fallback would misjudge the very next submission.
     */
    fun seenRecently(sessionId: String, content: String, nowMs: Long): Boolean {
        val fingerprint = fingerprint(content)
        val ring = sessions[sessionId] ?: return false
        synchronized(ring) {
            val cutoff = nowMs - windowMs
            while (ring.isNotEmpty() && ring.first().admittedAtMs < cutoff) ring.removeFirst()
            return ring.any { it.fingerprint == fingerprint }
        }
    }

    /**
     * Record [content] as admitted at [nowMs] without a reject check — the
     * commit half of the [seenRecently]/[record] pair (steering-accept
     * bookkeeping). Same prune/eviction discipline as [check].
     */
    fun record(sessionId: String, content: String, nowMs: Long) {
        val fingerprint = fingerprint(content)
        val ring = sessions.computeIfAbsent(sessionId) { ArrayDeque() }
        synchronized(ring) {
            val cutoff = nowMs - windowMs
            while (ring.isNotEmpty() && ring.first().admittedAtMs < cutoff) ring.removeFirst()
            ring.addLast(Entry(fingerprint, nowMs))
            while (ring.size > maxEntriesPerSession) ring.removeFirst()
        }
    }

    /** Drop all bookkeeping for one session (session deleted / forked). */
    fun clear(sessionId: String) {
        sessions.remove(sessionId)
    }

    /** Drop all bookkeeping (test helper; production prefers [clear]). */
    fun clearAll() {
        sessions.clear()
    }

    companion object {
        /**
         * Window for the accidental-double-submit class. 3 s was too long: a
         * deliberate fast re-send ("run this again" — and zh-style chained
         * follow-ups that users fire within seconds) was silently swallowed
         * long after the double-tap range. 800 ms still absorbs both
         * double-taps and enqueue/drain replays (which land within one frame)
         * while letting real re-sends through; a hit now also surfaces a
         * short system-info notice instead of vanishing silently (P2-4).
         */
        const val DEFAULT_WINDOW_MS = 800L
        const val DEFAULT_MAX_ENTRIES_PER_SESSION = 16

        /**
         * Content fingerprint: SHA-256 of the prompt with edge whitespace
         * trimmed and internal whitespace collapsed, so the same prompt typed
         * with different spacing dedups but "1 + 1" and "11" (or any real
         * content edit) do not. Hex-encoded so it is log-safe.
         */
        fun fingerprint(content: String): String {
            val normalized = content.trim().replace(WHITESPACE, " ")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
