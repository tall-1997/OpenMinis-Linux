package com.openminis.app.goal

import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [GoalManager] write-path contract: every successful persistence write
 * bumps the companion [GoalManager.version] signal exactly once (so the
 * PlanGoalCard header row reloads without the manager knowing a UI exists),
 * failed writes bump nothing, and [GoalManager.clearByUser] is the
 * user-sovereign removal that works from ANY status.
 *
 * Driven through the [GoalPersistence] seam with an in-memory fake — the
 * manager needs exactly four persistence operations, and JVM tests must not
 * drag Room in for them.
 */
class GoalManagerTest {

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

    private fun newManager(fake: FakePersistence): GoalManager = GoalManager(fake)

    private fun version(): Long = GoalManager.version.value

    @Test
    fun `create bumps version once and rejects a second goal without bumping`() = runTest {
        val fake = FakePersistence()
        val manager = newManager(fake)
        val before = version()
        manager.createByUser("s1", "ship it", 500)
        assertEquals(before + 1, version())
        try {
            manager.createByUser("s1", "another", null)
            fail("second create must be rejected")
        } catch (_: IllegalStateException) {
        }
        assertEquals(before + 1, version())
    }

    @Test
    fun `every successful write path bumps version`() = runTest {
        val fake = FakePersistence()
        val manager = newManager(fake)
        var before = version()
        manager.createByUser("s1", "ship it", 500)
        assertEquals(before + 1, version()); before += 1

        manager.setPausedByUser("s1", true)
        assertEquals(before + 1, version()); before += 1
        manager.setPausedByUser("s1", false)
        assertEquals(before + 1, version()); before += 1

        manager.updateBudgetByUser("s1", 900)
        assertEquals(before + 1, version()); before += 1

        manager.updateObjectiveByUser("s1", "ship it faster")
        assertEquals(before + 1, version()); before += 1

        manager.recordUsage("s1", 120, 1000)
        assertEquals(before + 1, version()); before += 1

        manager.updateByModel("s1", GoalStatus.COMPLETE, null)
        assertEquals(before + 1, version())
    }

    @Test
    fun `failed writes bump nothing`() = runTest {
        val fake = FakePersistence()
        val manager = newManager(fake)
        manager.createByUser("s1", "ship it", null)
        val before = version()
        fake.failAll = true
        try {
            manager.setPausedByUser("s1", true)
            fail("persist failure must propagate")
        } catch (_: IOException) {
        }
        assertEquals(before, version())
    }

    @Test
    fun `clearByUser removes any status and reports absence`() = runTest {
        val fake = FakePersistence()
        val manager = newManager(fake)
        manager.createByUser("s1", "ship it", null)
        manager.updateByModel("s1", GoalStatus.COMPLETE, null)
        var before = version()
        assertTrue(manager.clearByUser("s1"))
        assertEquals(before + 1, version())
        assertNull(fake.rows["s1"])
        before += 1
        // Second clear: nothing to remove — no bump, honest false.
        assertFalse(manager.clearByUser("s1"))
        assertEquals(before, version())
    }

    @Test
    fun `clearByUser works from blocked and budget-limited states`() = runTest {
        val fake = FakePersistence()
        val manager = newManager(fake)
        manager.createByUser("s1", "ship it", null)
        repeat(GoalStateMachine.BLOCKED_CONFIRMATIONS) {
            manager.updateByModel("s1", GoalStatus.BLOCKED, "same reason")
        }
        assertEquals(GoalStatus.BLOCKED, fake.rows["s1"]?.status)
        assertTrue(manager.clearByUser("s1"))

        manager.createByUser("s2", "ship it", 10)
        manager.recordUsage("s2", Long.MAX_VALUE, 1)
        assertEquals(GoalStatus.BUDGET_LIMITED, fake.rows["s2"]?.status)
        assertTrue(manager.clearByUser("s2"))
    }

    @Test
    fun `version is shared across manager instances`() = runTest {
        val fake = FakePersistence()
        val a = newManager(fake)
        val b = newManager(fake)
        val before = version()
        a.createByUser("s1", "ship it", null)
        b.setPausedByUser("s1", true)
        // One companion signal for the whole process — the card reloads no
        // matter which manager instance (slash UI, tool, agent loop) wrote.
        assertEquals(before + 2, version())
    }
}
