package com.openminis.app.goal

import com.openminis.app.data.db.SessionGoalEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [T-goal-created-with-plan] `createByModel` is the model-side counterpart of
 * `createByUser`: creating the task board must also record the objective, so
 * the Goal exists by the time the turn ends.
 *
 * Its contract differs from [GoalManager.createByUser] in exactly the ways the
 * agent loop needs: it is idempotent (a re-emitted board or a retried tool call
 * must not fail the turn) and it never overwrites an objective the human set.
 */
class GoalManagerCreateByModelTest {

    private class FakePersistence : GoalPersistence {
        val rows = mutableMapOf<String, SessionGoalEntity>()
        var inserts = 0
        override suspend fun load(sessionId: String): SessionGoalEntity? = rows[sessionId]
        override suspend fun insert(goal: SessionGoalEntity) {
            inserts += 1
            rows[goal.sessionId] = goal
        }
        override suspend fun save(goal: SessionGoalEntity) {
            rows[goal.sessionId] = goal
        }
        override suspend fun delete(sessionId: String) {
            rows.remove(sessionId)
        }
    }

    @Test
    fun `the model can create the goal the board needs`() = runTest {
        val fake = FakePersistence()
        val manager = GoalManager(fake)
        val goal = manager.createByModel("s1", "  ship the release  ")
        assertEquals("ship the release", goal.objective)
        assertEquals(1, fake.inserts)
        assertEquals(goal, fake.rows["s1"])
    }

    @Test
    fun `re-creating is idempotent and keeps the first objective`() = runTest {
        val fake = FakePersistence()
        val manager = GoalManager(fake)
        val first = manager.createByModel("s1", "ship the release")
        val second = manager.createByModel("s1", "something else entirely")
        assertSame(first, second)
        assertEquals("ship the release", fake.rows.getValue("s1").objective)
        assertEquals(1, fake.inserts)
    }

    @Test
    fun `a human objective is never overwritten by the model`() = runTest {
        val fake = FakePersistence()
        val manager = GoalManager(fake)
        val human = manager.createByUser("s1", "what the user actually asked for")
        val model = manager.createByModel("s1", "what the model inferred")
        assertEquals(human.objective, model.objective)
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
