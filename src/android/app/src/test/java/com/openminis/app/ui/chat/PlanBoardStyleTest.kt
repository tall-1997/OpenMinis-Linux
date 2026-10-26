package com.openminis.app.ui.chat

import com.openminis.app.tools.AgentPlanStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-planboard-restyle] Pure logic behind the plan board's collapsed
 * summary bar: which step the summary names, and the "2/5 · title" text the
 * bar renders (PlanBoardStyle.kt). Presentation is Compose; these two are the
 * testable seams.
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
}
