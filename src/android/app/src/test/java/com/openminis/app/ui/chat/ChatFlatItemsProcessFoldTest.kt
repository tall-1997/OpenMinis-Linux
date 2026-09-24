package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFlatItemsProcessFoldTest {

    private fun thinking(id: String = "th1") =
        AssistantBlock(id = id, kind = "thinking", content = "reason")

    private fun text(id: String = "tx1", content: String = "hello") =
        AssistantBlock(id = id, kind = "text", content = content)

    private fun tool(
        id: String = "tool1",
        name: String = "bash",
        status: ToolBlockStatus = ToolBlockStatus.SUCCESS,
    ) = AssistantBlock(
        id = id,
        kind = "tool_use",
        toolName = name,
        toolStatus = status,
        toolTitle = name,
    )

    private fun assistant(
        streaming: Boolean = false,
        awaiting: Boolean = false,
        blocks: List<AssistantBlock>,
        role: String = "assistant",
        id: String = "m1",
    ) = ChatMessage(
        id = id,
        role = role,
        content = "",
        isStreaming = streaming,
        isAwaitingModelResponse = awaiting,
        toolBlocks = blocks,
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
    fun `fold off keeps thinking tools and text with no summary`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(), text()))),
            showCompletedToolCards = true,
            foldAiProcess = false,
        )
        assertFalse(kinds(items).contains("summary"))
        assertTrue(kinds(items).contains("thinking"))
        assertTrue(kinds(items).contains("tool:bash"))
        assertTrue(kinds(items).contains("md"))
    }

    @Test
    fun `fold on finished collapses process and keeps every text block`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    blocks = listOf(
                        thinking(),
                        tool(),
                        text(id = "tx1", content = "one"),
                        text(id = "tx2", content = "two"),
                    ),
                ),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertEquals(listOf("header", "md", "md", "summary"), k)
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertEquals(1, summary.thinkingCount)
        assertEquals(1, summary.toolCount)
        assertFalse(summary.expanded)
        assertFalse(summary.hasFailure)
        val blocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertFalse(blocks[0].showTranslate)
        assertTrue(blocks[1].showTranslate)
        assertTrue(blocks[1].translateWholeReply)
        assertEquals("one\n\ntwo", blocks[1].segmentText)
    }

    @Test
    fun `fold off translates each text block instead of the whole reply`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    blocks = listOf(
                        text(id = "tx1", content = "one"),
                        text(id = "tx2", content = "two"),
                    ),
                ),
            ),
            showCompletedToolCards = true,
            foldAiProcess = false,
        )
        val blocks = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].showTranslate)
        assertFalse(blocks[0].translateWholeReply)
        assertEquals("one", blocks[0].segmentText)
        assertTrue(blocks[1].showTranslate)
        assertEquals("two", blocks[1].segmentText)
    }

    @Test
    fun `fold on live thinking stays expanded until a later block arrives`() {
        val items = buildFlatChatItems(
            listOf(assistant(streaming = true, blocks = listOf(thinking()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertFalse(k.contains("summary"))
        assertTrue(k.contains("thinking"))
    }

    @Test
    fun `fold on streaming collapses finished thinking while current tool stays`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    streaming = true,
                    blocks = listOf(thinking(), tool(status = ToolBlockStatus.RUNNING), text()),
                ),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertTrue(k.contains("summary"))
        assertFalse(k.contains("thinking"))
        assertTrue(k.contains("tool:bash"))
        assertTrue(k.contains("md"))
    }

    @Test
    fun `tap expand restores thinking and completed tools`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
            expandedProcessIds = setOf("m1"),
        )
        val k = kinds(items)
        assertTrue(k.contains("summary"))
        assertTrue(k.contains("thinking"))
        assertTrue(k.contains("tool:bash"))
        assertTrue(k.contains("md"))
        assertTrue(items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single().expanded)
    }

    @Test
    fun `ask_user_question stays visible while process is folded`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    blocks = listOf(
                        thinking(),
                        tool(id = "q", name = "ask_user_question", status = ToolBlockStatus.RUNNING),
                        tool(),
                        text(),
                    ),
                ),
            ),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertTrue(k.contains("summary"))
        assertTrue(k.contains("tool:ask_user_question"))
        assertFalse(k.contains("tool:bash"))
        assertFalse(k.contains("thinking"))
        assertTrue(k.contains("md"))
    }

    @Test
    fun `system info rows are not folded`() {
        val items = buildFlatChatItems(
            listOf(
                assistant(
                    role = "system",
                    blocks = listOf(AssistantBlock(id = "i1", kind = "info", content = "note")),
                ),
            ),
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertFalse(k.contains("summary"))
        assertTrue(k.contains("info"))
    }

    @Test
    fun `failed tool is flagged on the summary`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(tool(status = ToolBlockStatus.FAILED)))),
            foldAiProcess = true,
        )
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertTrue(summary.hasFailure)
        assertEquals(1, summary.toolCount)
        assertEquals(0, summary.thinkingCount)
    }

    @Test
    fun `fold on awaiting collapses completed process`() {
        val items = buildFlatChatItems(
            listOf(assistant(awaiting = true, blocks = listOf(thinking(), tool(), text()))),
            showCompletedToolCards = false,
            foldAiProcess = true,
        )
        val k = kinds(items)
        assertTrue(k.contains("summary"))
        assertFalse(k.contains("thinking"))
        assertFalse(k.contains("tool:bash"))
        assertTrue(k.contains("md"))
    }

    @Test
    fun `floating overlay hides completed tools when fold is on`() {
        val done = tool(status = ToolBlockStatus.SUCCESS)
        val running = tool(id = "t2", status = ToolBlockStatus.RUNNING)
        assertTrue(isFloatingProcessTool(done, foldAiProcess = false))
        assertFalse(isFloatingProcessTool(done, foldAiProcess = true))
        assertTrue(isFloatingProcessTool(running, foldAiProcess = true))
        assertFalse(isFloatingProcessTool(thinking(), foldAiProcess = false))
    }

    @Test
    fun `folded summary still exposes tool chips for detail`() {
        val items = buildFlatChatItems(
            listOf(assistant(blocks = listOf(thinking(), tool(id = "tool1", name = "bash"), text()))),
            foldAiProcess = true,
        )
        val summary = items.filterIsInstance<FlatChatItem.AssistantProcessSummary>().single()
        assertEquals(1, summary.processTools.size)
        assertEquals("tool1", summary.processTools.single().id)
        assertEquals("bash", summary.processTools.single().title)
    }

    @Test
    fun `detail sheet helper keeps completed tools when fold hides overlay`() {
        val done = tool(id = "done1", status = ToolBlockStatus.SUCCESS)
        val running = tool(id = "run1", status = ToolBlockStatus.RUNNING)
        val msgs = listOf(assistant(blocks = listOf(thinking(), done, running, text())))
        val all = assistantToolUseBlocks(msgs)
        assertEquals(listOf("done1", "run1"), all.map { it.id })
        assertTrue(isDetailProcessTool(done))
        assertFalse(isFloatingProcessTool(done, foldAiProcess = true))
        assertTrue(isFloatingProcessTool(running, foldAiProcess = true))
    }
}
