package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFlatItemsGroupSpeakerTest {

    private fun bubble(id: String, speaker: String, text: String) = ChatMessage(
        id = id,
        role = "assistant",
        content = text,
        speakerName = speaker,
        speakerVendor = speaker,
    )

    @Test
    fun `different group speakers each keep a header`() {
        val items = buildFlatChatItems(
            listOf(
                bubble("a", "glm-5.3", "first stance"),
                bubble("b", "claude", "second stance"),
            ),
        )
        val headers = items.filterIsInstance<FlatChatItem.AssistantHeader>()
        assertEquals(listOf("glm-5.3", "claude"), headers.map { it.speakerName })
    }

    @Test
    fun `live group speaker keeps the current tool card`() {
        val running = ChatMessage(
            id = "live",
            role = "assistant",
            content = "",
            speakerName = "glm-5.3",
            speakerVendor = "zhipu",
            isStreaming = true,
            toolBlocks = listOf(
                AssistantBlock(
                    id = "t1",
                    kind = "tool_use",
                    toolName = "bash",
                    toolTitle = "bash",
                    toolStatus = ToolBlockStatus.RUNNING,
                ),
            ),
        )
        val live = buildFlatChatItems(listOf(running), foldAiProcess = true)
        assertEquals(true, live.any { it is FlatChatItem.AssistantToolUse })

        val done = buildFlatChatItems(
            listOf(running.copy(isStreaming = false, content = "结论", toolBlocks = emptyList())),
        )
        assertEquals(false, done.any { it is FlatChatItem.AssistantToolUse })
        assertEquals(true, done.any { it is FlatChatItem.AssistantLegacyContent })
    }

    @Test
    fun `same unnamed assistant continuation still shares one header`() {
        val items = buildFlatChatItems(
            listOf(
                ChatMessage(id = "a", role = "assistant", content = "part"),
                ChatMessage(id = "b", role = "assistant", content = "more"),
            ),
        )
        val headers = items.filterIsInstance<FlatChatItem.AssistantHeader>()
        assertEquals(1, headers.size)
    }
}
