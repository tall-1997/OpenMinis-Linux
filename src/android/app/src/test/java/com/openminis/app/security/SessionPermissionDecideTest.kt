package com.openminis.app.security

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
    fun approvalAllowsReadonlyAndConfirmsWrite() {
        val read = gate.classify("file_read", """{"path":"/var/minis/workspace/a.txt"}""")
        assertTrue(gate.decide(read, PermissionMode.ASK) is Decision.Allow)

        val write = gate.classify("file_write", """{"path":"/var/minis/workspace/a.txt"}""")
        assertTrue(gate.decide(write, PermissionMode.ASK) is Decision.NeedConfirm)
    }
}
