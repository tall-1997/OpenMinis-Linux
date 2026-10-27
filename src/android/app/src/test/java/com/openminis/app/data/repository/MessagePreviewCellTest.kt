package com.openminis.app.data.repository

import com.openminis.app.data.body.ResourceLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-rerun-picker-preview] The re-run picker reads `messages.preview`,
 * and that cell is a raw head of parts_json — so the picker rendered
 * `[{"type":"text","value":…` instead of the message. [messagePreviewCell]
 * keeps the raw head (search LIKE and [isInterruptedTail] read it) and appends
 * the human text after [PREVIEW_SENTINEL]; the picker shows only the tail.
 */
class MessagePreviewCellTest {

    private fun shown(cell: String) = displayMessagePreview(cell)

    @Test
    fun `a user turn shows its text, not the json`() {
        val parts = """[{"type":"text","value":"你是谁"}]"""
        val cell = messagePreviewCell(parts)
        assertEquals("你是谁", shown(cell))
        assertFalse(shown(cell).contains("\"type\""))
    }

    @Test
    fun `the raw head still carries the type field the tail scan reads`() {
        val parts = """[{"type":"toolUse","value":{"name":"shell_execute"}}]"""
        val cell = messagePreviewCell(parts)
        val raw = cell.substringBefore(PREVIEW_SENTINEL)
        assertTrue(isInterruptedTail("ASSISTANT", raw))
    }

    @Test
    fun `a long turn stays bounded and still shows its text`() {
        val body = "甲".repeat(9_000)
        val cell = messagePreviewCell("""[{"type":"text","value":"$body"}]""")
        assertTrue(cell.length <= ResourceLimits.PREVIEW_BYTES + PREVIEW_SENTINEL.length + 100)
        assertTrue(shown(cell).startsWith("甲"))
    }

    @Test
    fun `a row with nothing to summarise keeps the raw head`() {
        val parts = """[{"type":"toolResult","value":{"output":"ok"}}]"""
        assertEquals(parts, messagePreviewCell(parts))
    }

    @Test
    fun `a legacy cell without the sentinel shows its text anyway`() {
        // Rows written before the sentinel existed hold only the raw head. The
        // picker recomputes the text from it, so an upgrade fixes the list
        // without a migration.
        val legacy = """[{"type":"text","value":"legacy row"}]"""
        assertEquals("legacy row", shown(legacy))
    }

    @Test
    fun `a legacy tool-result cell falls back to its raw head`() {
        val legacy = """[{"type":"toolResult","value":{"output":"ok"}}]"""
        assertEquals(legacy, shown(legacy))
    }
}
