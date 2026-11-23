package com.openminis.app.service

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-approval-session-mismatch-log] The resolver contract the chat UI must
 * honour.
 *
 * `ApprovalGate.waitFor(id, callerSessionId)` answers `false` BOTH when the
 * user denied and when the caller resolved against a different session id than
 * the request was filed under. The caller (SecurityGateHolder) turns `false`
 * into "User REJECTED the approval …", so a session-id mismatch reads to the
 * model — and to us — like a deliberate refusal.
 *
 * The safe pattern (used by ChatViewModel.approvePendingTool) is to resolve
 * with `detailOf(id)?.sessionId`, i.e. the session the REQUEST belongs to, not
 * whatever session the UI currently thinks it is showing. These tests pin both
 * halves of that contract so a future refactor cannot quietly reintroduce the
 * mismatch.
 */
class ApprovalGateSessionRoutingTest {

    @After
    fun cleanup() {
        ApprovalGate.cleanupAll()
    }

    @Test
    fun approvingWithTheFiledSessionWakesTheWaiter() = runBlocking {
        val id = ApprovalGate.requestApproval("session-1", "shell_execute", "pwd")
        assertEquals("session-1", ApprovalGate.detailOf(id)?.sessionId)

        val waiter = async { ApprovalGate.waitFor(id, "session-1") }
        // Let the waiter reach its suspension point first: waitFor looks the
        // pending channel up by id, and approve() removes that entry, so a
        // pre-subscription approve would log "no pending channel" and hang.
        yield()
        ApprovalGate.approve(id, "session-1")
        assertTrue("approve on the filed session must wake the waiter", waiter.await())
        assertNull(ApprovalGate.detailOf(id))
    }

    @Test
    fun approvingWithAForeignSessionLeavesTheRequestPending() = runBlocking {
        val id = ApprovalGate.requestApproval("session-owned", "shell_execute", "pwd")

        // A resolver that guessed the wrong session: silently dropped by design,
        // which is why callers must read detailOf(id).sessionId first.
        ApprovalGate.approve(id, "session-other")

        assertEquals(
            "a foreign session id must not resolve someone else's request",
            setOf(id),
            ApprovalGate.pendingApprovals("session-owned").value.keys,
        )
        assertNotNull(ApprovalGate.detailOf(id))

        // Clean up through the owning session so @After has nothing to do.
        ApprovalGate.deny(id, "session-owned")
        assertNull(ApprovalGate.detailOf(id))
    }

    @Test
    fun waitForReportsFalseOnSessionMismatchInsteadOfHanging() = runBlocking {
        val id = ApprovalGate.requestApproval("session-owned", "shell_execute", "pwd")
        assertFalse(
            "a mismatched caller must fail fast, not block until timeout",
            ApprovalGate.waitFor(id, "session-other"),
        )
        ApprovalGate.deny(id, "session-owned")
    }
}
