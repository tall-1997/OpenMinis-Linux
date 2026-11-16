package com.openminis.app.ui.chat

import com.openminis.app.tools.AgentPlanStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-planboard-restyle] [T-plan-card-visual-redesign] Pure logic behind
 * the plan board's collapsed summary row: which step the summary names, and
 * the "2/5 · title" text the row renders (PlanBoardStyle.kt). Presentation is
 * Compose; these two are the testable seams.
 */
class PlanBoardStyleTest {

    private fun plan(title: String, status: String) =
        AgentPlanStore.Plan(title = title, status = status)

    @Test
    fun `current step prefers the active one`() {
        val board = listOf(
            plan("done step", "done"),
            plan("queued step", "pending"),
            plan("running step", "active"),
            plan("later step", "pending"),
        )
        assertEquals("running step", planBoardCurrentStep(board)?.title)
    }

    @Test
    fun `current step falls back to the first unfinished`() {
        val board = listOf(
            plan("done step", "done"),
            plan("next up", "pending"),
            plan("after", "pending"),
        )
        assertEquals("next up", planBoardCurrentStep(board)?.title)
    }

    @Test
    fun `failed steps still count as unfinished for the fallback`() {
        val board = listOf(plan("broken", "failed"), plan("after", "pending"))
        assertEquals("broken", planBoardCurrentStep(board)?.title)
    }

    @Test
    fun `fully done board has no current step`() {
        val board = listOf(plan("a", "done"), plan("b", "done"))
        assertNull(planBoardCurrentStep(board))
        assertNull(planBoardCurrentStep(emptyList()))
    }

    @Test
    fun `summary joins count and title with a middle dot`() {
        assertEquals("2/5 · fix the login flow", planBoardSummary(2, 5, "fix the login flow"))
    }

    @Test
    fun `summary falls back to the bare count without a title`() {
        assertEquals("2/5", planBoardSummary(2, 5, null))
        assertEquals("0/3", planBoardSummary(0, 3, ""))
        assertEquals("0/3", planBoardSummary(0, 3, "   "))
    }

    @Test
    fun `summary count leads so the collapsed bar keeps progress visible`() {
        val summary = planBoardSummary(1, 2, "step")
        assertEquals("1/2", summary.take(3))
        assertEquals(" · step", summary.drop(3))
    }

    // [T-plan-board-dismiss] The user-side exit exists ONLY for a static
    // board: a "1/3 done, 2 failed" wave board used to sit above the composer
    // forever (retireFinished only clears all-done boards). An ACTIVE step is
    // live progress and must never be dismissible mid-run.

    @Test
    fun `a static board with failed steps is dismissible`() {
        val board = listOf(
            plan("lane 1", "done"),
            plan("lane 2", "failed"),
            plan("lane 3", "failed"),
        )
        assertTrue(planBoardDismissible(board))
    }

    @Test
    fun `a static board of done and pending steps is dismissible`() {
        assertTrue(planBoardDismissible(listOf(plan("a", "done"), plan("b", "pending"))))
    }

    @Test
    fun `a board with an active step is not dismissible`() {
        val board = listOf(
            plan("done step", "done"),
            plan("running step", "active"),
            plan("broken step", "failed"),
        )
        assertFalse(planBoardDismissible(board))
    }

    @Test
    fun `an empty board is not dismissible`() {
        assertFalse(planBoardDismissible(emptyList()))
    }
}
