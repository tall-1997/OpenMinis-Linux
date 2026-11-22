package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fold-expand-members] A folded tool group must be able to show its members.
 *
 * The group used to carry only `memberIds`, so the expanded row had nothing to
 * render — the chevron turned and no member ever appeared. The fold site now
 * keeps the real blocks, and the row renders them as ToolCallPills.
 */
class ToolGroupMemberBlocksTest {

    private var n = 0

    private fun toolItem(): FlatChatItem.AssistantToolUse {
        val block = AssistantBlock(
            id = "t${n++}",
            kind = "tool_use",
            toolName = "shell_execute",
            toolStatus = ToolBlockStatus.SUCCESS,
        )
        return FlatChatItem.AssistantToolUse(messageId = "m1", block = block, allToolBlocks = emptyList())
    }

    private fun textItem(): FlatChatItem =
        FlatChatItem.AssistantInfo(messageId = "m1", block = AssistantBlock(id = "i${n++}", kind = "info"))

    @Test
    fun aFoldedGroupCarriesTheMemberBlocksItNeedsToExpand() {
        val items = listOf(toolItem(), toolItem(), toolItem(), textItem())
        val group = applyToolFolds(items).filterIsInstance<FlatChatItem.AssistantToolGroup>().singleOrNull()
        assertTrue("three consecutive tools fold into one group", group != null)
        assertEquals("the group keeps every member block", 3, group!!.memberBlocks.size)
        assertEquals("member ids still match", 3, group.memberIds.size)
    }

    @Test
    fun expandingOnlyFlipsTheFlagAndKeepsTheBlocks() {
        val group = applyToolFolds(listOf(toolItem(), toolItem(), textItem()))
            .filterIsInstance<FlatChatItem.AssistantToolGroup>().single()
        val expanded = group.copy(expanded = true)
        assertEquals("expanding must not lose the member blocks", group.memberBlocks, expanded.memberBlocks)
        assertEquals(group.memberIds, expanded.memberIds)
    }
}
