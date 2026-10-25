package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A completed sub-agent used to vanish from the session page: the top bar only
 * renders RUNNING members, and the timeline hid sub-agent transcript cards
 * whenever the bar was enabled — including after the run finished. Cards must
 * be hidden only while their run is still live in the bar.
 */
class ChatFlatItemsSubAgentVisibilityTest {
    private fun spawnBlock(id: String) = AssistantBlock(
        id = id,
        kind = "tool_use",
        toolName = "spawn_agent",
        toolTitle = "sub-agent",
    )

    private fun message(id: String, vararg blocks: AssistantBlock) = ChatMessage(
        id = id,
        role = "assistant",
        content = "",
        toolBlocks = blocks.toList(),
    )

    private fun toolCardIds(items: List<FlatChatItem>) =
        items.filterIsInstance<FlatChatItem.AssistantToolUse>().map { it.block.id }

    @Test
    fun completedSubAgentCardIsVisibleWhenNoRunIsLive() {
        val items = buildFlatChatItems(listOf(message("m", spawnBlock("t1"))))
        assertEquals(listOf("t1"), toolCardIds(items))
    }

    @Test
    fun liveSubAgentCardIsHiddenWhileItsRunIsInTheBar() {
        val items = buildFlatChatItems(
            listOf(message("m", spawnBlock("t1"))),
            activeSubAgentToolIds = setOf("t1"),
        )
        assertEquals(emptyList<String>(), toolCardIds(items))
    }

    @Test
    fun onlyTheLiveRunIsHiddenWhenSeveralSubAgentsExist() {
        val items = buildFlatChatItems(
            listOf(message("m", spawnBlock("done1"), spawnBlock("live1"))),
            activeSubAgentToolIds = setOf("live1"),
        )
        assertEquals(listOf("done1"), toolCardIds(items))
    }

    @Test
    fun batchSubCardsMatchByParentToolId() {
        val items = buildFlatChatItems(
            listOf(message("m", spawnBlock("t9#sub-2"))),
            activeSubAgentToolIds = setOf("t9"),
        )
        assertEquals(emptyList<String>(), toolCardIds(items))
    }
}
