package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-port-fold-tools] `applyToolFolds`: list-level folding, order preserved. */
class ApplyToolFoldsTest {

    private var n = 0

    private fun toolItem(status: ToolBlockStatus = ToolBlockStatus.SUCCESS, ms: Long = 0, msg: String = "m1"): FlatChatItem {
        val block = AssistantBlock(
            id = "t${n++}",
            kind = "tool_use",
            toolName = "shell_execute",
            toolStatus = status,
            durationMs = ms,
        )
        return FlatChatItem.AssistantToolUse(messageId = msg, block = block, allToolBlocks = emptyList())
    }

    private fun textItem(msg: String = "m1"): FlatChatItem =
        FlatChatItem.AssistantInfo(messageId = msg, block = AssistantBlock(id = "i${n++}", kind = "info"))

    @Test
    fun `two consecutive tools become one group`() {
        val items = listOf(toolItem(), toolItem(ms = 1000), textItem())
        val folded = applyToolFolds(items)
        assertEquals(2, folded.size)
        val group = folded[0] as FlatChatItem.AssistantToolGroup
        assertEquals(2, group.toolCount)
        assertEquals(1000L, group.durationMs)
        assertTrue(folded[1] is FlatChatItem.AssistantInfo)
    }

    @Test
    fun `a lone tool is left alone`() {
        val items = listOf(toolItem(), textItem())
        assertEquals(items, applyToolFolds(items))
    }

    @Test
    fun `an unfinished tool keeps the run unfolded`() {
        val items = listOf(toolItem(), toolItem(status = ToolBlockStatus.RUNNING), textItem())
        assertEquals(items, applyToolFolds(items))
    }

    @Test
    fun `failures and cancellations are counted`() {
        val items = listOf(
            toolItem(status = ToolBlockStatus.FAILED),
            toolItem(status = ToolBlockStatus.CANCELLED),
            textItem(),
        )
        val group = applyToolFolds(items)[0] as FlatChatItem.AssistantToolGroup
        assertEquals(1, group.failedCount)
        assertEquals(1, group.cancelledCount)
    }

    @Test
    fun `runs from different messages never merge`() {
        val items = listOf(toolItem(msg = "a"), toolItem(msg = "b"), textItem())
        assertEquals(items, applyToolFolds(items))
    }

    @Test
    fun `the order of the surrounding items is preserved`() {
        val before = textItem()
        val after = textItem()
        val items = listOf(before, toolItem(), toolItem(), after)
        val folded = applyToolFolds(items)
        assertEquals(3, folded.size)
        assertTrue(folded.first() === before)
        assertTrue(folded.last() === after)
        assertTrue(folded[1] is FlatChatItem.AssistantToolGroup)
    }

    // [FIX-R2-14] A run nothing non-tool follows folds against nothing: it
    // stays as rows the user can open and compare (a turn cancelled after its
    // tools, or the list simply ending on a run).
    @Test
    fun `a run at the end of the list stays unfolded`() {
        val items = listOf(toolItem(), toolItem())
        assertEquals(items, applyToolFolds(items))
    }

    @Test
    fun `a run followed by the next message's tools stays unfolded`() {
        val items = listOf(toolItem(msg = "a"), toolItem(msg = "a"), toolItem(msg = "b"), toolItem(msg = "b"))
        assertEquals(items, applyToolFolds(items))
    }
}
