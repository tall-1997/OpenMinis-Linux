package com.openminis.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

    /**
     * [T-approval-gate-ce-rethrow] The user pressing Stop cancels the
     * coroutine parked in [ApprovalGate.waitFor]. That cancellation is NOT an
     * answer: swallowing it used to fabricate a "denied" decision and persist
     * it for a request the user never saw. The request must survive, still
     * pending, and the cancellation must propagate out of waitFor.
     */
    @Test fun cancellingWaitForDoesNotDenyThePendingApproval() = runBlocking {
        val id = ApprovalGate.requestApproval("stop-sid", "file_write", "cancel me while waiting")
        val settled = CompletableDeferred<Boolean>()
        val job = launch { settled.complete(ApprovalGate.waitFor(id, "stop-sid")) }
        repeat(5) { yield() } // let the child park in flow.first { it != null }
        assertTrue("waitFor should still be parked", settled.isActive)

        job.cancelAndJoin()

        // No fabricated decision: the request stays filed and pending.
        assertTrue("a cancelled wait must not resolve the request", settled.isActive)
        assertTrue("the request must survive cancellation", ApprovalGate.detailOf(id) != null)
        assertEquals(1, ApprovalGate.pendingCount())
        ApprovalGate.deny(id, "stop-sid") // the user answers later; clean up
    }
}
