package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalSameToolTest {
    @Test
    fun sameToolIsAutoApprovedAfterSessionAllow() {
        ApprovalGate.resetSessionAllowAll()
        ApprovalGate.bindSession("s1")
        val first = ApprovalGate.requestApproval("file_write", "write a", mustPrompt = false)
        assertTrue(first.isNotEmpty())
        ApprovalGate.allowToolForSession("file_write")
        val second = ApprovalGate.requestApproval("file_write", "write b", mustPrompt = false)
        assertEquals("", second)
        val other = ApprovalGate.requestApproval("su_exec", "su", mustPrompt = false)
        assertTrue(other.isNotEmpty())
        val fatal = ApprovalGate.requestApproval("shell_exec", "rm -rf /", mustPrompt = true)
        assertTrue(fatal.isNotEmpty())
        ApprovalGate.bindSession("s2")
        assertFalse(ApprovalGate.isToolAllowedForSession("file_write"))
        ApprovalGate.resetSessionAllowAll()
    }
}
