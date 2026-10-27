package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-logic tests for [StreamRateTracker] and [outputTokensPerSecond]. [T-android-usage-tok-s] */
class StreamRateTrackerTest {

    @Test
    fun `empty window yields null and stays reset`() {
        val t = StreamRateTracker()
        assertNull(t.takeDurationMs())
        assertNull(t.takeDurationMs())
    }

    @Test
    fun `window is first to last delta`() {
        val t = StreamRateTracker()
        t.onTextDelta(1_000L)
        t.onTextDelta(1_500L)
        t.onTextDelta(2_250L)
        assertEquals(1_250L, t.takeDurationMs())
    }

    @Test
    fun `single delta yields null window and resets`() {
        val t = StreamRateTracker()
        t.onTextDelta(5_000L)
        assertNull(t.takeDurationMs())
        t.onTextDelta(6_000L)
        assertNull(t.takeDurationMs())
    }

    @Test
    fun `take resets for the next persisted row`() {
        val t = StreamRateTracker()
        t.onTextDelta(1_000L)
        t.onTextDelta(2_000L)
        assertEquals(1_000L, t.takeDurationMs())
        t.onTextDelta(10_000L)
        t.onTextDelta(11_500L)
        assertEquals(1_500L, t.takeDurationMs())
    }

    @Test
    fun `zero-length window is reported as null`() {
        val t = StreamRateTracker()
        t.onTextDelta(1_000L)
        t.onTextDelta(1_000L)
        assertNull(t.takeDurationMs())
    }

    // ── outputTokensPerSecond guards ─────────────────────────────────────

    @Test
    fun `speed computed for meaningful windows`() {
        assertEquals(45, outputTokensPerSecond(90, 2_000L))
        assertEquals(100, outputTokensPerSecond(50, 500L))
    }

    @Test
    fun `tiny windows and tiny replies read as null`() {
        assertNull(outputTokensPerSecond(90, 499L))
        assertNull(outputTokensPerSecond(4, 2_000L))
        assertNull(outputTokensPerSecond(0, 2_000L))
    }
}
