package com.openminis.app.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class ApprovalSessionIsolationTest {
    @After fun cleanup() { ApprovalGate.cleanupAll() }

    @Test fun allowAllAndToolAllowListDoNotCrossSessions() {
        ApprovalGate.enableSessionAllowAll("a")
        ApprovalGate.allowToolForSession("file_write", "b")
        assertTrue(ApprovalGate.isSessionAllowAll("a"))
        assertFalse(ApprovalGate.isSessionAllowAll("b"))
        assertTrue(ApprovalGate.isToolAllowedForSession("file_write", "a"))
        assertTrue(ApprovalGate.isToolAllowedForSession("file_write", "b"))
        assertFalse(ApprovalGate.isToolAllowedForSession("shell_exec", "b"))
    }

    @Test fun pendingRequestsAndResolutionsStayWithTheirSession() = runBlocking {
        val a = ApprovalGate.requestApproval("a", "file_write", "a", false)
        val b = ApprovalGate.requestApproval("b", "file_write", "b")
        assertEquals(setOf(a), ApprovalGate.pendingApprovals("a").value.keys)
        assertEquals(setOf(b), ApprovalGate.pendingApprovals("b").value.keys)
        ApprovalGate.approve(a, "b")
        assertTrue(ApprovalGate.pendingApprovals("a").value.containsKey(a))
        ApprovalGate.approve(a, "a")
        assertFalse(ApprovalGate.pendingApprovals("a").value.containsKey(a))
        ApprovalGate.deny(b, "b")
    }

    @Test fun parallelSessionsCanRequestIndependently() = runBlocking {
        val ids = coroutineScope {
            listOf("a", "b").map { sid -> async { ApprovalGate.requestApproval(sid, "file_write", sid) } }.awaitAll()
        }
        assertEquals(setOf(ids[0]), ApprovalGate.pendingApprovals("a").value.keys)
        assertEquals(setOf(ids[1]), ApprovalGate.pendingApprovals("b").value.keys)
    }
}
