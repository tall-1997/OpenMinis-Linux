package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [GH#188] TTFB budget phases: the server must not be charged for the time the
 * client spent uploading the request body.
 */
class StreamTtfbPhaseTest {

    private val ms = 1_000_000L

    @Test
    fun `before upload completes the upload phase counts from call start`() {
        val (phase, elapsed) = StreamTtfb.classifyPhase(
            startedAtNanos = 0L,
            uploadDoneAtNanos = 0L,
            nowNanos = 25_000L * ms,
            hasWatch = true,
        )
        assertEquals("upload", phase)
        assertEquals(25_000L, elapsed)
    }

    @Test
    fun `after upload completes the ttfb phase counts from requestBodyEnd`() {
        // A 24s upload followed by 5s of server silence: 29s since call start,
        // but only 5s of the server's TTFB budget has been spent.
        val (phase, elapsed) = StreamTtfb.classifyPhase(
            startedAtNanos = 0L,
            uploadDoneAtNanos = 24_000L * ms,
            nowNanos = 29_000L * ms,
            hasWatch = true,
        )
        assertEquals("ttfb", phase)
        assertEquals(5_000L, elapsed)
    }

    @Test
    fun `legacy caller without a watch keeps one clock from call start`() {
        val (phase, elapsed) = StreamTtfb.classifyPhase(
            startedAtNanos = 0L,
            uploadDoneAtNanos = 24_000L * ms,
            nowNanos = 29_000L * ms,
            hasWatch = false,
        )
        assertEquals("ttfb", phase)
        assertEquals(29_000L, elapsed)
    }
}
