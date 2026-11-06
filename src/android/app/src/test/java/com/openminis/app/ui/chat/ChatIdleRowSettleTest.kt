package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-thinking-row-follows-run] / [T-android-orphaned-running-tool-spin]
 *
 * [settleIdleRows] is the single sink every message list passes through when no
 * run is streaming. These tests pin the two claims it must never leave behind:
 * a bubble that says "thinking" and a tool row that says "running".
 */
class ChatIdleRowSettleTest {

    private fun tool(
        id: String = "t1",
        name: String = "bash",
        status: ToolBlockStatus = ToolBlockStatus.RUNNING,
    ) = AssistantBlock(id = id, kind = "tool_use", toolName = name, toolStatus = status)

    private fun msg(
        id: String = "m1",
        streaming: Boolean = false,
        awaiting: Boolean = false,
        blocks: List<AssistantBlock> = emptyList(),
    ) = ChatMessage(
        id = id,
        role = "assistant",
        content = "",
        isStreaming = streaming,
        isAwaitingModelResponse = awaiting,
        toolBlocks = blocks,
    )

    @Test
    fun `an idle list clears a bubble that still claims to be streaming`() {
        val settled = settleIdleRows(
            listOf(msg(streaming = true, awaiting = true), msg(id = "m2")),
        )
        assertFalse(settled[0].isStreaming)
        assertFalse(settled[0].isAwaitingModelResponse)
        // Untouched rows are passed through, not rebuilt.
        assertEquals("m2", settled[1].id)
    }

    @Test
    fun `a spinner left behind by a dead run becomes a timeout`() {
        val settled = settleIdleRows(listOf(msg(blocks = listOf(tool(status = ToolBlockStatus.RUNNING)))))
        assertEquals(ToolBlockStatus.TIMEOUT, settled[0].toolBlocks.single().toolStatus)
    }

    @Test
    fun `a finished tool keeps its terminal status`() {
        val settled = settleIdleRows(
            listOf(msg(blocks = listOf(tool(status = ToolBlockStatus.SUCCESS), tool(id = "t2", status = ToolBlockStatus.FAILED)))),
        )
        assertEquals(ToolBlockStatus.SUCCESS, settled[0].toolBlocks[0].toolStatus)
        assertEquals(ToolBlockStatus.FAILED, settled[0].toolBlocks[1].toolStatus)
    }

    @Test
    fun `a sub agent card may still be running while the main turn is idle`() {
        val card = tool(id = "sub1#sub-2", name = "spawn_agent", status = ToolBlockStatus.RUNNING)
        val settled = settleIdleRows(listOf(msg(blocks = listOf(card))))
        assertEquals(ToolBlockStatus.RUNNING, settled[0].toolBlocks.single().toolStatus)
    }

    @Test
    fun `a fully settled list is returned without copying`() {
        val rows = listOf(msg(blocks = listOf(tool(status = ToolBlockStatus.SUCCESS))))
        val settled = settleIdleRows(rows)
        assertEquals(rows.size, settled.size)
        assertTrue(rows[0] === settled[0])
    }
}
