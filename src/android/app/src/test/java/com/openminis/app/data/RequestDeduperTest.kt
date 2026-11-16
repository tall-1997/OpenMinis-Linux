package com.openminis.app.data

import com.openminis.app.data.RequestDeduper.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the per-session duplicate-submission guard (pi durable/types.ts
 * Submission requestId dedup, simplified to content fingerprints). Covers:
 * window rejection/expiry, session isolation, normalization, capacity
 * eviction, clear paths, and concurrent check-and-record atomicity.
 */
class RequestDeduperTest {

    private fun deduper(
        windowMs: Long = RequestDeduper.DEFAULT_WINDOW_MS,
        capacity: Int = RequestDeduper.DEFAULT_MAX_ENTRIES_PER_SESSION,
    ) = RequestDeduper(windowMs = windowMs, maxEntriesPerSession = capacity)

    @Test
    fun `same fingerprint inside the window is rejected`() {
        val d = deduper(windowMs = 3_000)
        assertEquals(Decision.Accept, d.check("s1", "hello world", nowMs = 1_000))
        val second = d.check("s1", "hello world", nowMs = 2_500)
        assertTrue(second is Decision.Reject)
        assertEquals(1_000L, (second as Decision.Reject).existingAtMs)
    }

    @Test
    fun `same fingerprint after the window is admitted again`() {
        val d = deduper(windowMs = 3_000)
        assertEquals(Decision.Accept, d.check("s1", "retry me", nowMs = 1_000))
        // Strictly past the window: the exact now == admittedAt + window edge is
        // pinned by `boundary is exclusive - window edge admits` (kept while
        // admittedAt >= now - window, so the edge itself still rejects).
        assertEquals(Decision.Accept, d.check("s1", "retry me", nowMs = 4_001))
    }

    @Test
    fun `boundary is exclusive - window edge admits`() {
        // admittedAtMs=1000, window=3000 -> cutoff=1000; entry kept when == cutoff, so 4000 still rejects.
        val d = deduper(windowMs = 3_000)
        assertEquals(Decision.Accept, d.check("s1", "edge", nowMs = 1_000))
        assertTrue(d.check("s1", "edge", nowMs = 4_000) is Decision.Reject)
        assertEquals(Decision.Accept, d.check("s1", "edge", nowMs = 4_001))
    }

    @Test
    fun `different content is never deduped`() {
        val d = deduper()
        assertEquals(Decision.Accept, d.check("s1", "run tests", nowMs = 1_000))
        assertEquals(Decision.Accept, d.check("s1", "run tests twice", nowMs = 1_100))
    }

    @Test
    fun `whitespace-normalized content dedups`() {
        val d = deduper()
        assertEquals(Decision.Accept, d.check("s1", "fix  the\nbug", nowMs = 1_000))
        assertTrue(d.check("s1", "  fix the bug  ", nowMs = 1_500) is Decision.Reject)
    }

    @Test
    fun `sessions are isolated`() {
        val d = deduper()
        assertEquals(Decision.Accept, d.check("s1", "same text", nowMs = 1_000))
        assertEquals(Decision.Accept, d.check("s2", "same text", nowMs = 1_100))
        assertTrue(d.check("s1", "same text", nowMs = 1_200) is Decision.Reject)
        assertTrue(d.check("s2", "same text", nowMs = 1_300) is Decision.Reject)
    }

    @Test
    fun `clear drops one session only`() {
        val d = deduper()
        assertEquals(Decision.Accept, d.check("s1", "text", nowMs = 1_000))
        assertEquals(Decision.Accept, d.check("s2", "text", nowMs = 1_000))
        d.clear("s1")
        assertEquals(Decision.Accept, d.check("s1", "text", nowMs = 1_100))
        assertTrue(d.check("s2", "text", nowMs = 1_100) is Decision.Reject)
    }

    @Test
    fun `clearAll drops every session`() {
        val d = deduper()
        d.check("s1", "text", nowMs = 1_000)
        d.check("s2", "text", nowMs = 1_000)
        d.clearAll()
        assertEquals(Decision.Accept, d.check("s1", "text", nowMs = 1_100))
        assertEquals(Decision.Accept, d.check("s2", "text", nowMs = 1_100))
    }

    @Test
    fun `capacity evicts oldest entries`() {
        val d = deduper(windowMs = 60_000, capacity = 3)
        for (i in 1..3) d.check("s1", "msg-$i", nowMs = 1_000L * i)
        // Ring is at capacity; "msg-1" is the oldest and gets evicted by msg-4.
        assertEquals(Decision.Accept, d.check("s1", "msg-4", nowMs = 4_500))
        // Evicted content is admitted again within the window.
        assertEquals(Decision.Accept, d.check("s1", "msg-1", nowMs = 4_600))
        // Still-present content keeps deduping.
        assertTrue(d.check("s1", "msg-4", nowMs = 4_700) is Decision.Reject)
    }

    @Test
    fun `concurrent checks of one content admit exactly once`() {
        val d = deduper()
        val threads = 8
        val startGate = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val accepts = AtomicInteger()
        val rejects = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                startGate.await()
                when (d.check("s1", "burst", nowMs = 1_000)) {
                    is Decision.Accept -> accepts.incrementAndGet()
                    is Decision.Reject -> rejects.incrementAndGet()
                }
                done.countDown()
            }
        }
        startGate.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, accepts.get())
        assertEquals(threads - 1, rejects.get())
    }

    @Test
    fun `fingerprint is stable and content-sensitive`() {
        assertEquals(RequestDeduper.fingerprint("abc"), RequestDeduper.fingerprint("abc"))
        assertTrue(RequestDeduper.fingerprint("abc") != RequestDeduper.fingerprint("abd"))
        // Edge whitespace is normalized by design (RequestDeduper.fingerprint doc,
        // pinned by `whitespace-normalized content dedups`): the trimmed variant
        // hashes identically; only a real content edit changes the fingerprint.
        assertEquals(RequestDeduper.fingerprint("abc"), RequestDeduper.fingerprint(" abc "))
    }

    @Test
    fun `default window absorbs a double-tap but admits a deliberate re-send`() {
        // P2-4: the shipped window is 800 ms. The old 3 s window silently
        // swallowed deliberate fast re-sends ("run this again", chained
        // follow-ups) long after the double-tap range; 800 ms still absorbs
        // both double-taps and enqueue/drain replays while letting real
        // re-sends through after under a second.
        val d = deduper() // DEFAULT_WINDOW_MS
        assertEquals(Decision.Accept, d.check("s1", "again", nowMs = 1_000))
        // 500 ms later: still inside the 800 ms window — a double-tap.
        assertTrue(d.check("s1", "again", nowMs = 1_500) is Decision.Reject)
        // Just past the window: a deliberate re-send is admitted again.
        assertEquals(Decision.Accept, d.check("s1", "again", nowMs = 1_801))
    }

    // -- [T-agent-steering-dedupe] seenRecently/record: the check-only /
    //    record split behind the steering offer path (RequestDedupeGate wires
    //    these; the data component owns the window semantics).

    @Test
    fun `seenRecently probes without recording`() {
        val d = deduper()
        d.check("s1", "probe", nowMs = 1_000)
        // Probe sees the admitted entry…
        assertTrue(d.seenRecently("s1", "probe", nowMs = 1_100))
        // …but records nothing: after the window a fresh offer is admitted
        // again even though the probe kept hitting inside it.
        assertTrue(d.seenRecently("s1", "probe", nowMs = 1_700))
        assertFalse(d.seenRecently("s1", "probe", nowMs = 1_801))
    }

    @Test
    fun `probe on an unknown session is false`() {
        val d = deduper()
        assertFalse(d.seenRecently("no-session", "anything", nowMs = 1_000))
    }

    @Test
    fun `record commits without a check and the next probe hits`() {
        val d = deduper()
        d.record("s1", "steered", nowMs = 1_000)
        assertTrue(d.seenRecently("s1", "steered", nowMs = 1_100))
        // The committed entry dedupes like any admitted fingerprint.
        assertTrue(d.check("s1", "steered", nowMs = 1_200) is Decision.Reject)
    }

    @Test
    fun `record and check share one ring per session`() {
        // The steering commit and the enqueue check must see ONE window: a
        // text steered (recorded) blocks the enqueue check inside the window,
        // and the check's prune expires the steering entry too.
        val d = deduper()
        d.record("s1", "cross channel", nowMs = 1_000)
        assertTrue(d.check("s1", "cross channel", nowMs = 1_500) is Decision.Reject)
        assertFalse(d.seenRecently("s1", "cross channel", nowMs = 1_801))
    }
}
