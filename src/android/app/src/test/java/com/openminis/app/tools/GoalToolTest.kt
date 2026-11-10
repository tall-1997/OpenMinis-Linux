package com.openminis.app.tools

import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.goal.GoalManager
import com.openminis.app.goal.GoalPersistence
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-goal-tool-clear] `update_goal` used to have no delete operation: the agent
 * could mark a Goal complete but never remove it, so the row stayed and kept
 * mounting into every later request of the session. `op=clear` is that missing
 * delete — and it deliberately refuses a PAUSED Goal, which is the human's
 * explicit "leave this alone" and would otherwise be discarded silently.
 */
class GoalToolTest {

    private class FakePersistence : GoalPersistence {
        val rows = mutableMapOf<String, SessionGoalEntity>()
        override suspend fun load(sessionId: String): SessionGoalEntity? = rows[sessionId]
        override suspend fun insert(goal: SessionGoalEntity) {
            rows[goal.sessionId] = goal
        }
        override suspend fun save(goal: SessionGoalEntity) {
            rows[goal.sessionId] = goal
        }
        override suspend fun delete(sessionId: String) {
            rows.remove(sessionId)
        }
    }

    private fun managerWithGoal(): Pair<GoalManager, FakePersistence> {
        val fake = FakePersistence()
        fake.rows["s1"] = SessionGoalEntity(
            sessionId = "s1",
            objective = "fix the login bug",
            status = GoalStatus.ACTIVE,
            createdAt = 1L,
            updatedAt = 1L,
            createdBy = com.openminis.app.data.db.GoalOrigin.MODEL,
        )
        return GoalManager(fake) to fake
    }

    @Test
    fun `op=clear deletes the goal`() = runTest {
        val (manager, fake) = managerWithGoal()
        val result = GoalTool.execute("""{"op":"clear"}""", "s1", manager)
        assertTrue(result.success)
        assertTrue(result.output.contains("cleared", ignoreCase = true))
        assertNull(fake.rows["s1"])
    }

    @Test
    fun `op=clear refuses a goal the human paused`() = runTest {
        val (manager, fake) = managerWithGoal()
        manager.setPausedByUser("s1", true)
        val result = GoalTool.execute("""{"op":"clear"}""", "s1", manager)
        assertFalse(result.success)
        assertEquals(GoalStatus.PAUSED, fake.rows.getValue("s1").status)
    }

    @Test
    fun `op=clear without a goal reports nothing to clear`() = runTest {
        val fake = FakePersistence()
        val result = GoalTool.execute("""{"op":"clear"}""", "s1", GoalManager(fake))
        assertFalse(result.success)
    }

    @Test
    fun `op=clear then op=create records the next task`() = runTest {
        val (manager, fake) = managerWithGoal()
        GoalTool.execute("""{"op":"clear"}""", "s1", manager)
        val created = GoalTool.execute(
            """{"op":"create","objective":"write the release notes"}""",
            "s1",
            manager,
        )
        assertTrue(created.success)
        assertEquals("write the release notes", fake.rows.getValue("s1").objective)
    }

    @Test
    fun `an unknown op is rejected`() = runTest {
        val (manager, _) = managerWithGoal()
        val result = GoalTool.execute("""{"op":"nuke"}""", "s1", manager)
        assertFalse(result.success)
    }
}
