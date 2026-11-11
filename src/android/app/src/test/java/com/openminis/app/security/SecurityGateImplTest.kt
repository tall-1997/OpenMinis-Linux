package com.openminis.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecurityGateImplTest {
    private lateinit var gate: SecurityGateImpl

    @Before
    fun setUp() {
        gate = SecurityGateImpl()
        gate.setPermissionMode(PermissionMode.ASK)
    }

    @Test
    fun denyAllBlocksRead() {
        gate.setPermissionMode(PermissionMode.DENY_ALL)
        val d = gate.decide(gate.classify("file_read", """{"path":"/tmp/a"}"""), PermissionMode.DENY_ALL)
        assertTrue(d is Decision.Denied)
    }

    @Test
    fun askAllowsReadOnly() {
        val d = gate.decide(gate.classify("file_read", """{"path":"/tmp/a"}"""), PermissionMode.ASK)
        assertTrue(d is Decision.Allow)
    }

    @Test
    fun browserReadActionsAutoAllowButSideEffectsConfirm() {
        for (action in listOf("screenshot", "get_text", "scroll", "get_page_info", "find_elements", "hover", "get_readable", "get_backbone", "list_tabs", "wait_for_dom_stable")) {
            val decision = gate.decide(gate.classify("browser_use", "{\"action\":\"$action\"}"), PermissionMode.ASK)
            assertTrue("$action should auto allow: $decision", decision is Decision.Allow)
        }
        for (action in listOf("navigate", "click", "type", "execute_js", "get_cookies", "set_cookies", "fetch", "unknown")) {
            val decision = gate.decide(gate.classify("browser_use", "{\"action\":\"$action\"}"), PermissionMode.ASK)
            assertTrue("$action should require confirmation: $decision", decision is Decision.NeedConfirm)
        }
    }

    @Test
    fun askConfirmsFileWrite() {
        val d = gate.decide(gate.classify("file_write", """{"path":"/tmp/a","content":"x"}"""), PermissionMode.ASK)
        assertTrue(d is Decision.NeedConfirm)
    }

    @Test
    fun readOnlyBlocksShell() {
        gate.setPermissionMode(PermissionMode.READ_ONLY)
        val d = gate.decide(
            gate.classify("shell_execute", """{"command":"rm -rf /tmp/foo"}"""),
            PermissionMode.READ_ONLY,
        )
        assertTrue(d is Decision.Denied)
    }

                        @Test
    fun auditHashChainDetectsTamper() {
        val cmd = gate.classify("file_read", """{"path":"/tmp/a"}""")
        gate.audit(cmd, Decision.Allow("ok"), "ok")
        gate.audit(cmd, Decision.Allow("ok2"), "ok2")
        assertTrue(gate.verifyAuditChain().ok)
        gate.tamperDecision(0, "TAMPERED")
        val v = gate.verifyAuditChain()
        assertFalse(v.ok)
        assertEquals(0, v.brokenAt)
    }

    // ── [T-audit-room-persist] auditSink contract ───────────────────────────

    @Test
    fun auditSinkReceivesTheSameEntryTheTrailRecorded() {
        val seen = mutableListOf<AuditEntry>()
        gate.auditSink = { seen.add(it) }
        val cmd = gate.classify("file_read", """{"path":"/tmp/a"}""")
        gate.audit(cmd, Decision.Allow("ok"), "ok")
        assertEquals(1, seen.size)
        // Same hash ⇒ the persisted copy joins the same sha256 chain the
        // in-memory trail verified, so Room stays a mirror, not a fork.
        assertEquals(gate.getAuditTrail().single().hash, seen.single().hash)
        assertEquals("Allow: ok", seen.single().decision)
    }

    @Test
    fun auditSinkExceptionNeverBreaksAudit() {
        gate.auditSink = { error("sink exploded") }
        val cmd = gate.classify("file_read", """{"path":"/tmp/a"}""")
        // Must not throw: persistence is fire-and-forget by contract.
        gate.audit(cmd, Decision.Allow("ok"), "ok")
        assertTrue(gate.verifyAuditChain().ok)
    }

    @Test
    fun nullAuditSinkKeepsMemoryOnlyBehaviour() {
        val cmd = gate.classify("file_read", """{"path":"/tmp/a"}""")
        gate.audit(cmd, Decision.Allow("ok"), "ok")
        assertEquals(1, gate.getAuditTrail().size)
        assertTrue(gate.verifyAuditChain().ok)
    }

    @Test
    fun authorityFenceDeniesOutsideWorkspace() {
        gate.setAuthorityProfile(PermissionProfile.workspace("/var/minis/workspace"))
        val asked = gate.decide(
            gate.classify("file_write", """{"path":"/etc/passwd","content":"x"}"""),
            PermissionMode.ASK,
        )
        assertTrue(asked is Decision.Denied)
        val allowed = gate.decide(
            gate.classify("file_write", """{"path":"/etc/passwd","content":"x"}"""),
            PermissionMode.ALLOW_ALL,
        )
        assertTrue(allowed is Decision.Allow)
    }

    @Test
    fun sessionAllowAllUsesSameModeAsGlobal() {
        assertEquals(
            PermissionMode.ALLOW_ALL,
            effectivePermissionMode(PermissionMode.ASK, sessionAllowAll = true),
        )
        assertEquals(
            PermissionMode.DENY_ALL,
            effectivePermissionMode(PermissionMode.DENY_ALL, sessionAllowAll = false),
        )
    }

    @Test
    fun aliasesCanonicalize() {
        assertEquals("shell_execute", ToolAliases.canonical("shell_exec"))
        assertEquals("web_fetch", ToolAliases.canonical("fetch_url"))
        assertEquals("memory_write", ToolAliases.canonical("save_memory"))
        assertEquals("execute_code", ToolAliases.canonical("code_exec"))
    }

    @Test
    fun tokenizeRespectsQuotes() {
        assertEquals(listOf("echo", "a b"), tokenizeCommand("echo 'a b'"))
        assertEquals(listOf("rm", "-rf", "/"), tokenizeCommand("rm -rf /"))
    }
}
