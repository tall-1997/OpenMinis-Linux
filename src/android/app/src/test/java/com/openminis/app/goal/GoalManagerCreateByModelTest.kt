package com.openminis.app.goal

import com.openminis.app.data.db.GoalOrigin
import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-goal-created-with-plan] `createByModel` is the model-side counterpart of
 * `createByUser`: creating the task board must also record the objective, so
 * the Goal exists by the time the turn ends.
 *
 * [T-goal-model-origin] Its contract: the SAME objective is idempotent (a
 * re-emitted board or a retried tool call must not fail the turn), a DIFFERENT
 * objective supersedes the model's own previous Goal (a finished task must not
 * keep steering the next one), and an objective the human wrote is never
 * overwritten.
 */
class GoalManagerCreateByModelTest {

    private class FakePersistence : GoalPersistence {
        val rows = mutableMapOf<String, SessionGoalEntity>()
        var inserts = 0
        var deletes = 0
        override suspend fun load(sessionId: String): SessionGoalEntity? = rows[sessionId]
        override suspend fun insert(goal: SessionGoalEntity) {
            inserts += 1
            rows[goal.sessionId] = goal
        }
        override suspend fun save(goal: SessionGoalEntity) {
            rows[goal.sessionId] = goal
        }
        override suspend fun delete(sessionId: String) {
            deletes += 1
            rows.remove(sessionId)
        }
    }

    @Test
    fun `the model can create the goal the board needs`() = runTest {
        val fake = FakePersistence()
        val manager = GoalManager(fake)
        val goal = manager.createByModel("s1", "  ship the release  ")
        assertEquals("ship the release", goal.objective)
        assertEquals(GoalOrigin.MODEL, goal.createdBy)
        assertEquals(1, fake.inserts)
        assertEquals(goal, fake.rows["s1"])
    }

    @Test
    fun `re-creating with the same objective is idempotent`() = runTest {
        val fake = FakePersistence()
        val manager = GoalManager(fake)
        val first = manager.createByModel("s1", "ship the release")
        val second = manager.createByModel("s1", "  ship the release  ")
        assertSame(first, second)
        assertEquals(1, fake.inserts)
        assertEquals(0, fake.deletes)
    }

    @Test
    fun `a new task replaces the model's own goal instead of inheriting it`() = runTest {
        val fake = FakePersistence()
        val manager = GoalManager(fake)
        manager.createByModel("s1", "fix the login bug")
        val next = manager.createByModel("s1", "write the release notes")
        assertEquals("write the release notes", next.objective)
        assertEquals(GoalStatus.ACTIVE, next.status)
        assertEquals(next, fake.rows.getValue("s1"))
        assertEquals(2, fake.inserts)
    }

    /**
     * The reported symptom: after a finished task, the next request still
     * mounted the OLD objective, so the model looked like it had read the
     * previous task's goal. The mounted prompt must carry the new task only.
     */
    @Test
    fun `the mounted prompt carries the new task, never the stale objective`() = runTest {
        val fake = FakePersistence()
        val manager = GoalManager(fake)
        manager.createByModel("s1", "fix the login bug")
        val current = manager.createByModel("s1", "write the release notes")
        val mount = goalMount(current, manager::continuationPrompt)
        assertTrue(mount.canContinue)
        val prompt = mount.prompt.orEmpty()
        assertTrue(prompt.contains("write the release notes"))
        assertFalse(prompt.contains("fix the login bug"))
    }

    @Test
    fun `a finished model goal does not block the next task`() = runTest {
        val fake = FakePersistence()
        val manager = GoalManager(fake)
        val first = manager.createByModel("s1", "fix the login bug")
        manager.updateByModel("s1", GoalStatus.COMPLETE, null)
        assertEquals(GoalStatus.COMPLETE, fake.rows.getValue("s1").status)
        val next = manager.createByModel("s1", "write the release notes")
        assertEquals("write the release notes", next.objective)
        assertNotEquals(first.objective, next.objective)
    }

    @Test
    fun `a human objective is never overwritten by the model`() = runTest {
        val fake = FakePersistence()
        val manager = GoalManager(fake)
        val human = manager.createByUser("s1", "what the user actually asked for")
        assertEquals(GoalOrigin.USER, human.createdBy)
        val model = manager.createByModel("s1", "what the model inferred")
        assertEquals(human.objective, model.objective)
        assertEquals(1, fake.inserts)
        assertEquals(0, fake.deletes)
    }

    @Test
    fun `a goal the human paused is left alone by the model`() = runTest {
        val fake = FakePersistence()
        val manager = GoalManager(fake)
        manager.createByModel("s1", "fix the login bug")
        manager.setPausedByUser("s1", true)
        val attempted = manager.createByModel("s1", "write the release notes")
        assertEquals("fix the login bug", attempted.objective)
        assertEquals(GoalStatus.PAUSED, fake.rows.getValue("s1").status)
        assertEquals(1, fake.inserts)
    }

    @Test
    fun `a token budget is recorded when the model supplies one`() = runTest {
        val fake = FakePersistence()
        val goal = GoalManager(fake).createByModel("s1", "ship it", tokenBudget = 5_000)
        assertEquals(5_000L, goal.tokenBudget)
    }

    @Test
    fun `the goal version signal advances so the card reloads`() = runTest {
        val before = GoalManager.version.value
        GoalManager(FakePersistence()).createByModel("s-version", "ship it")
        assertEquals(before + 1, GoalManager.version.value)
    }
}
