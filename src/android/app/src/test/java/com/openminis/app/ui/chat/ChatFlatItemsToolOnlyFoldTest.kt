package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the user report: with AI-process folding enabled, a turn
 * consisting ONLY of tool calls (no reply text) rendered a large blank band —
 * the standalone AssistantHeader row above an otherwise empty collapsed turn.
 */
class ChatFlatItemsToolOnlyFoldTest {

    private fun assistant(
        id: String = "m1",
        blocks: List<AssistantBlock>,
        role: String = "assistant",
    ) = ChatMessage(
        id = id,
        role = role,
        content = "",
        isStreaming = false,
        toolBlocks = blocks,
    )

    private fun tool(id: String, status: ToolBlockStatus = ToolBlockStatus.SUCCESS) = AssistantBlock(
        id = id,
        kind = "tool_use",
        content = "",
        toolName = "bash",
        toolTitle = "bash",
        toolStatus = status,
    )

    private fun build(message: ChatMessage) = buildFlatChatItems(
        listOf(message),
        foldAiProcess = true,
    )

    private fun kinds(items: List<FlatChatItem>): List<String> = items.map { item ->
        when (item) {
            is FlatChatItem.UserBubble -> "user"
            is FlatChatItem.AssistantHeader -> "header"
            is FlatChatItem.AssistantText -> "text"
            is FlatChatItem.AssistantMarkdownBlock -> "md"
            is FlatChatItem.AssistantProcessSummary -> "summary"
            is FlatChatItem.AssistantThinking -> "thinking"
            is FlatChatItem.AssistantToolUse -> "tool:${item.block.toolName}"
            is FlatChatItem.AssistantInfo -> "info"
            is FlatChatItem.AssistantTyping -> "typing"
            is FlatChatItem.AssistantError -> "error"
            is FlatChatItem.AssistantLegacyContent -> "legacy"
        }
    }

    @Test
    fun `collapsed tool-only turn renders summary without header`() {
        val items = build(assistant(blocks = listOf(tool("t1"), tool("t2"))))
        val k = kinds(items)
        assertTrue("summary expected, got $k", k.contains("summary"))
        assertFalse("header must be hidden, got $k", k.contains("header"))
        assertFalse("tool cards must fold, got $k", k.any { it.startsWith("tool:") })
    }

    @Test
    fun `expanded turn restores header and tool cards`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(tool("t1"), tool("t2")))),
            foldAiProcess = true,
            expandedProcessIds = setOf("m1"),
        )
        val k = kinds(items)
        assertTrue("header expected, got $k", k.contains("header"))
        assertTrue("tool cards expected, got $k", k.any { it.startsWith("tool:") })
    }

    @Test
    fun `turn with reply text keeps header when folded`() {
        val msg = assistant(
            blocks = listOf(tool("t1"), AssistantBlock(id = "x1", kind = "text", content = "done")),
        )
        val k = kinds(build(msg))
        assertTrue("header expected, got $k", k.contains("header"))
        assertTrue(k.contains("summary"))
    }

    @Test
    fun `failed tool still flags summary`() {
        val items = build(
            assistant(blocks = listOf(tool("t1", ToolBlockStatus.FAILED))),
        )
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().first()
        assertTrue(summary.hasFailure)
    }
}
