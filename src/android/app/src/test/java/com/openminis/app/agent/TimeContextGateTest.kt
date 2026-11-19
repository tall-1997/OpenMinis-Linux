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
    fun theInjectionTableIsBounded() {
        // A host that opens many sessions must not accumulate entries forever.
        repeat(200) { i -> TimeContextGate.markInjected("s$i", 1_000L) }
        // The oldest entries are evicted, so a re-visit is eligible again.
        assertTrue(TimeContextGate.shouldInject("s0", 1_000L))
    }
}
