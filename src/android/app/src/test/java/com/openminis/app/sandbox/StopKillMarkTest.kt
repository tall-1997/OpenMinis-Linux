package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [stop=kill] Pure contracts behind the user Stop → kill flow:
 *
 *  1. The guest shell bash is identified by its exact boot argv so the
 *     command-tree kill spares it (the shell must survive a Stop with its
 *     cwd/env/packages).
 *  2. The killed command's result is head-labelled "[killed by user]" only
 *     for signal-range exits, so a command that finished normally in the
 *     stop race is never mislabelled and 124 stays the timeout's code.
 */
class StopKillMarkTest {

    // ── guest shell bash identification ─────────────────────────────────

    @Test
    fun `boot bash argv matches exactly`() {
        assertTrue(ProcessTree.isGuestShellBash("/bin/bash\u0000--noprofile\u0000--norc\u0000"))
        assertTrue(ProcessTree.isGuestShellBash("/bin/bash\u0000--noprofile\u0000--norc"))
    }

    @Test
    fun `nested or differently-invoked bash never matches`() {
        // A user command's own bash: different argv, must be killed.
        assertFalse(ProcessTree.isGuestShellBash("/bin/bash\u0000-c\u0000echo hi\u0000"))
        assertFalse(ProcessTree.isGuestShellBash("/bin/bash\u0000--norc\u0000--noprofile\u0000"))
        assertFalse(ProcessTree.isGuestShellBash("bash\u0000--noprofile\u0000--norc\u0000"))
        assertFalse(ProcessTree.isGuestShellBash("/bin/bash\u0000--noprofile\u0000--norc\u0000extra\u0000"))
    }

    @Test
    fun `null empty and zombie cmdlines never match`() {
        assertFalse(ProcessTree.isGuestShellBash(null))
        assertFalse(ProcessTree.isGuestShellBash(""))
        assertFalse(ProcessTree.isGuestShellBash("\u0000\u0000"))
    }

    @Test
    fun `cmdline args split drops NULs and empties`() {
        assertEquals(
            listOf("/bin/bash", "--noprofile", "--norc"),
            ProcessTree.parseCmdlineArgs("/bin/bash\u0000--noprofile\u0000--norc\u0000"),
        )
        assertEquals(emptyList<String>(), ProcessTree.parseCmdlineArgs(""))
    }

    // ── killed verdict ───────────────────────────────────────────────────

    @Test
    fun `signal-range exits count as killed`() {
        assertTrue(ShellStopKill.isSignalExit(-1)) // shell death completion
        assertTrue(ShellStopKill.isSignalExit(137)) // guest SIGKILL
        assertTrue(ShellStopKill.isSignalExit(143)) // guest SIGTERM
        assertTrue(ShellStopKill.isSignalExit(130)) // guest SIGINT
    }

    @Test
    fun `normal and timeout exits never count as killed`() {
        assertFalse(ShellStopKill.isSignalExit(0))
        assertFalse(ShellStopKill.isSignalExit(1))
        assertFalse(ShellStopKill.isSignalExit(124)) // the timeout's own code
        assertFalse(ShellStopKill.isSignalExit(127))
    }

    @Test
    fun `marker is one head line above the captured output`() {
        assertEquals(
            "[killed by user]\nsome partial output",
            ShellStopKill.markedOutput(true, "some partial output"),
        )
    }

    @Test
    fun `unmarked output passes through unchanged`() {
        val output = "completed normally"
        assertEquals(output, ShellStopKill.markedOutput(false, output))
    }
}
