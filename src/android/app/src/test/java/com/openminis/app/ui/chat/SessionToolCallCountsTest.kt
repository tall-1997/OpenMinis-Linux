package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-pi-tools-bucket] Pins for the UsageLedger tools-bucket wiring
 * (sessionToolCallCounts in ChatViewModelTokenExt): fake in-memory blocks in,
 * per-tool call counts out — the exact fold the Token Usage sheet renders.
 * foldToolUse / UsageState primitives have their own component tests; what is
 * pinned HERE is the block-to-bucket mapping (kind + name filtering, no
 * double-count, no icon reuse leak).
 */
class SessionToolCallCountsTest {

    private fun assistant(vararg blocks: AssistantBlock) = ChatMessage(
        id = "a_${blocks.joinToString { it.id }}",
        role = "assistant",
        content = "",
        toolBlocks = blocks.toList(),
    )

    private fun toolUse(id: String, name: String) = AssistantBlock(
        id = id,
        kind = "tool_use",
        toolName = name,
    )

    @Test
    fun `counts per tool across the loaded window`() {
        val counts = sessionToolCallCounts(
            listOf(
                assistant(toolUse("b1", "read_file"), toolUse("b2", "read_file"), toolUse("b3", "bash")),
                assistant(toolUse("b4", "bash")),
            ),
        )
        assertEquals(mapOf("read_file" to 2, "bash" to 2), counts)
    }

    @Test
    fun `info and thinking blocks never count as tool calls`() {
        // Info blocks reuse AssistantBlock.toolName for icons/markers ("compact",
        // icon kinds) and toolResult blocks mirror the same call set — counting
        // either would inflate the tools bucket.
        val counts = sessionToolCallCounts(
            listOf(
                assistant(
                    AssistantBlock(id = "i1", kind = "info", toolName = "compact"),
                    AssistantBlock(id = "t1", kind = "thinking", content = "reasoning"),
                    toolUse("b1", "read_file"),
                ),
            ),
        )
        assertEquals(mapOf("read_file" to 1), counts)
    }

    @Test
    fun `blank tool names and non-assistant rows are skipped`() {
        val counts = sessionToolCallCounts(
            listOf(
                ChatMessage(id = "u1", role = "user", content = "hi"),
                assistant(AssistantBlock(id = "b0", kind = "tool_use", toolName = "")),
                assistant(toolUse("b1", "grep")),
            ),
        )
        assertEquals(mapOf("grep" to 1), counts)
    }

    @Test
    fun `empty history folds to an empty map`() {
        assertTrue(sessionToolCallCounts(emptyList()).isEmpty())
    }
}
