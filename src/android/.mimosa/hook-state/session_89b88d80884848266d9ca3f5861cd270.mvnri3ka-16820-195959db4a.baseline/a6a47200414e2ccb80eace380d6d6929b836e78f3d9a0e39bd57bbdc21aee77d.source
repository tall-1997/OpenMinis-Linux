package com.openminis.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-mcp-native] The first-class `mcp` tool used to ride shell_execute
 * (minis-mcp-cli), so its gating came from the shell classifier. Now that it
 * is its own tool name, the gate must treat it at least as strictly:
 * confirm in ASK, refuse in READ_ONLY / PLAN / DENY_ALL.
 */
class McpNativeGateTest {
    private lateinit var gate: SecurityGateImpl

    private val callArgs = """{"action":"call","server":"git","tool":"get_diff","arguments":{}}"""

    @Before
    fun setUp() {
        gate = SecurityGateImpl()
        gate.setPermissionMode(PermissionMode.ASK)
    }

    @Test
    fun mcpClassifiesAsNetReversible() {
        val cmd = gate.classify("mcp", callArgs)
        assertEquals("mcp", cmd.toolName)
        assertEquals(Capability.NET, cmd.capability)
        assertEquals(Reversibility.REVERSIBLE, cmd.reversibility)
    }

    @Test
    fun askModeRequiresConfirm() {
        val d = gate.decide(gate.classify("mcp", callArgs), PermissionMode.ASK)
        assertTrue("mcp should require confirmation in ASK: $d", d is Decision.NeedConfirm)
    }

    @Test
    fun readOnlyModeDenies() {
        val d = gate.decide(gate.classify("mcp", callArgs), PermissionMode.READ_ONLY)
        assertTrue("mcp must be denied in READ_ONLY: $d", d is Decision.Denied)
    }

    @Test
    fun planModeDenies() {
        val d = gate.decide(gate.classify("mcp", callArgs), PermissionMode.PLAN)
        assertTrue("mcp must be denied in PLAN: $d", d is Decision.Denied)
    }

    @Test
    fun denyAllModeDenies() {
        val d = gate.decide(gate.classify("mcp", callArgs), PermissionMode.DENY_ALL)
        assertTrue("mcp must be denied in DENY_ALL: $d", d is Decision.Denied)
    }

    @Test
    fun yoyoModeAllows() {
        val d = gate.decide(gate.classify("mcp", callArgs), PermissionMode.ALLOW_ALL)
        assertTrue("mcp should auto-run in YOYO like every non-fatal tool: $d", d is Decision.Allow)
    }
}
