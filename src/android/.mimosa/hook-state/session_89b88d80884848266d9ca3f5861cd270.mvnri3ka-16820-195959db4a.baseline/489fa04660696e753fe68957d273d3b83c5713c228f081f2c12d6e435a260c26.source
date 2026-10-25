package com.openminis.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPermissionDecideTest {
    private val gate = SecurityGateImpl()

    @Test
    fun yoyoAllowsOrdinaryWriteAndOnlyConfirmsFatal() {
        val write = gate.classify("file_write", """{"path":"/var/minis/workspace/a.txt"}""")
        assertTrue(gate.decide(write, PermissionMode.ALLOW_ALL) is Decision.Allow)

        val fatal = gate.classify("shell_exec", """{"command":"rm -rf /"}""")
        val d = gate.decide(fatal, PermissionMode.ALLOW_ALL)
        assertTrue(d is Decision.NeedConfirm)
        assertTrue((d as Decision.NeedConfirm).mustPrompt)
    }

    @Test
    fun upgradedAndUnknownModesDefaultToAsk() {
        org.junit.Assert.assertEquals(PermissionMode.ASK, PermissionMode.sessionDefault(null))
        org.junit.Assert.assertEquals(PermissionMode.ASK, PermissionMode.sessionDefault(""))
        org.junit.Assert.assertEquals(PermissionMode.ASK, PermissionMode.sessionDefault("READ_ONLY"))
        org.junit.Assert.assertEquals(PermissionMode.ASK, PermissionMode.sessionDefault("PLAN"))
        org.junit.Assert.assertEquals(PermissionMode.ASK, PermissionMode.sessionDefault("DENY_ALL"))
        assertEquals(PermissionMode.ASK, PermissionMode.sessionDefault("ALLOW_ALL"))
        assertEquals(PermissionMode.ALLOW_ALL, PermissionMode.sessionDefault("YOYO"))
    }

    @Test
    fun approvalAllowsReadonlyAndConfirmsWrite() {
        val read = gate.classify("file_read", """{"path":"/var/minis/workspace/a.txt"}""")
        assertTrue(gate.decide(read, PermissionMode.ASK) is Decision.Allow)

        val write = gate.classify("file_write", """{"path":"/var/minis/workspace/a.txt"}""")
        assertTrue(gate.decide(write, PermissionMode.ASK) is Decision.NeedConfirm)
    }
}
