package com.openminis.app.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-vscreen-vd] The callback/state contract behind the virtual
 * display: RUNNING until the platform says otherwise, and a stopped display
 * must never be reported as capturable.
 */
class VirtualDisplayRuntimeTest {

    @Test
    fun `state starts running and records every callback`() {
        val state = VirtualDisplayState()
        assertEquals(VdLifecycle.RUNNING, state.lifecycle)
        assertEquals(0L, state.lastEventAtMs)

        state.mark(VdLifecycle.PAUSED)
        assertEquals(VdLifecycle.PAUSED, state.lifecycle)
        assertTrue("callback must timestamp the transition", state.lastEventAtMs > 0L)

        state.mark(VdLifecycle.STOPPED)
        assertEquals(VdLifecycle.STOPPED, state.lifecycle)
    }

    @Test
    fun `capture is blocked only when the platform stopped the display`() {
        assertNull(VirtualDisplayRuntime.captureBlockedReason(VdLifecycle.RUNNING))
        // A paused display still holds its last frame, so a screenshot is fine.
        assertNull(VirtualDisplayRuntime.captureBlockedReason(VdLifecycle.PAUSED))

        val stopped = VirtualDisplayRuntime.captureBlockedReason(VdLifecycle.STOPPED)
        assertNotNull(stopped)
        assertTrue(
            "the message must tell the model to recreate the display: $stopped",
            stopped!!.contains("destroy", ignoreCase = true) &&
                stopped.contains("create", ignoreCase = true),
        )
    }

    @Test
    fun `a healthy display carries no note`() {
        assertNull(VirtualDisplayRuntime.stateNote(VdLifecycle.RUNNING))
    }

    @Test
    fun `paused and stopped displays explain themselves`() {
        val paused = VirtualDisplayRuntime.stateNote(VdLifecycle.PAUSED)
        val stopped = VirtualDisplayRuntime.stateNote(VdLifecycle.STOPPED)
        assertNotNull(paused)
        assertNotNull(stopped)
        assertTrue(paused!!.contains("resume", ignoreCase = true))
        assertTrue(stopped!!.contains("recreate", ignoreCase = true))
    }

    // ── root-assisted geometry (wm output parsing) ────────────────────────

    @Test
    fun `wm size prefers the user override over the panel`() {
        val out = "Physical size: 1272x2800\nOverride size: 1080x2400"
        assertEquals(1080 to 2400, VirtualDisplayRuntime.parseWmSize(out))
    }

    @Test
    fun `wm size falls back to the physical panel`() {
        assertEquals(1272 to 2800, VirtualDisplayRuntime.parseWmSize("Physical size: 1272x2800"))
    }

    @Test
    fun `wm density prefers the override`() {
        val out = "Physical density: 560\nOverride density: 480"
        assertEquals(480, VirtualDisplayRuntime.parseWmDensity(out))
        assertEquals(560, VirtualDisplayRuntime.parseWmDensity("Physical density: 560"))
    }

    @Test
    fun `unparseable wm output yields null`() {
        assertNull(VirtualDisplayRuntime.parseWmSize(""))
        assertNull(VirtualDisplayRuntime.parseWmSize("WxH: not-a-size"))
        assertNull(VirtualDisplayRuntime.parseWmDensity("density: unknown"))
    }
}
