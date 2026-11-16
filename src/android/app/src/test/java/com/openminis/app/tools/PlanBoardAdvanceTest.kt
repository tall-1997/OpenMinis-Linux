package com.openminis.app.tools

import com.openminis.app.data.db.GoalStatus
import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.goal.GoalManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-plan-host-derive] The board's DONE state used to be writable ONLY by the
 * model (`agent_plan op=update status=done`), and the Goal's close ONLY by
 * `update_goal(op=complete)`. Every prior fix (aliases, ref ladder,
 * auto-activation, nudges, the verifier) helps only when the model SENDS that
 * call; the dominant failure is the model moving on to the next step's WORK
 * without sending it. These tests pin the host-side derivation from work
 * evidence: narration attribution walks the board, and an all-done board
 * closes an ACTIVE Goal through the verifier path — no prompt involved.
 */
class PlanBoardAdvanceTest {

    private val sid = "board-advance-test"
    private val goalId = "goal-test"

    /** In-memory goal persistence: JVM-only, no Room. */
    private class FakeGoalPersistence : com.openminis.app.goal.GoalPersistence {
        val rows = LinkedHashMap<String, SessionGoalEntity>()
        override suspend fun load(sessionId: String): SessionGoalEntity? = rows[sessionId]
        override suspend fun insert(goal: SessionGoalEntity) { rows[goal.sessionId] = goal }
        override suspend fun save(goal: SessionGoalEntity) { rows[goal.sessionId] = goal }
        override suspend fun delete(sessionId: String) { rows.remove(sessionId) }
    }

    private lateinit var persistence: FakeGoalPersistence
    private lateinit var goals: GoalManager

    @Before
    fun setUp() {
        AgentPlanStore.clear(sid, null)
        persistence = FakeGoalPersistence()
        goals = GoalManager(persistence)
        com.openminis.app.goal.GoalManager.resetCompletionRejections()
    }

    private fun seedBoard(vararg items: AgentPlanStore.PlanInput) {
        AgentPlanStore.replace(sid, null, items.toList())
    }

    private fun board() = AgentPlanStore.list(sid, null)

    private fun seedGoal() {
        val goal = SessionGoalEntity(
            sessionId = sid,
            objective = "ship the feature",
            createdAt = 1_000L,
            updatedAt = 1_000L,
            createdBy = com.openminis.app.data.db.GoalOrigin.MODEL,
        )
        persistence.rows[goal.sessionId] = goal
    }

    private suspend fun reconcile(toolTitle: String, toolName: String = "shell_execute", succeeded: Boolean = true) =
        PlanBoardAdvance.reconcileAfterTool(sid, null, toolName, toolTitle, succeeded, goals)

    // ------------------------------------------- rule 1: attribution -------

    @Test
    fun `narrating the next step marks the active step done and activates the next`() = runTest {
        seedBoard(
            AgentPlanStore.PlanInput(id = "s1", title = "read skills", status = "active"),
            AgentPlanStore.PlanInput(id = "s2", title = "implement fix", status = "pending"),
        )
        // The model's tool narration names the NEXT step's work.
        val note = reconcile("implement fix in auth module, ready to verify")
        assertNotNull("narration attribution must fire", note)
        assertTrue(note!!.contains("host"))
        val plans = board()
        assertEquals("done", plans.first { it.id == "s1" }.status)
        // updateWithAdvance walked the cursor in the same save.
        assertEquals("active", plans.first { it.id == "s2" }.status)
    }

    @Test
    fun `narrating a pending step with nothing active activates it`() = runTest {
        seedBoard(
            AgentPlanStore.PlanInput(id = "s1", title = "read skills", status = "pending"),
            AgentPlanStore.PlanInput(id = "s2", title = "implement fix", status = "pending"),
        )
        val note = reconcile("read skills documentation first")
        assertNotNull(note)
        assertEquals("active", board().first { it.id == "s1" }.status)
    }

    @Test
    fun `narration naming the active step itself does not move the board`() = runTest {
        seedBoard(
            AgentPlanStore.PlanInput(id = "s1", title = "read skills", status = "active"),
            AgentPlanStore.PlanInput(id = "s2", title = "implement fix", status = "pending"),
        )
        assertNull(reconcile("read the skills list again"))
        assertEquals("active", board().first { it.id == "s1" }.status)
    }

    @Test
    fun `failed tool call is not work evidence`() = runTest {
        seedBoard(
            AgentPlanStore.PlanInput(id = "s1", title = "read skills", status = "active"),
            AgentPlanStore.PlanInput(id = "s2", title = "implement fix", status = "pending"),
        )
        assertNull(reconcile("implement fix now", succeeded = false))
        assertEquals("active", board().first { it.id == "s1" }.status)
    }

    @Test
    fun `bookkeeping tools are exempt from attribution`() = runTest {
        seedBoard(
            AgentPlanStore.PlanInput(id = "s1", title = "read skills", status = "active"),
            AgentPlanStore.PlanInput(id = "s2", title = "implement fix", status = "pending"),
        )
        // An agent_plan call whose tool_title happens to name the NEXT step
        // must not advance the board as a side effect — the call itself is
        // the bookkeeping.
        assertNull(reconcile("implement fix progress update", toolName = AgentPlanTool.NAME))
        assertEquals("active", board().first { it.id == "s1" }.status)
    }

    @Test
    fun `ambiguous narration does not move the board`() = runTest {
        seedBoard(
            AgentPlanStore.PlanInput(id = "s1", title = "read skills", status = "active"),
            AgentPlanStore.PlanInput(id = "s2", title = "read skills slowly", status = "pending"),
        )
        // "read skills" is a prefix of BOTH titles → ambiguous → -1 → no move.
        assertNull(reconcile("read skills now"))
        assertEquals("active", board().first { it.id == "s1" }.status)
    }

    // ------------------------------------------ rule 2: goal close-out -----

    @Test
    fun `all-done board closes an active goal through the verifier`() = runTest {
        seedBoard(
            AgentPlanStore.PlanInput(id = "s1", title = "read skills", status = "done"),
            AgentPlanStore.PlanInput(id = "s2", title = "implement fix", status = "done"),
        )
        seedGoal()
        val note = reconcile("wrap-up notes")
        assertNotNull(note)
        assertTrue(note!!.contains("Goal closed by the host"))
        assertEquals(GoalStatus.COMPLETE, persistence.rows[sid]!!.status)
    }

    @Test
    fun `board with pending or failed steps does not close the goal`() = runTest {
        seedBoard(
            AgentPlanStore.PlanInput(id = "s1", title = "read skills", status = "done"),
            AgentPlanStore.PlanInput(id = "s2", title = "implement fix", status = "failed"),
        )
        seedGoal()
        assertNull(reconcile("wrap-up notes"))
        assertEquals(GoalStatus.ACTIVE, persistence.rows[sid]!!.status)
    }

    @Test
    fun `no board means no reconcile and no goal touch`() = runTest {
        seedGoal()
        assertNull(reconcile("anything"))
        assertEquals(GoalStatus.ACTIVE, persistence.rows[sid]!!.status)
    }

    @Test
    fun `receipt carries the attribution and the close-out together`() = runTest {
        seedBoard(
            AgentPlanStore.PlanInput(id = "s1", title = "read skills", status = "active"),
            AgentPlanStore.PlanInput(id = "s2", title = "implement fix", status = "pending"),
        )
        seedGoal()
        // First reconciliation: the narration quotes s2's title → s1 done
        // host-side, s2 activated. Then the model marks s2 done itself, and
        // the second reconciliation closes the ACTIVE Goal (all-done board).
        reconcile("implement fix in auth module")
        AgentPlanStore.update(sid, null, "s2", status = "done")
        val note = reconcile("final wrap-up notes")
        assertNotNull(note)
        assertTrue(note!!.contains("Goal closed by the host"))
        assertEquals(GoalStatus.COMPLETE, persistence.rows[sid]!!.status)
    }
}
