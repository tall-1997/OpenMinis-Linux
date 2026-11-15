package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-mem-probe-trust] The [CtxShape] line builder, extracted from
 * runAgentLoopBody so the counting pass is pure. Pins the arithmetic the
 * extraction must not have moved (the original inline block's numbers are the
 * oracle) and the [T-thinking-dual-ledger] addition: the requested thinking
 * tier rides on the same line.
 */
class ContextShapeStatsTest {

    private fun text(text: String) = AgentContentPart.Text(text)

    @Test
    fun `counts text tool results and images like the original inline block`() {
        val toolUseId = "t1"
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "hello"), // 5 chars
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "abc", // 3 chars
                contentParts = listOf(
                    AgentContentPart.ToolResult(
                        id = toolUseId,
                        name = "grep",
                        content = "12345", // 5 chars
                        isError = false,
                    ),
                    text("de"), // 2 chars
                ),
            ),
        )
        val stats = contextShapeStats(history)
        assertEquals(2, stats.historySize)
        assertEquals(15L, stats.totalChars)
        // max per-message: user=5, assistant=3+5+2=10
        assertEquals(10, stats.maxMsgChars)
        assertEquals("ASSISTANT", stats.maxMsgRole)
        assertEquals(1, stats.toolResultParts)
        assertEquals(0, stats.imageParts)
        assertEquals(0L, stats.imageBytes)
        assertEquals(0L, stats.audioB64Chars)
        assertEquals(3L, stats.approxTokens)
    }

    @Test
    fun `counts images on results inline parts and message parts separately`() {
        val inline = ByteArray(11)
        val history = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(AgentContentPart.ImageData(ByteArray(7), "image/png")),
            ),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult(
                        id = "t",
                        name = "n",
                        content = "",
                        isError = false,
                        imageData = inline,
                    ),
                ),
            ),
        )
        val stats = contextShapeStats(history)
        // Inline result bytes + ImageData part bytes are tracked separately
        // from the char count, exactly like the original block.
        assertEquals(2, stats.imageParts)
        assertEquals(18L, stats.imageBytes)
        assertEquals(0L, stats.totalChars)
    }

    @Test
    fun `line carries the shape and the requested thinking tier`() {
        val stats = contextShapeStats(listOf(LLMMessage(LLMMessage.Role.USER, "abcd")))
        val line = stats.line(ThinkingLevel.HIGH)
        assertTrue(line.startsWith("[CtxShape] "))
        assertTrue("historySize in line", line.contains("historySize=1"))
        assertTrue("totalChars in line", line.contains("totalChars=4"))
        assertTrue("approxTokens in line", line.contains("approxTokens=1"))
        // [T-thinking-dual-ledger] The request side of the ledger on the log line.
        assertTrue("thinkingLevel in line", line.contains("thinkingLevel=HIGH"))
        // The runtime memory snapshot stays the CALLER's appendage (Android-bound).
        assertTrue("no memory snapshot in the pure line", !line.contains("mem"))
    }

    @Test
    fun `empty history renders a sane zero line`() {
        val stats = contextShapeStats(emptyList())
        assertEquals(0, stats.historySize)
        assertEquals(0L, stats.totalChars)
        assertEquals("", stats.maxMsgRole)
        val line = stats.line(ThinkingLevel.OFF)
        assertTrue(line.contains("maxMsgChars=0"))
        assertTrue(line.contains("thinkingLevel=OFF"))
    }
}
