package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-retry-rebase] Pins for the pure turn-parts slice builder behind
 * [ChatViewModel.buildTurnParts] (ChatViewModelTurnPersistExt). The agent
 * loop itself is not JVM-testable, so the contract the compact-and-retry
 * rebase depends on is pinned HERE, on the pure slice function:
 * ChatViewModelAgentLoopExt.compactAndRetryForOverflow's seal+swap clears
 * allToolBlocks and rebases turnStartBlockIndex to 0 — a stale pre-clear
 * bound would slice from beyond the rewound list and the empty-slice guard
 * would silently DROP the whole retried turn at persist.
 */
class ChatViewModelTurnPersistExtTest {

    private fun text(id: String, content: String) =
        AssistantBlock(id = id, kind = "text", content = content)

    private fun toolUse(id: String, name: String) =
        AssistantBlock(id = id, kind = "tool_use", toolName = name)

    @Test
    fun `stale pre-clear bound beyond the rewound list yields an empty slice`() {
        // The regression shape: after the seal+swap the list was cleared but the
        // bound still held the pre-clear size — buildTurnParts returned nothing
        // and the retried turn was dropped at persist with no error anywhere.
        val blocks = listOf(text("t1", "retry body"), toolUse("u1", "file_read"))
        val parts = turnPartsFromBlocks(blocks, turnStartBlockIndex = 5, toolCallInputs = emptyMap())
        assertTrue("stale bound must not fabricate parts", parts.isEmpty())
    }

    @Test
    fun `rebased bound zero over the rewound list keeps the whole retried turn`() {
        // The contract the rebase restores: after the seal+swap the list holds
        // ONLY the retried attempt's blocks, so bound 0 carries all of them.
        val blocks = listOf(text("t1", "retry body"), toolUse("u1", "file_read"))
        val parts = turnPartsFromBlocks(
            blocks,
            turnStartBlockIndex = 0,
            toolCallInputs = mapOf("u1" to "{\"path\":\"/a\"}"),
        )
        assertEquals(2, parts.size)
        assertEquals("retry body", (parts[0] as AgentContentPart.Text).text)
        val use = parts[1] as AgentContentPart.ToolUse
        assertEquals("u1", use.id)
        assertEquals("file_read", use.name)
        assertEquals("/a", use.input.optString("path"))
    }

    @Test
    fun `mid-run bound slices only the current turn's blocks`() {
        // Normal multi-turn shape: earlier turns' blocks stay below the bound
        // (they were persisted by their own turns) and must not leak in again.
        val blocks = listOf(
            text("t0", "earlier turn"),
            toolUse("u0", "shell"),
            text("t1", "this turn"),
        )
        val parts = turnPartsFromBlocks(blocks, turnStartBlockIndex = 2, toolCallInputs = emptyMap())
        assertEquals(listOf("this turn"), parts.map { (it as AgentContentPart.Text).text })
    }

    @Test
    fun `thinking and info blocks and empty text are not persisted`() {
        val blocks = listOf(
            AssistantBlock(id = "th1", kind = "thinking", content = "secret chain"),
            AssistantBlock(id = "i1", kind = "info", content = "switched model"),
            text("t1", ""),
            text("t2", "visible"),
        )
        val parts = turnPartsFromBlocks(blocks, turnStartBlockIndex = 0, toolCallInputs = emptyMap())
        assertEquals(listOf("visible"), parts.map { (it as AgentContentPart.Text).text })
    }

    @Test
    fun `tool_use without a name is skipped, missing args default to empty object`() {
        val blocks = listOf(
            toolUse("u1", ""),
            toolUse("u2", "file_read"),
        )
        val parts = turnPartsFromBlocks(blocks, turnStartBlockIndex = 0, toolCallInputs = emptyMap())
        assertEquals(1, parts.size)
        val use = parts[0] as AgentContentPart.ToolUse
        assertEquals("u2", use.id)
        assertEquals(0, use.input.length())
    }
}
