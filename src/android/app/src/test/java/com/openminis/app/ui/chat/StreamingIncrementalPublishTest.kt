package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P1-12] The incremental side-channel publish path must be byte-equivalent
 * to the full-snapshot path it replaces: identical published values, with the
 * only difference being list reuse when the tool blocks are unchanged. The
 * [STREAM_INCREMENTAL_PUBLISH] switch flips between the two paths so this
 * suite can prove the equivalence in one process.
 */
class StreamingIncrementalPublishTest {

    private val blocks = listOf(
        AssistantBlock(
            id = "t1",
            kind = "tool_use",
            toolName = "shell_execute",
            toolTitle = "Shell command",
            toolStatus = ToolBlockStatus.RUNNING,
        ),
    )

    private fun controller(
        streaming: MutableStateFlow<Map<String, StreamingDelta>> = MutableStateFlow(emptyMap()),
        messages: MutableStateFlow<List<ChatMessage>> = MutableStateFlow(emptyList()),
    ) = StreamSessionController(
        scope = TestScope(),
        messages = messages,
        streamingById = streaming,
        newlineFlushMinChars = 50,
        newlineFlushMaxLen = 5_000,
    )

    @Test
    fun `incremental path reuses the published block list for unchanged blocks`() {
        val streaming = MutableStateFlow(emptyMap<String, StreamingDelta>())
        val c = controller(streaming)
        try {
            STREAM_INCREMENTAL_PUBLISH = true
            // First publish: prev == null counts as structural → immediate.
            c.updateAssistantMessage("a1", "Hello", true, blocks)
            val first = streaming.value.getValue("a1")
            assertEquals("Hello", first.content)
            assertEquals(blocks, first.toolBlocks)
            // Pure text append with element-wise-identical blocks: the awaiting
            // flip keeps the publish immediate while the block list stays equal
            // — the exact reuse condition. The published delta must share the
            // previously published immutable list instead of allocating a copy.
            c.updateAssistantMessage("a1", "Hello, world", true, blocks, isAwaitingModelResponse = true)
            val second = streaming.value.getValue("a1")
            assertEquals("Hello, world", second.content)
            assertSame(first.toolBlocks, second.toolBlocks)
            assertEquals(blocks, second.toolBlocks)
            assertTrue(second.isAwaitingModelResponse)
        } finally {
            STREAM_INCREMENTAL_PUBLISH = true
        }
    }

    @Test
    fun `switch off takes the full snapshot path with equal values`() {
        val streaming = MutableStateFlow(emptyMap<String, StreamingDelta>())
        val c = controller(streaming)
        try {
            STREAM_INCREMENTAL_PUBLISH = false
            c.updateAssistantMessage("a1", "Hello", true, blocks)
            val first = streaming.value.getValue("a1")
            c.updateAssistantMessage("a1", "Hello, world", true, blocks, isAwaitingModelResponse = true)
            val second = streaming.value.getValue("a1")
            // Byte-equivalence: same content, same awaiting flag, and a block
            // list equal to the caller's — only the identity reuse is absent.
            assertEquals("Hello", first.content)
            assertEquals("Hello, world", second.content)
            assertEquals(blocks, second.toolBlocks)
            assertTrue(second.isAwaitingModelResponse)
            assertNotSame(first.toolBlocks, second.toolBlocks)
        } finally {
            STREAM_INCREMENTAL_PUBLISH = true
        }
    }

    @Test
    fun `changed blocks still publish a fresh snapshot on the incremental path`() {
        val streaming = MutableStateFlow(emptyMap<String, StreamingDelta>())
        val c = controller(streaming)
        try {
            STREAM_INCREMENTAL_PUBLISH = true
            c.updateAssistantMessage("a1", "Hello", true, blocks)
            val first = streaming.value.getValue("a1")
            val settled = listOf(blocks[0].copy(toolStatus = ToolBlockStatus.SUCCESS))
            c.updateAssistantMessage("a1", "Hello", true, settled, isAwaitingModelResponse = true)
            val second = streaming.value.getValue("a1")
            assertEquals(settled, second.toolBlocks)
            assertNotSame(first.toolBlocks, second.toolBlocks)
        } finally {
            STREAM_INCREMENTAL_PUBLISH = true
        }
    }

    @Test
    fun `stream end drains the final content and clears the side channel`() {
        val streaming = MutableStateFlow(emptyMap<String, StreamingDelta>())
        val messages = MutableStateFlow(
            listOf(ChatMessage(id = "a1", role = "assistant", content = "", isStreaming = true)),
        )
        val c = controller(streaming, messages)
        try {
            STREAM_INCREMENTAL_PUBLISH = true
            c.updateAssistantMessage("a1", "final text", true, blocks)
            c.updateAssistantMessage("a1", "final text", false, blocks)
            assertEquals("final text", messages.value.single().content)
            assertEquals(false, messages.value.single().isStreaming)
            assertEquals(emptyMap<String, StreamingDelta>(), streaming.value)
        } finally {
            STREAM_INCREMENTAL_PUBLISH = true
        }
    }
}

/**
 * [P1-17] extractToolTitle is the single source for the model-provided
 * tool_title across the dispatch status, the guard results and the streaming
 * pill. Complete JSON wins, partial streaming JSON is the fallback, and a
 * blank/absent/undecodable title falls back to the caller's default.
 */
class ExtractToolTitleTest {

    @Test
    fun `complete json wins and blanks fall back`() {
        assertEquals(
            "Editing report.md",
            extractToolTitle("""{"path":"r.md","tool_title":"Editing report.md"}""", fallback = "F"),
        )
        // Explicit empty title → fallback (the unified semantics the call
        // sites' downstream isBlank handling already assumed).
        assertEquals("F", extractToolTitle("""{"tool_title":""}""", fallback = "F"))
        assertEquals("F", extractToolTitle("""{"path":"r.md"}""", fallback = "F"))
        assertEquals(null, extractToolTitle("""{"path":"r.md"}""", fallback = null))
    }

    @Test
    fun `partial streaming json resolves the title mid-string`() {
        assertEquals(
            "Editing Cha",
            extractToolTitle("""{"path":"chat.kt","tool_title": "Editing Cha""", fallback = "F"),
        )
        // Unclosed escaped quote still terminates at the escape boundary.
        assertEquals(
            "a\"b",
            extractToolTitle("""{"tool_title":"a\"b"}""", fallback = "F"),
        )
    }

    @Test
    fun `invalid json without the key falls back`() {
        assertEquals("canonical", extractToolTitle("not json at all", fallback = "canonical"))
        assertEquals("canonical", extractToolTitle("", fallback = "canonical"))
        // Malformed JSON that still carries the key resolves it (the widened
        // tolerance the streaming path relies on).
        assertEquals("T", extractToolTitle("""{"tool_title":"T""", fallback = "canonical"))
    }
}
