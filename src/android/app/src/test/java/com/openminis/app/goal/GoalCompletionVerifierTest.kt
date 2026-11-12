package com.openminis.app.goal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-goal-completion-verifier] The host verifier's decision matrix for
 * `update_goal(op=complete)`. The plan board is the one structured signal
 * the host can read; the objective itself is free text with no parsed
 * acceptance criteria, so a session with no board passes on the strength of
 * the model's call (the honest boundary).
 */
class GoalCompletionVerifierTest {

    @Test fun `no board maintains the status quo and allows completion`() {
        val v = GoalCompletionVerifier.verifyCompletion(emptyList())
        assertTrue(v.allowed)
        assertNull(v.reason)
    }

    @Test fun `board with every step done and none failed allows completion`() {
        val v = GoalCompletionVerifier.verifyCompletion(listOf("done", "done", "done"))
        assertTrue(v.allowed)
        assertNull(v.reason)
    }

    @Test fun `board with a pending step rejects completion and names the progress`() {
        val v = GoalCompletionVerifier.verifyCompletion(listOf("done", "pending", "pending"))
        assertFalse(v.allowed)
        assertTrue(v.reason!!.contains("1/3 done"))
        assertTrue(v.reason.contains("pending") || v.reason.contains("do not re-report"))
    }

    @Test fun `board with a failed step rejects completion and names the failure`() {
        val v = GoalCompletionVerifier.verifyCompletion(listOf("done", "failed"))
        assertFalse(v.allowed)
        assertTrue(v.reason!!.contains("1/2 done"))
        assertTrue(v.reason.contains("1 failed"))
    }

    @Test fun `a single all-failed board rejects completion`() {
        val v = GoalCompletionVerifier.verifyCompletion(listOf("failed", "failed"))
        assertFalse(v.allowed)
        assertTrue(v.reason!!.contains("0/2 done"))
        assertTrue(v.reason.contains("2 failed"))
    }

    @Test fun `rejection limit matches the blocked confirmations`() {
        // Same number as the blocked-condition confirmations: three host
        // verdicts in a row mean the verifier and the model disagree
        // persistently, and dead-locking the Goal helps nobody.
        org.junit.Assert.assertEquals(
            GoalStateMachine.BLOCKED_CONFIRMATIONS,
            GoalCompletionVerifier.MAX_REJECTIONS,
        )
    }

    @Test fun `forced acceptance reason records the standing objection`() {
        val r = GoalCompletionVerifier.forcedAcceptanceReason(listOf("done", "pending", "failed"))
        assertTrue(r.contains("3 attempts"))
        assertTrue(r.contains("1/3 done"))
        assertTrue(r.contains("1 failed"))
        // Empty board never forces (it never rejects either).
        org.junit.Assert.assertEquals("", GoalCompletionVerifier.forcedAcceptanceReason(emptyList()))
    }
}
