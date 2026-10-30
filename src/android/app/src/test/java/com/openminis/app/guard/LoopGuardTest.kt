package com.openminis.app.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopGuardTest {

    @Test
    fun repeatRemindsAt3And6() {
        val g = LoopGuard()
        assertNull(g.record("a", true, Outcome.Effective))
        assertNull(g.record("a", true, Outcome.Effective))
        assertEquals(Signal.Repeat to 3, g.record("a", true, Outcome.Effective))
        assertNull(g.record("a", true, Outcome.Effective))
        assertNull(g.record("a", true, Outcome.Effective))
        assertEquals(Signal.Repeat to 6, g.record("a", true, Outcome.Effective))
        assertNull(g.stopped)
    }

    @Test
    fun repeatRefusesTheWouldBe12thBeforeItRuns() {
        val g = LoopGuard()
        assertNull(g.check("a")) // nothing recorded yet
        repeat(11) { g.record("a", true, Outcome.Effective) }
        assertEquals(Signal.Repeat, g.check("a")) // this call would be the 12th identical one
        assertEquals(Signal.Repeat, g.stopped)
        val text = LoopGuard.message(Signal.Repeat, Signal.Repeat.stopAt, stop = true)
        assertTrue(text.contains("Refused"))
    }

    @Test
    fun repeatOnlyCountsTheLast16Calls() {
        val g = LoopGuard()
        g.record("a", true, Outcome.Effective)
        repeat(15) { i -> g.record("b$i", true, Outcome.Effective) }
        // the original "a" has fallen out of the 16-entry counting window
        assertNull(g.record("a", true, Outcome.Effective))
        assertNull(g.check("a"))
    }

    @Test
    fun signatureStoreKeepsAtMost64EntriesDroppingTheOldest() {
        val g = LoopGuard()
        repeat(70) { i -> g.record("s$i", true, Outcome.Effective) }
        assertEquals(LoopGuard.STORAGE_LIMIT, g.storedSignatures().size)
        assertEquals("s6", g.storedSignatures().first()) // s0..s5 were evicted
    }

    @Test
    fun noEffectRemindsAt3_6_9AndStopsAt12() {
        val g = LoopGuard()
        assertNull(g.record("a1", true, Outcome.NoEffect))
        assertNull(g.record("a2", true, Outcome.NoEffect))
        assertEquals(Signal.NoEffect to 3, g.record("a3", true, Outcome.NoEffect))
        assertNull(g.record("a4", true, Outcome.NoEffect))
        assertNull(g.record("a5", true, Outcome.NoEffect))
        assertEquals(Signal.NoEffect to 6, g.record("a6", true, Outcome.NoEffect))
        assertNull(g.record("a7", true, Outcome.NoEffect))
        assertNull(g.record("a8", true, Outcome.NoEffect))
        assertEquals(Signal.NoEffect to 9, g.record("a9", true, Outcome.NoEffect))
        assertNull(g.record("a10", true, Outcome.NoEffect))
        assertNull(g.record("a11", true, Outcome.NoEffect))
        assertEquals(Signal.NoEffect to 12, g.record("a12", true, Outcome.NoEffect))
        assertEquals(Signal.NoEffect, g.stopped)
        assertEquals(Signal.NoEffect, g.check(null)) // stopped sessions refuse everything
    }

    @Test
    fun rereadRemindsAt5And8AndStopsAt12() {
        val g = LoopGuard()
        repeat(4) { assertNull(g.record(null, true, Outcome.ReadUnchanged)) }
        assertEquals(Signal.Reread to 5, g.record(null, true, Outcome.ReadUnchanged))
        repeat(2) { assertNull(g.record(null, true, Outcome.ReadUnchanged)) }
        assertEquals(Signal.Reread to 8, g.record(null, true, Outcome.ReadUnchanged))
        repeat(3) { assertNull(g.record(null, true, Outcome.ReadUnchanged)) }
        assertEquals(Signal.Reread to 12, g.record(null, true, Outcome.ReadUnchanged))
        assertEquals(Signal.Reread, g.stopped)
    }

    @Test
    fun failureRemindsAt2And4AndStopsAt8() {
        val g = LoopGuard()
        assertNull(g.record("f1", false, Outcome.None))
        assertEquals(Signal.Failure to 2, g.record("f2", false, Outcome.None))
        assertNull(g.record("f3", false, Outcome.None))
        assertEquals(Signal.Failure to 4, g.record("f4", false, Outcome.None))
        assertNull(g.record("f5", false, Outcome.None))
        assertNull(g.record("f6", false, Outcome.None))
        assertNull(g.record("f7", false, Outcome.None))
        assertEquals(Signal.Failure to 8, g.record("f8", false, Outcome.None))
        assertEquals(Signal.Failure, g.stopped)
    }

    @Test
    fun effectiveActionBreaksNoEffectAndRereadStreaks() {
        val g = LoopGuard()
        repeat(2) { i -> g.record("a$i", true, Outcome.NoEffect) }
        g.record("eff", true, Outcome.Effective)
        // the streak restarts: reaching only count 1 and 2 again must not remind
        assertNull(g.record("b0", true, Outcome.NoEffect))
        assertNull(g.record("b1", true, Outcome.NoEffect))
        g.record(null, true, Outcome.ReadUnchanged)
        g.record("eff2", true, Outcome.Effective)
        assertNull(g.record(null, true, Outcome.ReadUnchanged))
        assertNull(g.record(null, true, Outcome.ReadUnchanged))
    }

    @Test
    fun successBreaksTheFailureStreak() {
        val g = LoopGuard()
        g.record("f1", false, Outcome.None)
        assertEquals(Signal.Failure to 2, g.record("f2", false, Outcome.None))
        g.record("ok", true, Outcome.Effective)
        assertNull(g.record("f3", false, Outcome.None)) // back to a count of 1
    }

    @Test
    fun identicalFailuresAlsoFeedTheRepeatLadder() {
        val g = LoopGuard()
        g.record("same", false, Outcome.None)
        assertEquals(Signal.Failure to 2, g.record("same", false, Outcome.None))
        // the 3rd identical call: Repeat rung 3 fires (failure rung 4 is not reached yet)
        assertEquals(Signal.Repeat to 3, g.record("same", false, Outcome.None))
    }

    @Test
    fun resetClearsEveryCounterAndTheStop() {
        val g = LoopGuard()
        repeat(11) { g.record("a", true, Outcome.Effective) }
        assertEquals(Signal.Repeat, g.check("a"))
        g.reset()
        assertNull(g.stopped)
        assertNull(g.check("a"))
        assertNull(g.record("a", true, Outcome.Effective))
        assertNull(g.record("a", true, Outcome.Effective))
        assertEquals(Signal.Repeat to 3, g.record("a", true, Outcome.Effective)) // counting starts at zero again
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
