package com.openminis.app.harness.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-duplicate-toolcall-replay] Contract for the dispatch-side
 * idempotence guard that refuses re-emitted ToolCallComplete events.
 */
class ToolReplayGuardTest {

    @Test
    fun `first sighting dispatches`() {
        val guard = ToolReplayGuard()
        assertFalse(guard.registerAndCheckReplay("call_a", "shell_execute", """{"command":"ls"}"""))
    }

    @Test
    fun `identical re-emission is a replay`() {
        val guard = ToolReplayGuard()
        guard.registerAndCheckReplay("call_a", "shell_execute", """{"command":"ls"}""")
        assertTrue(guard.registerAndCheckReplay("call_a", "shell_execute", """{"command":"ls"}"""))
    }

    @Test
    fun `same id different args is the gateway parallel case not a replay`() {
        val guard = ToolReplayGuard()
        guard.registerAndCheckReplay("call_a", "shell_execute", """{"command":"ls"}""")
        assertFalse(guard.registerAndCheckReplay("call_a", "shell_execute", """{"command":"pwd"}"""))
    }

    @Test
    fun `same id different tool is not a replay`() {
        val guard = ToolReplayGuard()
        guard.registerAndCheckReplay("call_a", "shell_execute", """{"command":"ls"}""")
        assertFalse(guard.registerAndCheckReplay("call_a", "file_read", """{"command":"ls"}"""))
    }

    @Test
    fun `same args different id is a legitimate repeat and dispatches`() {
        val guard = ToolReplayGuard()
        guard.registerAndCheckReplay("call_a", "shell_execute", """{"command":"ls"}""")
        assertFalse(guard.registerAndCheckReplay("call_b", "shell_execute", """{"command":"ls"}"""))
        // and repeating call_b is again a replay
        assertTrue(guard.registerAndCheckReplay("call_b", "shell_execute", """{"command":"ls"}"""))
    }

    @Test
    fun `blank raw id never replays`() {
        val guard = ToolReplayGuard()
        assertFalse(guard.registerAndCheckReplay("", "shell_execute", """{"command":"ls"}"""))
        assertFalse(guard.registerAndCheckReplay("", "shell_execute", """{"command":"ls"}"""))
    }

    @Test
    fun `reset clears sightings so a fresh attempt re-dispatches`() {
        val guard = ToolReplayGuard()
        guard.registerAndCheckReplay("call_a", "shell_execute", """{"command":"ls"}""")
        guard.reset()
        assertEquals(0, guard.observedRawIds())
        assertFalse(guard.registerAndCheckReplay("call_a", "shell_execute", """{"command":"ls"}"""))
    }

    @Test
    fun `fingerprint distinguishes whitespace-different args`() {
        // Serialized args differing in whitespace are different keys — the
        // guard is byte-exact on purpose (a replay re-emits identical bytes).
        val guard = ToolReplayGuard()
        guard.registerAndCheckReplay("call_a", "shell_execute", """{"command":"ls"}""")
        assertFalse(guard.registerAndCheckReplay("call_a", "shell_execute", """{ "command": "ls" }"""))
    }
}
