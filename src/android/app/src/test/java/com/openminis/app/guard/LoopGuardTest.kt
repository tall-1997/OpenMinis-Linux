package com.openminis.app.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopGuardTest {

    /**
     * [T-loop-guard-all-rungs] `record` now reports every ladder that crossed a
     * new rung. These cases advance exactly one ladder at a time, so the
     * single-hit view keeps them readable; multi-signal reporting is covered by
     * `LoopGuardStopPolicyTest`.
     */
    private fun LoopGuard.next(sig: String?, ok: Boolean, outcome: Outcome): Pair<Signal, Int>? =
        record(sig, ok, outcome).firstOrNull()

    @Test
    fun repeatRemindsAt3And6() {
        val g = LoopGuard()
        assertNull(g.next("a", true, Outcome.Effective))
        assertNull(g.next("a", true, Outcome.Effective))
        assertEquals(Signal.Repeat to 3, g.next("a", true, Outcome.Effective))
        assertNull(g.next("a", true, Outcome.Effective))
        assertNull(g.next("a", true, Outcome.Effective))
        assertEquals(Signal.Repeat to 6, g.next("a", true, Outcome.Effective))
        assertNull(g.stopped)
    }

    @Test
    fun repeatRefusesTheWouldBe12thBeforeItRuns() {
        val g = LoopGuard()
        assertNull(g.check("a")) // nothing recorded yet
        repeat(11) { g.next("a", true, Outcome.Effective) }
        assertEquals(Signal.Repeat, g.check("a")) // this call would be the 12th identical one
        assertEquals(Signal.Repeat, g.stopped)
        val text = LoopGuard.message(Signal.Repeat, Signal.Repeat.stopAt, stop = true)
        assertTrue(text.contains("Refused"))
    }

    @Test
    fun repeatOnlyCountsTheLast16Calls() {
        val g = LoopGuard()
        g.next("a", true, Outcome.Effective)
        repeat(15) { i -> g.next("b$i", true, Outcome.Effective) }
        // the original "a" has fallen out of the 16-entry counting window
        assertNull(g.next("a", true, Outcome.Effective))
        assertNull(g.check("a"))
    }

    @Test
    fun signatureStoreKeepsAtMost64EntriesDroppingTheOldest() {
        val g = LoopGuard()
        repeat(70) { i -> g.next("s$i", true, Outcome.Effective) }
        assertEquals(LoopGuard.STORAGE_LIMIT, g.storedSignatures().size)
        assertEquals("s6", g.storedSignatures().first()) // s0..s5 were evicted
    }

    @Test
    fun noEffectRemindsAt3_6_9AndStopsAt12() {
        val g = LoopGuard()
        assertNull(g.next("a1", true, Outcome.NoEffect))
        assertNull(g.next("a2", true, Outcome.NoEffect))
        assertEquals(Signal.NoEffect to 3, g.next("a3", true, Outcome.NoEffect))
        assertNull(g.next("a4", true, Outcome.NoEffect))
        assertNull(g.next("a5", true, Outcome.NoEffect))
        assertEquals(Signal.NoEffect to 6, g.next("a6", true, Outcome.NoEffect))
        assertNull(g.next("a7", true, Outcome.NoEffect))
        assertNull(g.next("a8", true, Outcome.NoEffect))
        assertEquals(Signal.NoEffect to 9, g.next("a9", true, Outcome.NoEffect))
        assertNull(g.next("a10", true, Outcome.NoEffect))
        assertNull(g.next("a11", true, Outcome.NoEffect))
        assertEquals(Signal.NoEffect to 12, g.next("a12", true, Outcome.NoEffect))
        assertEquals(Signal.NoEffect, g.stopped)
        assertEquals(Signal.NoEffect, g.check(null)) // stopped sessions refuse everything
    }

    @Test
    fun rereadRemindsAt5And8AndStopsAt12() {
        val g = LoopGuard()
        repeat(4) { assertNull(g.next(null, true, Outcome.ReadUnchanged)) }
        assertEquals(Signal.Reread to 5, g.next(null, true, Outcome.ReadUnchanged))
        repeat(2) { assertNull(g.next(null, true, Outcome.ReadUnchanged)) }
        assertEquals(Signal.Reread to 8, g.next(null, true, Outcome.ReadUnchanged))
        repeat(3) { assertNull(g.next(null, true, Outcome.ReadUnchanged)) }
        assertEquals(Signal.Reread to 12, g.next(null, true, Outcome.ReadUnchanged))
        assertEquals(Signal.Reread, g.stopped)
    }

    @Test
    fun failureRemindsAt2And4AndStopsAt8() {
        val g = LoopGuard()
        assertNull(g.next("f1", false, Outcome.None))
        assertEquals(Signal.Failure to 2, g.next("f2", false, Outcome.None))
        assertNull(g.next("f3", false, Outcome.None))
        assertEquals(Signal.Failure to 4, g.next("f4", false, Outcome.None))
        assertNull(g.next("f5", false, Outcome.None))
        assertNull(g.next("f6", false, Outcome.None))
        assertNull(g.next("f7", false, Outcome.None))
        assertEquals(Signal.Failure to 8, g.next("f8", false, Outcome.None))
        assertEquals(Signal.Failure, g.stopped)
    }

    @Test
    fun effectiveActionBreaksNoEffectAndRereadStreaks() {
        val g = LoopGuard()
        repeat(2) { i -> g.next("a$i", true, Outcome.NoEffect) }
        g.next("eff", true, Outcome.Effective)
        // the streak restarts: reaching only count 1 and 2 again must not remind
        assertNull(g.next("b0", true, Outcome.NoEffect))
        assertNull(g.next("b1", true, Outcome.NoEffect))
        g.next(null, true, Outcome.ReadUnchanged)
        g.next("eff2", true, Outcome.Effective)
        assertNull(g.next(null, true, Outcome.ReadUnchanged))
        assertNull(g.next(null, true, Outcome.ReadUnchanged))
    }

    @Test
    fun successBreaksTheFailureStreak() {
        val g = LoopGuard()
        g.next("f1", false, Outcome.None)
        assertEquals(Signal.Failure to 2, g.next("f2", false, Outcome.None))
        g.next("ok", true, Outcome.Effective)
        assertNull(g.next("f3", false, Outcome.None)) // back to a count of 1
    }

    @Test
    fun identicalFailuresAlsoFeedTheRepeatLadder() {
        val g = LoopGuard()
        g.next("same", false, Outcome.None)
        assertEquals(Signal.Failure to 2, g.next("same", false, Outcome.None))
        // the 3rd identical call: Repeat rung 3 fires (failure rung 4 is not reached yet)
        assertEquals(Signal.Repeat to 3, g.next("same", false, Outcome.None))
    }

    @Test
    fun resetClearsEveryCounterAndTheStop() {
        val g = LoopGuard()
        repeat(11) { g.next("a", true, Outcome.Effective) }
        assertEquals(Signal.Repeat, g.check("a"))
        g.reset()
        assertNull(g.stopped)
        assertNull(g.check("a"))
        assertNull(g.next("a", true, Outcome.Effective))
        assertNull(g.next("a", true, Outcome.Effective))
        assertEquals(Signal.Repeat to 3, g.next("a", true, Outcome.Effective)) // counting starts at zero again
    }

    @Test
    fun messagesSpeakEnglishAndSeparateNoticeFromRefusal() {
        val notice = LoopGuard.message(Signal.Failure, 2, stop = false)
        assertTrue(notice.contains("Notice"))
        assertTrue(notice.contains("2 calls in a row failed"))
        val stop = LoopGuard.message(Signal.Failure, 8, stop = true)
        assertTrue(stop.contains("Refused"))
        assertTrue(stop.contains("8 calls in a row failed"))
        assertTrue(stop.contains("report the situation to the user"))
    }
}
