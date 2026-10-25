package com.openminis.app.sandbox

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentOutputHeartbeatTest {
    @Test
    fun staysQuietBeforeTheInterval() {
        val beat = SilentOutputHeartbeat(intervalMs = 8_000)
        beat.start(0)
        assertNull(beat.tick(7_999))
    }

    @Test
    fun speaksAfterASilentInterval() {
        val beat = SilentOutputHeartbeat(intervalMs = 8_000)
        beat.start(0)
        val msg = beat.tick(8_000)
        assertTrue(msg!!.contains("8s"))
        assertTrue(msg.contains("没有新输出"))
    }

    @Test
    fun outputResetsTheSilenceWindow() {
        val beat = SilentOutputHeartbeat(intervalMs = 8_000)
        beat.start(0)
        beat.onOutput(7_000)
        assertNull(beat.tick(8_000))
        val msg = beat.tick(16_000)
        assertTrue(msg!!.contains("16s"))
    }
}
