package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** [T-thinking-char-count] compact char-count formatting for the thinking row. */
class ChatProcessRowGuardsTest {
    @Test
    fun charCountCompact() {
        assertEquals("0", formatThinkingCharCount(0))
        assertEquals("999", formatThinkingCharCount(999))
        assertEquals("8.4k", formatThinkingCharCount(8_400))
        assertEquals("10k", formatThinkingCharCount(10_000))
        assertEquals("99k", formatThinkingCharCount(99_999))
        assertEquals("1.2M", formatThinkingCharCount(1_200_000))
    }
}
