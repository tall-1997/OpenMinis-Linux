package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** [T-thinking-tail-follow] user fling hands over, bottom snap re-arms. */
    @Test
    fun tailFollowPolicy() {
        val p = TailFollowPolicy()
        assertNull(p.onScroll(0, 1_000)) // first sample: no decision
        assertEquals(true, p.onScroll(976, 1_000)) // within 24px of bottom: follow
        assertNull(p.onScroll(970, 1_000)) // small move: keep current mode
        assertEquals(false, p.onScroll(500, 1_000)) // upward fling: user holds
        assertNull(p.onScroll(495, 1_000)) // still holding, small move
        assertEquals(true, p.onScroll(999, 1_000)) // back at bottom: re-arm
    }

    /** [T-thinking-tail-follow] degenerate unscrolled content (maxValue=0). */
    @Test
    fun tailFollowPolicyZeroMax() {
        val p = TailFollowPolicy()
        assertNull(p.onScroll(0, 0))
        assertNull(p.onScroll(0, 0))
    }
}
