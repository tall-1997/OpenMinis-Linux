package com.openminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#358] A timed-out command must not leave the session's shell channel in
 * the per-session registry: the next command has to build a fresh one instead
 * of reusing a shell whose pending callback / stdin writer may still be wedged.
 */
class ShellChannelRecoveryTest {

    @Test
    fun timeoutExitDiscardsTheChannel() {
        assertTrue(ShellChannelRecovery.shouldDiscardChannel(124, "[Command timed out after 180s; guest process group killed]"))
        assertTrue(ShellChannelRecovery.shouldDiscardChannel(124, ""))
    }

    @Test
    fun ordinaryFailuresKeepTheChannel() {
        // 126 = refused by policy, 127 = command not found, 1 = normal failure:
        // all of these are valid shells that must be reused (env/cwd survive).
        assertFalse(ShellChannelRecovery.shouldDiscardChannel(126, "refused"))
        assertFalse(ShellChannelRecovery.shouldDiscardChannel(127, "not found"))
        assertFalse(ShellChannelRecovery.shouldDiscardChannel(1, "boom"))
        assertFalse(ShellChannelRecovery.shouldDiscardChannel(0, "ok"))
    }

    @Test
    fun resetFieldListNamesEveryStuckStateField() {
        val fields = ShellChannelRecovery.RESET_FIELDS
        for (name in listOf(
            "pendingCallback", "stdinWriter", "process", "isStarting",
            "sourcedOffloadSignature", "lastExitCode",
        )) {
            assertTrue("reset list must name $name", fields.contains(name))
        }
    }
}
