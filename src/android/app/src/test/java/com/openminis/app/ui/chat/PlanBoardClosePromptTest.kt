package com.openminis.app.ui.chat

import com.openminis.app.tools.AgentPlanStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-plan-close-nudge] The board close-out nudge that runs at natural turn
 * end. Pure logic in [PlanBoardClosePrompt] — these tests pin the contract the
 * agent-loop tail relies on: open boards get a reconcile nudge whose closing
 * update_goal instruction rides on the Goal actually being active, fully-done
 * boards with an ACTIVE Goal get a complete nudge, empty/closed/or capped
 * states stay silent, and the nudge can never fire more than a run's cap.
 */
class PlanBoardClosePromptTest {

    private fun plan(title: String, status: String) =
        AgentPlanStore.Plan(title = title, status = status)

    // ── open board → reconcile nudge ────────────────────────────────────────

    @Test
    fun `open board at turn end gets a reconcile nudge`() {
        val board = listOf(
            plan("read skills", "done"),
            plan("implement fix", "active"),
            plan("ship pr", "pending"),
        )
        val nudge = PlanBoardClosePrompt.nudge(board, goalActive = true, nudgesUsed = 0, maxNudges = 2)
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("agent_plan op=update status=done"))
        assertTrue(nudge.contains("implement fix"))
        assertTrue(nudge.contains("ship pr"))
        assertTrue(nudge.contains("update_goal(op=complete)"))
        assertFalse(nudge.contains("No active Goal"))
        assertFalse(nudge.contains("fully done"))
    }

    @Test
    fun `open board with no active goal still reconciles but skips update_goal`() {
        // The board outlives its Goal (cleared or never created): the nudge
        // must still push the reconcile, but the closing instruction would
        // make the model burn a tool call on a Goal that is not there.
        val board = listOf(
            plan("read skills", "done"),
            plan("implement fix", "active"),
        )
        val nudge = PlanBoardClosePrompt.nudge(board, goalActive = false, nudgesUsed = 0, maxNudges = 2)
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("agent_plan op=update status=done"))
        assertTrue(nudge.contains("implement fix"))
        assertTrue(nudge.contains("No active Goal — just reconcile the board above."))
        assertFalse(nudge.contains("update_goal(op=complete)"))
        assertFalse(nudge.contains("update_goal(op=clear)"))
    }

    @Test
    fun `fully done board with active goal gets a complete nudge`() {
        val board = listOf(
            plan("read skills", "done"),
            plan("implement fix", "done"),
            plan("ship pr", "done"),
        )
        val nudge = PlanBoardClosePrompt.nudge(board, goalActive = true, nudgesUsed = 0, maxNudges = 2)
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("fully done (3/3)"))
        assertTrue(nudge.contains("update_goal(op=complete)"))
    }

    @Test
    fun `failed step nudge names the failure constraint`() {
        val board = listOf(
            plan("flaky test", "failed"),
            plan("ship pr", "pending"),
        )
        val nudge = PlanBoardClosePrompt.nudge(board, goalActive = true, nudgesUsed = 0, maxNudges = 2)
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("failed step"))
        assertTrue(nudge.contains("op=update status=done"))
    }

    // ── silence cases ───────────────────────────────────────────────────────

    @Test
    fun `empty board never nudges`() {
        assertNull(PlanBoardClosePrompt.nudge(emptyList(), goalActive = true, nudgesUsed = 0, maxNudges = 2))
    }

    @Test
    fun `fully done board with no active goal is silent`() {
        val board = listOf(plan("done thing", "done"))
        assertNull(PlanBoardClosePrompt.nudge(board, goalActive = false, nudgesUsed = 0, maxNudges = 2))
    }

    @Test
    fun `nudge cap silences further attempts`() {
        val board = listOf(plan("stuck step", "pending"))
        assertNull(PlanBoardClosePrompt.nudge(board, goalActive = true, nudgesUsed = 2, maxNudges = 2))
    }

    @Test
    fun `zero cap never nudges`() {
        val board = listOf(plan("stuck step", "pending"))
        assertNull(PlanBoardClosePrompt.nudge(board, goalActive = true, nudgesUsed = 0, maxNudges = 0))
    }

    // ── goalActive helper ───────────────────────────────────────────────────

    @Test
    fun `goalActive maps only the active status`() {
        assertTrue(PlanBoardClosePrompt.goalActive("active"))
        assertFalse(PlanBoardClosePrompt.goalActive("complete"))
        assertFalse(PlanBoardClosePrompt.goalActive("paused"))
        assertFalse(PlanBoardClosePrompt.goalActive("blocked"))
        assertFalse(PlanBoardClosePrompt.goalActive(null))
    }

    @Test
    fun `nudge constants are shared with the loop`() {
        assertEquals(PlanBoardClosePrompt.DEFAULT_MAX_NUDGES, 2)
    }
}
