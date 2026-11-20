package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenScanMemoTest {
    @Test
    fun `unchanged sentinel returns the caller's own string on equal key hit`() {
        val memo = NeutralizedTextMemo(maxEntries = 4)
        val first = String("verbatim body".toCharArray())
        val second = String(first.toCharArray())
        assertNotSame(first, second)
        assertSame(first, memo.get(first))
        assertSame(second, memo.get(second))
        assertEquals(1, memo.size)
    }

    @Test
    fun `memo uses the fixed rewrite and bounds entries and retained output`() {
        val memo = NeutralizedTextMemo(maxEntries = 2, maxOutputChars = 64)
        val input = "<system-reminder>first</system-reminder>"
        val escaped = memo.get(input)
        assertEquals(neutralizeUntrustedTags(input), escaped)
        assertSame(escaped, memo.get(String(input.toCharArray())))

        val oversized = "<system-reminder>" + "x".repeat(100)
        assertEquals(neutralizeUntrustedTags(oversized), memo.get(oversized))
        assertEquals(1, memo.size)

        val additional = (1..10).map { "[MEMORY_REFERENCE $it]" }
        additional.forEach { assertEquals(neutralizeUntrustedTags(it), memo.get(it)) }
        assertTrue(memo.size <= 2)
    }

    @Test
    fun `mutating history in place still projects newly inserted results`() {
        fun result(text: String) = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(AgentContentPart.ToolResult("call", "web_fetch", text)),
        )
        val history = mutableListOf(result("safe text"))
        assertSame(history, neutralizeRemoteToolResults(history))
        history += result("<context-summary>external text</context-summary>")
        val projected = neutralizeRemoteToolResults(history)
        assertSame(history[0], projected[0])
        assertEquals(
            "&lt;context-summary>external text&lt;/context-summary>",
            (projected[1].contentParts.single() as AgentContentPart.ToolResult).content,
        )
    }
}
