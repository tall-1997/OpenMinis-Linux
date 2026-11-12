package com.openminis.app.goal

import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-goal-completion-verifier] [GoalManager.completeByModelVerified] — the
 * host-verified completion path for `update_goal(op=complete)`. The matrix:
 *
 * - no board (empty statuses) → accepted, the row moves to COMPLETE;
 * - board not done → rejected, the row is NOT touched (the Goal stays
 *   ACTIVE) and the rejection count for the session advances;
 * - rejections hit [GoalCompletionVerifier.MAX_REJECTIONS] → the call is
 *   accepted anyway (Forced) and the verifier's standing objection rides
 *   the receipt;
 * - a pass resets the count, so a later unfinished board starts over.
 *
 * Driven through the [GoalPersistence] seam with an in-memory fake — the
 * manager needs exactly four persistence operations, and JVM tests must not
 * drag Room in for them.
 */
class GoalManagerVerifiedCompletionTest {

    private class FakePersistence : GoalPersistence {
        val rows = mutableMapOf<String, SessionGoalEntity>()
        var failAll = false

        private fun guard() { if (failAll) throw IOException("disk on fire") }

        override suspend fun load(sessionId: String): SessionGoalEntity? {
            guard()
            return rows[sessionId]
        }

        override suspend fun insert(goal: SessionGoalEntity) {
            guard()
            rows[goal.sessionId] = goal
        }

        override suspend fun save(goal: SessionGoalEntity) {
            guard()
            rows[goal.sessionId] = goal
        }

        override suspend fun delete(sessionId: String) {
            guard()
            rows.remove(sessionId)
        }
    }

    private fun managerWithGoal(): Pair<GoalManager, FakePersistence> {
        val fake = FakePersistence()
        fake.rows["s1"] = GoalStateMachine.create("s1", "ship it", null, 1)
        return GoalManager(fake) to fake
    }

    private fun version(): Long = GoalManager.version.value

    @org.junit.Before
    fun resetCounters() {
        // The rejection counter lives on the companion (managers are built
        // ad-hoc everywhere), so tests share it — reset per test.
        GoalManager.resetCompletionRejections()
    }

    @Test
    fun `no board accepts completion and moves the row to complete`() = runTest {
        val (manager, fake) = managerWithGoal()
        val before = version()
        val outcome = manager.completeByModelVerified("s1", emptyList())
        assertTrue(outcome is GoalManager.VerifiedCompletion.Accepted)
        assertEquals(GoalStatus.COMPLETE, fake.rows.getValue("s1").status)
        assertEquals(before + 1, version())
    }

    @Test
    fun `unfinished board rejects completion and leaves the goal active`() = runTest {
        val (manager, fake) = managerWithGoal()
        val before = version()
        val outcome = manager.completeByModelVerified("s1", listOf("done", "pending"))
        assertTrue(outcome is GoalManager.VerifiedCompletion.Rejected)
        // The row was NOT touched: the Goal stays ACTIVE and nothing bumped.
        assertEquals(GoalStatus.ACTIVE, fake.rows.getValue("s1").status)
        assertEquals(before, version())
        assertTrue(outcome.verification.reason!!.contains("1/2 done"))
    }

    @Test
    fun `rejections are bounded then the model call is accepted anyway`() = runTest {
        val (manager, fake) = managerWithGoal()
        val board = listOf("done", "pending")
        // Rejections 1..MAX-1 keep the Goal active.
        for (i in 1 until GoalCompletionVerifier.MAX_REJECTIONS) {
            val outcome = manager.completeByModelVerified("s1", board)
            assertTrue(outcome is GoalManager.VerifiedCompletion.Rejected)
            assertEquals(GoalStatus.ACTIVE, fake.rows.getValue("s1").status)
        }
        // The MAX-th rejection respects the model's call: the row moves to
        // COMPLETE and the verifier's standing objection rides the receipt.
        val forced = manager.completeByModelVerified("s1", board)
        assertTrue(forced is GoalManager.VerifiedCompletion.Forced)
        assertEquals(GoalStatus.COMPLETE, fake.rows.getValue("s1").status)
        assertTrue((forced as GoalManager.VerifiedCompletion.Forced).forcedReason.contains("1/2 done"))
    }

    @Test
    fun `forced acceptance is per session`() = runTest {
        val fake = FakePersistence()
        fake.rows["a"] = GoalStateMachine.create("a", "task a", null, 1)
        fake.rows["b"] = GoalStateMachine.create("b", "task b", null, 1)
        val manager = GoalManager(fake)
        val board = listOf("pending")
        // MAX-1 rejections on session a must not spend session b's budget:
        // b still gets the full run of rejections before its own forced
        // acceptance.
        repeat(GoalCompletionVerifier.MAX_REJECTIONS - 1) {
            manager.completeByModelVerified("a", board)
        }
        // Session b starts over: two rejections keep the Goal active, the
        // third one respects the model's call.
        assertTrue(manager.completeByModelVerified("b", board) is GoalManager.VerifiedCompletion.Rejected)
        assertTrue(manager.completeByModelVerified("b", board) is GoalManager.VerifiedCompletion.Rejected)
        val forced = manager.completeByModelVerified("b", board)
        assertTrue(forced is GoalManager.VerifiedCompletion.Forced)
    }

    @Test
    fun `an accepted pass resets the rejection count`() = runTest {
        val (manager, fake) = managerWithGoal()
        // Spend MAX-1 rejections on an unfinished board...
        repeat(GoalCompletionVerifier.MAX_REJECTIONS - 1) {
            manager.completeByModelVerified("s1", listOf("pending"))
        }
        // ...then a clean pass (finished board) resets the count...
        val accepted = manager.completeByModelVerified("s1", listOf("done"))
        assertTrue(accepted is GoalManager.VerifiedCompletion.Accepted)
        assertEquals(GoalStatus.COMPLETE, fake.rows.getValue("s1").status)
        // ...so a NEW goal in the session gets the full run of rejections
        // again instead of forcing on its first one.
        fake.rows["s1"] = GoalStateMachine.create("s1", "next task", null, 2)
        val rejected = manager.completeByModelVerified("s1", listOf("pending"))
        assertTrue(rejected is GoalManager.VerifiedCompletion.Rejected)
        assertEquals(GoalStatus.ACTIVE, fake.rows.getValue("s1").status)
    }

    @Test
    fun `no goal in the session fails the completion`() = runTest {
        val (manager, _) = managerWithGoal()
        manager.clearByUser("s1")
        try {
            manager.completeByModelVerified("s1", emptyList())
            fail("completion without a goal must fail")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun `failed persistence propagates and bumps nothing`() = runTest {
        val (manager, fake) = managerWithGoal()
        val before = version()
        fake.failAll = true
        try {
            manager.completeByModelVerified("s1", emptyList())
            fail("persist failure must propagate")
        } catch (_: IOException) {
        }
        assertEquals(before, version())
    }

    @Test
    fun `rejected call keeps the row identical`() = runTest {
        val (manager, fake) = managerWithGoal()
        val before = fake.rows.getValue("s1")
        manager.completeByModelVerified("s1", listOf("failed"))
        // Rejection touches nothing — not even updatedAt.
        assertEquals(before, fake.rows.getValue("s1"))
        assertNotEquals(GoalStatus.COMPLETE, fake.rows.getValue("s1").status)
    }
}
