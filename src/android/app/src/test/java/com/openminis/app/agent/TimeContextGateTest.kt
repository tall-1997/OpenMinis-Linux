package com.openminis.app.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-time-context-interval] The clock is no longer keyword-gated: a long tool
 * chain gets a timestamp at most once per interval, and elapsed time is what
 * makes that timestamp usable.
 */
class TimeContextGateTest {

    @Before
    fun setUp() {
        TimeContextGate.resetForTest()
    }

    @After
    fun tearDown() {
        TimeContextGate.resetForTest()
    }

    @Test
    fun theFirstRequestOfASessionMayCarryTheClock() {
        assertTrue(TimeContextGate.shouldInject("s1", 1_000L))
    }

    @Test
    fun aSecondRequestInsideTheIntervalIsSuppressed() {
        TimeContextGate.markInjected("s1", 1_000L)
        assertFalse(TimeContextGate.shouldInject("s1", 1_000L + TimeContextGate.MIN_INTERVAL_MS - 1))
        assertTrue(TimeContextGate.shouldInject("s1", 1_000L + TimeContextGate.MIN_INTERVAL_MS))
    }

    @Test
    fun sessionsDoNotShareTheThrottle() {
        TimeContextGate.markInjected("a", 1_000L)
        assertTrue(TimeContextGate.shouldInject("b", 1_000L))
    }

    @Test
    fun anUnknownSessionNeverStamps() {
        // No session means no baseline to space against; stamping would put a
        // timestamp on every message of a caller that does not model sessions.
        assertFalse(TimeContextGate.shouldInject(null, 1_000L))
        assertFalse(TimeContextGate.shouldInject("  ", 1_000L))
        TimeContextGate.markInjected(null, 1_000L)
        assertFalse(TimeContextGate.shouldInject(null, 10_000_000L))
    }

    @Test
    fun elapsedFormatsTheUnitsAModelReasonsAbout() {
        assertEquals("0s", TimeContextGate.formatElapsed(0L))
        assertEquals("45s", TimeContextGate.formatElapsed(45_000L))
        assertEquals("1m 5s", TimeContextGate.formatElapsed(65_000L))
        assertEquals("15m", TimeContextGate.formatElapsed(15 * 60_000L))
        assertEquals("1h", TimeContextGate.formatElapsed(60 * 60_000L))
        assertEquals("2h 30m", TimeContextGate.formatElapsed((2 * 60 + 30) * 60_000L))
    }

    @Test
    fun aBackwardClockJumpNeverProducesNegativeElapsed() {
        assertEquals("0s", TimeContextGate.formatElapsed(-5_000L))
    }

    @Test
    fun anIdenticalStampIsNotInjectedTwice() {
        // [T-time-context-snapshot] A keyword hit bypasses the interval gate, so
        // without the value check a second request in the same minute carried two
        // identical clock notes — noise the model learns to skip.
        assertFalse(TimeContextGate.alreadyStamped("s", "2026-11-1914:32"))
        TimeContextGate.markStamped("s", "2026-11-1914:32")
        assertTrue(TimeContextGate.alreadyStamped("s", "2026-11-1914:32"))
        assertFalse(TimeContextGate.alreadyStamped("s", "2026-11-1914:33"))
    }

    @Test
    fun theStampIsPerSessionAndClearedByReset() {
        TimeContextGate.markStamped("a", "2026-11-1914:32")
        assertFalse(TimeContextGate.alreadyStamped("b", "2026-11-1914:32"))
        TimeContextGate.resetForTest()
        assertFalse(TimeContextGate.alreadyStamped("a", "2026-11-1914:32"))
    }

    @Test
    fun aNullSessionNeverClaimsAStamp() {
        TimeContextGate.markStamped(null, "x")
        assertFalse(TimeContextGate.alreadyStamped(null, "x"))
        assertFalse(TimeContextGate.alreadyStamped("s", ""))
    }

    @Test
    fun theInjectionTableIsBounded() {
        // A host that opens many sessions must not accumulate entries forever.
        repeat(200) { i -> TimeContextGate.markInjected("s$i", 1_000L) }
        // The oldest entries are evicted, so a re-visit is eligible again.
        assertTrue(TimeContextGate.shouldInject("s0", 1_000L))
    }
}
