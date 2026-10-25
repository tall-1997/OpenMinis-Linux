package com.openminis.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-ssh-backend] Gate treatment of ssh_exec: every action is NET (it talks
 * to a remote machine or manages credentials for one); mutations are
 * IRREVERSIBLE, read-only probes stay REVERSIBLE. Mode matrix mirrors
 * McpNativeGateTest: confirm in ASK, refuse mutations in READ_ONLY/PLAN,
 * refuse everything in DENY_ALL, auto-run in ALLOW_ALL. The authority fence
 * must cover ssh_exec — a net-RESTRICTED sub-agent cannot reach out.
 */
class SecurityGateSshTest {

    private lateinit var gate: SecurityGateImpl

    @Before
    fun setUp() {
        gate = SecurityGateImpl()
    }

    private fun classify(action: String) =
        gate.classify("ssh_exec", """{"action":"$action","host":"web","command":"uptime","remote_path":"/tmp/x"}""")

    // ------------------------------------------------------------ classify

    @Test
    fun execIsNetIrreversible() {
        val c = classify("exec")
        assertEquals(Capability.NET, c.capability)
        assertEquals(Reversibility.IRREVERSIBLE, c.reversibility)
    }

    @Test
    fun mutationsAreIrreversible() {
        for (a in listOf("exec", "put", "add_host", "remove_host", "forget_host_key")) {
            assertEquals(a, Capability.NET, classify(a).capability)
            assertEquals(a, Reversibility.IRREVERSIBLE, classify(a).reversibility)
        }
    }

    @Test
    fun getIsNetReversible() {
        val c = classify("get")
        assertEquals(Capability.NET, c.capability)
        assertEquals(Reversibility.REVERSIBLE, c.reversibility)
    }

    @Test
    fun readOnlyProbesAreNetReversible() {
        for (a in listOf("ls", "test", "list_hosts")) {
            assertEquals(a, Capability.NET, classify(a).capability)
            assertEquals(a, Reversibility.REVERSIBLE, classify(a).reversibility)
        }
    }

    // ------------------------------------------------------------ modes

    @Test
    fun askModeConfirmsExec() {
        gate.setPermissionMode(PermissionMode.ASK)
        val d = gate.decide(classify("exec"), PermissionMode.ASK)
        assertTrue("remote exec must confirm in ASK: $d", d is Decision.NeedConfirm)
    }

    @Test
    fun readOnlyDeniesMutationsButNotProbes() {
        gate.setPermissionMode(PermissionMode.READ_ONLY)
        assertTrue(gate.decide(classify("exec"), PermissionMode.READ_ONLY) is Decision.Denied)
        assertTrue(gate.decide(classify("put"), PermissionMode.READ_ONLY) is Decision.Denied)
        assertTrue("get writes a local file — blocked in READ_ONLY", gate.decide(classify("get"), PermissionMode.READ_ONLY) is Decision.Denied)
        assertTrue(gate.decide(classify("add_host"), PermissionMode.READ_ONLY) is Decision.Denied)
        // probes pass the mode gate, then confirm at [8]
        assertTrue(gate.decide(classify("ls"), PermissionMode.READ_ONLY) is Decision.NeedConfirm)
    }

    @Test
    fun planDeniesExec() {
        gate.setPermissionMode(PermissionMode.PLAN)
        assertTrue(gate.decide(classify("exec"), PermissionMode.PLAN) is Decision.Denied)
        assertTrue(gate.decide(classify("ls"), PermissionMode.PLAN) is Decision.NeedConfirm)
    }

    @Test
    fun denyAllDeniesEverything() {
        gate.setPermissionMode(PermissionMode.DENY_ALL)
        assertTrue(gate.decide(classify("exec"), PermissionMode.DENY_ALL) is Decision.Denied)
        assertTrue(gate.decide(classify("ls"), PermissionMode.DENY_ALL) is Decision.Denied)
    }

    @Test
    fun allowAllRuns() {
        gate.setPermissionMode(PermissionMode.ALLOW_ALL)
        assertTrue(gate.decide(classify("exec"), PermissionMode.ALLOW_ALL) is Decision.Allow)
    }

    // ------------------------------------------------------------ fence

    @Test
    fun authorityFenceBlocksNetRestricted() {
        gate.setPermissionMode(PermissionMode.ASK)
        gate.setAuthorityProfile(PermissionProfile.readOnlyWorkspace("/var/minis/workspace"))
        val d = gate.decide(classify("ls"), PermissionMode.ASK)
        assertTrue("net-RESTRICTED fence must cover ssh_exec: $d", d is Decision.Denied)
        gate.setAuthorityProfile(null)
        assertTrue(gate.decide(classify("ls"), PermissionMode.ASK) is Decision.NeedConfirm)
    }

    // ------------------------------------------------------------ surface

    @Test
    fun previewNamesActionHostCommand() {
        val p = gate.preview(classify("exec"))
        assertTrue(p.contains("exec web: uptime"))
    }

    @Test
    fun aliasesResolveToSshExec() {
        assertEquals("ssh_exec", ToolAliases.canonical("ssh"))
        assertEquals("ssh_exec", ToolAliases.canonical("SFTP"))
        assertEquals("ssh_exec", ToolAliases.canonical("scp"))
        assertEquals("ssh_exec", ToolAliases.canonical("remote_exec"))
        assertEquals("ssh_exec", ToolAliases.canonical("ssh_exec"))
    }
}
