// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream semantics
// (provider/DsmlLeak.kt); no upstream test file existed, so the decision table
// is pinned here against this tree's MAX_RETRIES=1 guardrail.
package com.openminis.app.provider

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DsmlLeakTest {

    private val v4Sample = """<｜DSML｜invoke name="file_write">
        <｜DSML｜parameter key="path">/tmp/a.md</｜DSML｜parameter></｜DSML｜invoke>"""

    @Test
    fun `markers catch the v4 and degraded forms`() {
        assertNotNull(DsmlLeak.marker("thought... </｜｜DSML｜｜ parameter> tail"))
        assertNotNull(DsmlLeak.marker("x |DSML| y"))
        assertNotNull(DsmlLeak.marker("pre <｜tool▁calls▁begin｜> post"))
        assertNotNull(DsmlLeak.marker("plain tool_calls_begin token"))
        assertNull(DsmlLeak.marker(null))
        assertNull(DsmlLeak.marker(""))
        assertNull(DsmlLeak.marker("ordinary reasoning about tool calls"))
    }

    @Test
    fun `a leak is an empty clean stop with markup in the reasoning`() {
        assertTrue(DsmlLeak.isLeak(turnHasText = false, hasToolCalls = false, finishReason = "stop", reasoning = v4Sample))
        // Text, a tool call, or a non-clean stop is never a leak.
        assertFalse(DsmlLeak.isLeak(turnHasText = true, hasToolCalls = false, finishReason = "stop", reasoning = v4Sample))
        assertFalse(DsmlLeak.isLeak(turnHasText = false, hasToolCalls = true, finishReason = "stop", reasoning = v4Sample))
        assertFalse(DsmlLeak.isLeak(turnHasText = false, hasToolCalls = false, finishReason = "length", reasoning = v4Sample))
        assertFalse(DsmlLeak.isLeak(turnHasText = false, hasToolCalls = false, finishReason = "refusal", reasoning = v4Sample))
        assertFalse(DsmlLeak.isLeak(turnHasText = false, hasToolCalls = false, finishReason = "stop", reasoning = null))
    }

    @Test
    fun `exactly one resend, then give up`() {
        assertEquals(DsmlLeak.Step.Retry(1), DsmlLeak.next(0))
        assertEquals(DsmlLeak.Step.GiveUp, DsmlLeak.next(1))
        assertEquals(DsmlLeak.Step.GiveUp, DsmlLeak.next(5))
        assertEquals(1, DsmlLeak.MAX_RETRIES)
    }

    @Test
    fun `the loop drops the leaked turn and resends before the empty-turn handling`() {
        val loop = ProductionSources.read("ui/chat/ChatViewModelAgentLoopExt.kt")
        assertTrue(loop.contains("com.openminis.app.provider.DsmlLeak.isLeak("))
        assertTrue(loop.contains("com.openminis.app.provider.DsmlLeak.next(dsmlLeakRetries)"))
        val dsmlAt = loop.indexOf("[T-android-dsml-leak] DeepSeek wrote its tool")
        val emptyAt = loop.indexOf("val isEmptyTurn = EmptyTurnRecovery.shouldClassifyAsEmptyTurn(")
        assertTrue("the dsml retry runs before the empty-turn path", dsmlAt in 0 until emptyAt)
    }
}
