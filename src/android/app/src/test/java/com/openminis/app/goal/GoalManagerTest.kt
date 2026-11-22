package com.openminis.app.goal

import android.content.ContextWrapper
import com.openminis.app.data.GoalDriftPrefs
import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.tools.AgentPlanStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

    @get:Rule
    val tmp = TemporaryFolder()

    private val driftSessionId = "goal-drift-manager-test"
    private val driftContext = object : ContextWrapper(null) {
        override fun getFilesDir(): File = tmp.root
    }

    @After
    fun clearDriftTestBoard() {
        AgentPlanStore.clear(driftSessionId, null)
    }

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

    private fun addDriftPlan() {
        AgentPlanStore.add(driftSessionId, null, "Draft the schedule", id = "draft")
    }

    private fun driftState() = GoalDriftStore.load(tmp.root, driftSessionId)

    private fun seedOwedDrift() {
        val plans = AgentPlanStore.list(driftSessionId, null)
        val sha = requireNotNull(GoalDriftReconciler.boardFingerprint(plans))
        GoalDriftStore.save(tmp.root, driftSessionId, GoalDriftStore.DriftState(sha, GoalDriftPrefs.stallTurns()))
    }

    @Test
    fun `only committed tool turns reach the threshold and each board speaks once`() = runTest {
        val manager = GoalManager(FakePersistence(), tmp.root)
        manager.createByUser(driftSessionId, "Publish a team schedule")
        addDriftPlan()
        val threshold = GoalDriftPrefs.stallTurns()

        // The first observation establishes the board, not an unchanged tool turn.
        assertNull(manager.driftNudgeIfOwed(driftSessionId, driftContext))
        repeat(threshold - 1) { index ->
            manager.recordUsage(driftSessionId, 1, 2, driftContext)
            manager.recordUsage(driftSessionId, 1, 2)
            assertNull(manager.driftNudgeIfOwed(driftSessionId, driftContext))
            assertNull(manager.driftNudgeIfOwed(driftSessionId, driftContext))
            assertEquals(index + 1, driftState().stallTurns)
        }
        assertEquals(2L * (threshold - 1), manager.get(driftSessionId)?.tokensUsed)
        assertEquals(4L * (threshold - 1), manager.get(driftSessionId)?.elapsedMs)

        manager.recordUsage(driftSessionId, 1, 2, driftContext)
        assertNull(driftState().nudgedBoardSha)
        val nudge = requireNotNull(manager.driftNudgeIfOwed(driftSessionId, driftContext))
        assertTrue(nudge.contains("$threshold consecutive tool turns"))
        assertEquals(threshold, driftState().stallTurns)
        assertEquals(driftState().lastBoardSha, driftState().nudgedBoardSha)
        manager.recordUsage(driftSessionId, 1, 2, driftContext)
        assertNull(manager.driftNudgeIfOwed(driftSessionId, driftContext))
    }

    @Test
    fun `board progress and clear invalidate old stalls without a tool tick`() = runTest {
        val manager = GoalManager(FakePersistence(), tmp.root)
        manager.createByUser(driftSessionId, "Publish a team schedule")
        addDriftPlan()
        seedOwedDrift()
        val originalSha = driftState().lastBoardSha

        // The plan moved after the last tool accounting, before nudge delivery.
        AgentPlanStore.update(driftSessionId, null, "draft", status = "active")
        assertNull(manager.driftNudgeIfOwed(driftSessionId, driftContext))
        val advanced = driftState()
        assertTrue(advanced.lastBoardSha != null && advanced.lastBoardSha != originalSha)
        assertEquals(0, advanced.stallTurns)
        assertNull(advanced.nudgedBoardSha)
        repeat(GoalDriftPrefs.stallTurns()) { manager.recordUsage(driftSessionId, 1, 2, driftContext) }
        assertTrue(manager.driftNudgeIfOwed(driftSessionId, driftContext) != null)

        AgentPlanStore.clear(driftSessionId, null)
        assertNull(manager.driftNudgeIfOwed(driftSessionId, driftContext))
        assertEquals(GoalDriftStore.DriftState(), driftState())
    }

    @Test
    fun `paused or missing goal discards even a matching owed stall`() = runTest {
        val manager = GoalManager(FakePersistence())
        manager.createByUser(driftSessionId, "Publish a team schedule")
        addDriftPlan()
        seedOwedDrift()
        manager.setPausedByUser(driftSessionId, true)
        assertNull(manager.driftNudgeIfOwed(driftSessionId, driftContext))
        assertEquals(GoalDriftStore.DriftState(), driftState())
        assertTrue(manager.clearByUser(driftSessionId))

        seedOwedDrift()
        assertNull(manager.driftNudgeIfOwed(driftSessionId, driftContext))
        assertEquals(GoalDriftStore.DriftState(), driftState())
    }

    @Test
    fun `pause and resume between boundaries start a fresh stall`() = runTest {
        val manager = GoalManager(FakePersistence(), tmp.root)
        manager.createByUser(driftSessionId, "Publish a team schedule")
        addDriftPlan()
        seedOwedDrift()

        manager.setPausedByUser(driftSessionId, true)
        manager.setPausedByUser(driftSessionId, false)
        assertNull(manager.driftNudgeIfOwed(driftSessionId, driftContext))
        assertEquals(0, driftState().stallTurns)
    }

    @Test
    fun `the first committed turn on an existing board starts counting immediately`() = runTest {
        val manager = GoalManager(FakePersistence(), tmp.root)
        manager.createByUser(driftSessionId, "Publish a team schedule")
        addDriftPlan()
        val threshold = GoalDriftPrefs.stallTurns()

        // No sidecar yet: this turn is the first board baseline, so it must not tick.
        manager.observeDriftBoard(driftSessionId, driftContext)
        assertEquals(0, driftState().stallTurns)
        repeat(threshold) {
            manager.observeDriftBoard(driftSessionId, driftContext)
            manager.recordUsage(driftSessionId, 1, 2, driftContext)
        }
        assertEquals(threshold, driftState().stallTurns)
        assertTrue(manager.driftNudgeIfOwed(driftSessionId, driftContext) != null)
    }

    @Test
    fun `a plan edit between tool turns clears a previously earned stall`() = runTest {
        val manager = GoalManager(FakePersistence(), tmp.root)
        manager.createByUser(driftSessionId, "Publish a team schedule")
        addDriftPlan()
        val threshold = GoalDriftPrefs.stallTurns()
        seedOwedDrift()
        // The user retries the failed step (failed -> active) before the next tool turn.
        AgentPlanStore.update(driftSessionId, null, "draft", status = "failed")
        manager.observeDriftBoard(driftSessionId, driftContext)
        assertEquals(0, driftState().stallTurns)
        assertNull(driftState().nudgedBoardSha)
        repeat(threshold - 1) { manager.recordUsage(driftSessionId, 1, 2, driftContext) }
        assertNull(manager.driftNudgeIfOwed(driftSessionId, driftContext))
        manager.recordUsage(driftSessionId, 1, 2, driftContext)
        assertTrue(manager.driftNudgeIfOwed(driftSessionId, driftContext) != null)
    }
}
