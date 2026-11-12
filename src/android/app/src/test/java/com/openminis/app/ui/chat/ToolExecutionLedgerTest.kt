package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [T-tool-execution-ledger] ZCode's streaming contract: one call id executes
 * once and owns exactly one result. A replay reuses that result.
 */
class ToolExecutionLedgerTest {

    private fun part(id: String, content: String = "ok") =
        AgentContentPart.ToolResult(id = id, name = "shell_execute", content = content)

    @Test
    fun `a recorded call is reused by id`() {
        val ledger = ToolExecutionLedger()
        val part = part("call_1")
        ledger.record("call_1", part)
        assertSame(part, ledger.reuse("call_1"))
    }

    @Test
    fun `an unrecorded call is not reused`() {
        val ledger = ToolExecutionLedger()
        assertNull(ledger.reuse("call_missing"))
        assertNull(ledger.reuse(""))
    }

    @Test
    fun `a replay cannot overwrite the first result`() {
        val ledger = ToolExecutionLedger()
        val first = part("call_2", "first")
        ledger.record("call_2", first)
        ledger.record("call_2", part("call_2", "second"))
        assertEquals("first", ledger.reuse("call_2")?.content)
        assertEquals(1, ledger.size())
    }
}
