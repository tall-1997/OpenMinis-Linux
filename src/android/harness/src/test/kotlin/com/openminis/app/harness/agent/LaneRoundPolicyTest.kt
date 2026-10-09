package com.openminis.app.harness.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-taixu-2.3] 车道轮策略验收：钳制、预算级别、警告文案、页脚、
 * 输出合成、卡片提取、参数预览。
 */
class LaneRoundPolicyTest {

    @Test
    fun `turns are clamped to the runaway guard`() {
        assertEquals(1, LaneRoundPolicy.clampTurns(0))
        assertEquals(7, LaneRoundPolicy.clampTurns(7))
        assertEquals(LaneRoundPolicy.ABSOLUTE_MAX_TURNS, LaneRoundPolicy.clampTurns(99_999))
    }

    @Test
    fun `budget levels fire at 80 and 95 percent`() {
        assertEquals(LaneRoundPolicy.BudgetLevel.NONE, LaneRoundPolicy.budgetLevel(1, 100))
        assertEquals(LaneRoundPolicy.BudgetLevel.WARN, LaneRoundPolicy.budgetLevel(80, 100))
        assertEquals(LaneRoundPolicy.BudgetLevel.FORCE, LaneRoundPolicy.budgetLevel(95, 100))
        assertEquals(LaneRoundPolicy.BudgetLevel.FORCE, LaneRoundPolicy.budgetLevel(100, 100))
    }

    @Test
    fun `force warning orders a stop and partial report`() {
        val msg = LaneRoundPolicy.budgetWarningMessage(95, 100, LaneRoundPolicy.BudgetLevel.FORCE)
        assertTrue(msg!!.contains("force=\"true\""))
        assertTrue(msg.contains("STOP calling tools NOW"))
        val warn = LaneRoundPolicy.budgetWarningMessage(80, 100, LaneRoundPolicy.BudgetLevel.WARN)
        assertTrue(warn!!.contains("used=\"80/100\""))
        assertNull(LaneRoundPolicy.budgetWarningMessage(1, 100, LaneRoundPolicy.BudgetLevel.NONE))
    }

    @Test
    fun `footers distinguish findings token exhaustion and failure`() {
        assertEquals("(reached the 5-turn budget — partial report above)",
            LaneRoundPolicy.turnBudgetFooter(5, true))
        assertEquals("(sub-agent reached the 5-turn budget with no findings to report)",
            LaneRoundPolicy.turnBudgetFooter(5, false))
        assertEquals("(stopped: shared token budget exhausted at 3/5 turns)",
            LaneRoundPolicy.tokenExhaustedFooter(3, 5))
        assertEquals("Sub-agent failed: boom", LaneRoundPolicy.failureFooter("boom"))
    }

    @Test
    fun `compose output keeps trace report footer layout and truncates the tail`() {
        val out = LaneRoundPolicy.composeOutput("结论", "step1", "(footer)")
        assertTrue(out.startsWith("## Trace\nstep1"))
        assertTrue(out.contains("## Report\n结论"))
        assertTrue(out.endsWith("(footer)"))
        // 空输出兜底
        assertEquals("(sub-agent finished with empty output)", LaneRoundPolicy.composeOutput("", ""))
        // 超长保尾截断
        val big = "x".repeat(LaneRoundPolicy.MAX_REPORT_CHARS + 500)
        val truncated = LaneRoundPolicy.composeOutput(big, "")
        assertTrue(truncated.startsWith("…(truncated)"))
        assertTrue(truncated.length <= LaneRoundPolicy.MAX_REPORT_CHARS + 20)
    }

    @Test
    fun `card step shows the report section not the step history`() {
        assertEquals("结论", LaneRoundPolicy.cardStep("## Trace\ns1\n\n## Report\n结论"))
        assertEquals("已结束", LaneRoundPolicy.cardStep("## Trace\ns1"))
        assertEquals("原文", LaneRoundPolicy.cardStep("原文"))
    }

    @Test
    fun `preview tool args picks the first known key and flattens`() {
        assertEquals("ls -la", LaneRoundPolicy.previewToolArgs("""{"command":"ls -la"}"""))
        assertEquals("/tmp/a.kt", LaneRoundPolicy.previewToolArgs("""{"path":"/tmp/a.kt"}"""))
        assertEquals("a b", LaneRoundPolicy.previewToolArgs("""{"command":"a\nb"}"""))
        val long = "y".repeat(200)
        assertEquals(80, LaneRoundPolicy.previewToolArgs("""{"command":"$long"}""").length)
    }
}
