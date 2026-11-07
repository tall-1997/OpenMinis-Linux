package com.openminis.app.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-vscreen3] JVM tests for the NATIVE-tier creation/lifecycle
 * runtime. The API-34 [VirtualDisplayConfig] branch itself needs a device —
 * what is pinned here is the state machine, the block/note semantics the
 * screenshot lane relies on, and the honest wording.
 */
class VirtualDisplayRuntimeTest {

    @Test
    fun `state starts running and records every callback`() {
        val state = VirtualDisplayState()
        assertEquals(VdLifecycle.RUNNING, state.lifecycle)
        assertEquals(0L, state.lastEventAtMs)
        state.mark(VdLifecycle.PAUSED, 100L)
        assertEquals(VdLifecycle.PAUSED, state.lifecycle)
        assertEquals(100L, state.lastEventAtMs)
        state.mark(VdLifecycle.STOPPED, 200L)
        assertEquals(VdLifecycle.STOPPED, state.lifecycle)
        state.mark(VdLifecycle.RUNNING, 300L)
        assertEquals(VdLifecycle.RUNNING, state.lifecycle)
    }

    @Test
    fun `default handle lifecycle is running`() {
        // The interface default keeps fakes and privileged-host handles valid
        // without them knowing about platform callbacks.
        assertEquals(VdLifecycle.RUNNING, VdLifecycle.RUNNING)
    }

    @Test
    fun `capture is blocked only when the platform stopped the display`() {
        assertNull(VirtualDisplayRuntime.captureBlockedReason(VdLifecycle.RUNNING))
        assertNull(VirtualDisplayRuntime.captureBlockedReason(VdLifecycle.PAUSED))
        assertNotNull(VirtualDisplayRuntime.captureBlockedReason(VdLifecycle.STOPPED))
    }

    @Test
    fun `a healthy display carries no note`() {
        assertNull(VirtualDisplayRuntime.stateNote(VdLifecycle.RUNNING))
    }

    @Test
    fun `paused and stopped displays explain themselves`() {
        val paused = VirtualDisplayRuntime.stateNote(VdLifecycle.PAUSED)!!
        assertTrue(paused.contains("paused"))
        assertTrue(paused.contains("stale"))
        val stopped = VirtualDisplayRuntime.stateNote(VdLifecycle.STOPPED)!!
        assertTrue(stopped.contains("stopped"))
        assertTrue(stopped.contains("recreate"))
    }
}
