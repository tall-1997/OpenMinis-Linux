package com.openminis.app.ui.chat

import com.openminis.app.tools.AgentPlanStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-plan-board-current] 收起态当前任务选择契约：并行 active 取最先开始的；
 * 无 active 落首个 pending（完成后自动换下一条）；全终态返回 null。
 */
class PlanBoardCurrentStepTest {
    private fun plan(id: String, status: String, position: Int, startedAt: Long = 0) =
        AgentPlanStore.Plan(id = id, title = "t$id", status = status, position = position, startedAt = startedAt)

    @Test
    fun `parallel actives pick the earliest started`() {
        val plans = listOf(
            plan("a", "active", 0, startedAt = 500),
            plan("b", "active", 1, startedAt = 100),
            plan("c", "pending", 2),
        )
        assertEquals("b", pickCurrentStep(plans)?.id)
    }

    @Test
    fun `active wins over an earlier pending`() {
        val plans = listOf(plan("p", "pending", 0), plan("a", "active", 1, startedAt = 9))
        assertEquals("a", pickCurrentStep(plans)?.id)
    }

    @Test
    fun `no active falls back to first pending`() {
        val plans = listOf(plan("d", "done", 0), plan("p1", "pending", 1), plan("p2", "pending", 2))
        assertEquals("p1", pickCurrentStep(plans)?.id)
    }

    @Test
    fun `all terminal returns null`() {
        val plans = listOf(plan("d", "done", 0), plan("f", "failed", 1))
        assertNull(pickCurrentStep(plans))
    }

    @Test
    fun `legacy zero startedAt ties break by position`() {
        val plans = listOf(
            plan("late", "active", 3, startedAt = 0),
            plan("early", "active", 1, startedAt = 0),
        )
        assertEquals("early", pickCurrentStep(plans)?.id)
    }

    @Test
    fun `a finished step is replaced by the next active not by a pending`() {
        val plans = listOf(
            plan("d", "done", 0, startedAt = 1),
            plan("a2", "active", 1, startedAt = 2),
            plan("p", "pending", 2),
        )
        assertEquals("a2", pickCurrentStep(plans)?.id)
    }
}
