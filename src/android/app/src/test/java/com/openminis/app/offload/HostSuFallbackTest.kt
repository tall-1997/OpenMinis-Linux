package com.openminis.app.offload

import com.openminis.app.sandbox.offload.SuCommand
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-host-su-fallback] Pure decision and argv-joining coverage. Execution
 * through a real `su` is host-dependent and untestable on a JVM CI runner;
 * the su-execution machinery itself is covered by SuCommandTest.
 */
class HostSuFallbackTest {

    @Before
    fun setUp() {
        // A CI runner has no host su: pin the probe so no test depends on
        // the machine it happens to run on.
        HostSuFallback.suProbe = { null }
    }

    @After
    fun tearDown() {
        HostSuFallback.suProbe = { SuCommand.findSuBinary() }
    }

    // ── lane decision ──────────────────────────────────────────────────────

    @Test
    fun liveBinderWinsOverEverything() {
        assertEquals(HostSuFallback.Lane.BINDER, HostSuFallback.lane(true, true))
        assertEquals(HostSuFallback.Lane.BINDER, HostSuFallback.lane(true, false))
    }

    @Test
    fun deadBinderWithSuFallsBackToHostSu() {
        assertEquals(HostSuFallback.Lane.HOST_SU, HostSuFallback.lane(false, true))
    }

    @Test
    fun deadBinderWithoutSuHasNoLane() {
        assertEquals(HostSuFallback.Lane.NONE, HostSuFallback.lane(false, false))
    }

    // ── vscreen guard decision ─────────────────────────────────────────────

    @Test
    fun readyNeverBlocks() {
        assertNull(HostSuFallback.guardDecision(ShizukuManager.State.READY, suAvailable = true))
        assertNull(HostSuFallback.guardDecision(ShizukuManager.State.READY, suAvailable = false))
    }

    @Test
    fun suEquippedDeviceNeverBlocksRegardlessOfShizukuState() {
        for (state in listOf(
            ShizukuManager.State.NOT_INSTALLED,
            ShizukuManager.State.NOT_RUNNING,
            ShizukuManager.State.NEED_PERMISSION,
        )) {
            assertNull("state=$state with su must pass", HostSuFallback.guardDecision(state, true))
        }
    }

    @Test
    fun eachShizukuStateBlocksWithItsOwnMessageWhenNoSu() {
        val notInstalled = HostSuFallback.guardDecision(ShizukuManager.State.NOT_INSTALLED, false)
        val notRunning = HostSuFallback.guardDecision(ShizukuManager.State.NOT_RUNNING, false)
        val needPermission = HostSuFallback.guardDecision(ShizukuManager.State.NEED_PERMISSION, false)

        assertTrue(notInstalled!!.contains("not installed"))
        assertTrue(notRunning!!.contains("not running"))
        assertTrue(needPermission!!.contains("not authorized"))
        // Every blocked message names the recovery path.
        listOf(notInstalled, notRunning, needPermission).forEach {
            assertTrue(it.contains("no host su was found"))
            assertTrue(it.contains("Magisk/KernelSU"))
        }
    }

    // ── argv joining (the `su -c` quoting contract) ────────────────────────

    @Test
    fun plainTokensJoinWithSpaces() {
        assertEquals("screencap -d 101", HostSuFallback.joinCommand(arrayOf("screencap", "-d", "101")))
    }

    @Test
    fun tokenWithSpaceStaysOneArgvSlot() {
        assertEquals("pm 'disable-user --user 0' com.x", HostSuFallback.joinCommand(
            arrayOf("pm", "disable-user --user 0", "com.x"),
        ))
    }

    @Test
    fun embeddedSingleQuoteIsPosixEscaped() {
        assertEquals("echo 'it'\\''s'", HostSuFallback.joinCommand(arrayOf("echo", "it's")))
    }

    @Test
    fun emptyTokenIsQuotedEmptyString() {
        assertEquals("echo ''", HostSuFallback.joinCommand(arrayOf("echo", "")))
    }

    // ── execution when su is absent ────────────────────────────────────────

    @Test
    fun runWithoutSuReports126WithHonestMessage() {
        val r = HostSuFallback.run(arrayOf("id"), timeoutMs = 1_000)
        assertEquals(126, r.exitCode)
        assertEquals("", r.stdout)
        assertTrue(r.stderr.contains("host su binary not found"))
    }
}
