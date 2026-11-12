package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [T-tool-execution-ledger] ZCode's streaming contract: one call executes once
 * and owns exactly one result. The key is (id, name, args) because a provider
 * may reuse a numbered id for a different call in a later turn.
 */
class ToolExecutionLedgerTest {

    private fun part(id: String, content: String = "ok") =
        AgentContentPart.ToolResult(id = id, name = "shell_execute", content = content)

    @Test
    fun `a recorded call is reused by id name and args`() {
        val ledger = ToolExecutionLedger()
        val part = part("call_1")
        ledger.record("call_1", "shell_execute", "sha-a", part)
        assertSame(part, ledger.reuse("call_1", "shell_execute", "sha-a"))
    }

    @Test
    fun `an unrecorded call is not reused`() {
        val ledger = ToolExecutionLedger()
        assertNull(ledger.reuse("call_missing", "shell_execute", "sha-a"))
        assertNull(ledger.reuse("", "shell_execute", "sha-a"))
    }

    @Test
    fun `the same id with different arguments is a different call`() {
        val ledger = ToolExecutionLedger()
        ledger.record("call_0", "shell_execute", "sha-old", part("call_0", "old"))
        assertNull(
            "a provider that numbers calls per response must not get a stale result",
            ledger.reuse("call_0", "shell_execute", "sha-new"),
        )
        assertNull(ledger.reuse("call_0", "read_file", "sha-old"))
    }

    @Test
    fun `a replay cannot overwrite the first result`() {
        val ledger = ToolExecutionLedger()
        ledger.record("call_2", "shell_execute", "sha-x", part("call_2", "first"))
        ledger.record("call_2", "shell_execute", "sha-x", part("call_2", "second"))
        assertEquals("first", ledger.reuse("call_2", "shell_execute", "sha-x")?.content)
        assertEquals(1, ledger.size())
    }
}
