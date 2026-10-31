package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streaming overlay must not copy the whole transcript per tick: frozen
 * messages stay reference-identical, and only the streamed row is rebuilt.
 */
class StreamingOverlayTest {

    private val base = listOf(
        ChatMessage(id = "u1", role = "user", content = "hi"),
        ChatMessage(id = "a1", role = "assistant", content = "partial"),
        ChatMessage(id = "a2", role = "assistant", content = "other"),
    )

    @Test
    fun emptyStreamingReturnsTheSameList() {
        assertSame(base, mergeStreamingOverlay(base, emptyMap()))
    }

    @Test
    fun onlyTheStreamedRowIsMaterialized() {
        val merged = mergeStreamingOverlay(
            base,
            mapOf(
                "a1" to StreamingDelta(
                    content = "partial reply",
                    toolBlocks = emptyList(),
                    isAwaitingModelResponse = false,
                ),
            ),
        )
        assertEquals(base.size, merged.size)
        assertSame(base[0], merged[0])
        assertSame(base[2], merged[2])
        assertTrue(merged[1].isStreaming)
        assertEquals("partial reply", merged[1].content)
        assertFalse(merged[0].isStreaming)
    }

    @Test
    fun overlaidRowIsCachedPerIndex() {
        val merged = mergeStreamingOverlay(
            base,
            mapOf("a1" to StreamingDelta("x", emptyList(), false)),
        )
        assertSame(merged[1], merged[1])
    }

    // ── [P1-13] latestAssistantWithOverlay: tail-only projection that must
    // stay byte-equivalent to mergeStreamingOverlay(...).lastOrNull { assistant } ──

    @Test
    fun tailOnlyProjectionMatchesTheMergedView() {
        // The delta targets the LAST assistant (a2) — the tail the projection
        // reads. A delta on a NON-tail message is covered below.
        val delta = StreamingDelta(
            content = "a2 live text",
            toolBlocks = listOf(AssistantBlock(id = "t1", kind = "tool_use", toolName = "ui_read")),
            isAwaitingModelResponse = false,
        )
        val streaming = mapOf("a2" to delta)
        val mergedLast = mergeStreamingOverlay(base, streaming).lastOrNull { it.role == "assistant" }
        val tailOnly = latestAssistantWithOverlay(base, streaming)
        assertEquals(mergedLast, tailOnly)
        assertEquals("a2 live text", tailOnly!!.content)
        assertTrue(tailOnly.isStreaming)
        assertEquals(delta.toolBlocks, tailOnly.toolBlocks)
    }

    @Test
    fun tailOnlyProjectionHandlesBoundaryCases() {
        // No assistant message at all → null, like the merged view.
        assertEquals(null, latestAssistantWithOverlay(listOf(base[0]), emptyMap()))
        // Empty streaming map → canonical message untouched.
        assertSame(base[2], latestAssistantWithOverlay(base, emptyMap()))
        // A delta targeting a NON-tail message leaves the last assistant
        // canonical — identical to the merged view (the merge only rewrites
        // the delta-keyed row, and the LAST assistant here is a2).
        val stale = mapOf("a1" to StreamingDelta("stale", emptyList(), false))
        assertSame(base[2], latestAssistantWithOverlay(base, stale))
        // No assistant → null even with a dangling delta.
        assertEquals(null, latestAssistantWithOverlay(listOf(base[0]), stale))
    }
}
