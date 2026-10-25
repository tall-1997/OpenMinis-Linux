package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateSnapshotsTest {
    @Test
    fun publishedMessages_areDetachedFromWorkingListsAndNestedBlocks() {
        val blocks = mutableListOf(
            AssistantBlock(id = "tool-1", kind = "tool_use", toolStatus = ToolBlockStatus.RUNNING),
        )
        val working = mutableListOf(
            ChatMessage(id = "m-1", role = "assistant", content = "partial", toolBlocks = blocks),
        )
        val flow = SnapshotMutableStateFlow<List<ChatMessage>>(
            emptyList(),
            ::snapshotChatMessages,
        )

        flow.value = working
        val published = flow.value
        val oldWindow = published.subList(0, published.size)

        working.add(ChatMessage(id = "m-2", role = "user", content = "next"))
        blocks[0] = blocks[0].copy(toolStatus = ToolBlockStatus.SUCCESS)
        blocks.add(AssistantBlock(id = "tool-2", kind = "tool_use"))

        assertEquals(1, published.size)
        assertEquals(ToolBlockStatus.RUNNING, published.single().toolBlocks.single().toolStatus)
        assertEquals(1, oldWindow.size)
        assertEquals("m-1", oldWindow.single().id)
        assertNotSame(working, published)
        assertNotSame(blocks, published.single().toolBlocks)
    }

    @Test
    fun streamingDeltaSnapshot_detachesToolBlocksFromCaller() {
        val blocks = mutableListOf(
            AssistantBlock(id = "tool-1", kind = "tool_use", toolStatus = ToolBlockStatus.PENDING),
        )
        val flow = SnapshotMutableStateFlow<Map<String, StreamingDelta>>(
            emptyMap(),
            ::snapshotStreamingDeltas,
        )

        flow.value = mapOf(
            "assistant-1" to StreamingDelta("text", blocks, isAwaitingModelResponse = false),
        )
        blocks[0] = blocks[0].copy(toolStatus = ToolBlockStatus.SUCCESS)
        blocks.clear()

        val published = flow.value.getValue("assistant-1")
        assertEquals(1, published.toolBlocks.size)
        assertEquals(ToolBlockStatus.PENDING, published.toolBlocks.single().toolStatus)
        assertTrue(published.toolBlocks !== blocks)
    }

    @Test
    fun ordinaryMutableStateFlowInput_isStillDeepCopiedByController() {
        val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        val streaming = MutableStateFlow<Map<String, StreamingDelta>>(emptyMap())
        val controller = StreamSessionController(
            scope = kotlinx.coroutines.test.TestScope(),
            messages = messages,
            streamingById = streaming,
            newlineFlushMinChars = 50,
            newlineFlushMaxLen = 5_000,
        )
        val blocks = mutableListOf(
            AssistantBlock(id = "tool-1", kind = "tool_use", toolStatus = ToolBlockStatus.RUNNING),
        )
        messages.value = listOf(ChatMessage("assistant-1", "assistant", ""))

        controller.updateAssistantMessage(
            id = "assistant-1",
            content = "text",
            isStreaming = true,
            toolBlocks = blocks,
        )
        blocks.clear()

        assertEquals(1, streaming.value.getValue("assistant-1").toolBlocks.size)
    }
}
