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

    // [T-android-preview-reminder-leak] A user turn opens with a
    // system-reminder text part; the real text sits behind it.
    @Test
    fun `a reminder-first user turn shows the real text`() {
        val parts = """[{"type":"text","value":"<system-reminder>task tracker: step 1 done</system-reminder>"},{"type":"text","value":"修复登录页崩溃"}]"""
        val cell = messagePreviewCell(parts)
        assertEquals("修复登录页崩溃", shown(cell))
    }

    @Test
    fun `a legacy reminder-first cell shows the real text`() {
        val legacy = """[{"type":"text","value":"<system-reminder>task tracker: step 1 done</system-reminder>"},{"type":"text","value":"修复登录页崩溃"}]"""
        assertEquals("修复登录页崩溃", shown(legacy))
    }

    @Test
    fun `a reminder longer than the clean cap is still stripped`() {
        val longReminder = "<system-reminder>" + "x".repeat(600) + "</system-reminder>"
        val parts = """[{"type":"text","value":"$longReminder"},{"type":"text","value":"真实消息"}]"""
        val cell = messagePreviewCell(parts)
        val shownText = shown(cell)
        assertFalse(shownText.contains("x"))
        assertFalse(shownText.contains("system-reminder"))
        assertEquals("真实消息", shownText)
    }

    @Test
    fun `a blank tail written by the old writer is recomputed`() {
        // The pre-fix writer stored a blank tail when the first text part
        // cleaned to empty. The picker must recover the real text from the
        // raw head instead of collapsing to "(empty)".
        val parts = """[{"type":"text","value":"<system-reminder>task tracker: step 1 done</system-reminder>"},{"type":"text","value":"修复登录页崩溃"}]"""
        val buggyCell = parts + PREVIEW_SENTINEL + ""
        assertEquals("修复登录页崩溃", shown(buggyCell))
    }
}
