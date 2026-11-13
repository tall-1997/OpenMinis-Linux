package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-hidden-user-note] PreviewText is the SINGLE stripping authority for host
 * framing (reminder blocks + the legacy #339 no-reply head). Every preview /
 * export / display path routes through [PreviewText.stripSystemReminders];
 * these tests pin that authority so the four hand-rolled copies that drifted
 * apart (session list, txt export, md export, in-app display) can never
 * diverge again.
 */
class PreviewTextStripAuthorityTest {

    /** The exact frame #339 produced: head OUTSIDE the reminder block. */
    private fun legacyNote(inner: String): String =
        "${PreviewText.HOST_NOTE_NO_REPLY_HEAD}\n<system-reminder>\n$inner\n</system-reminder>"

    @Test
    fun `legacy head outside the reminder block is stripped`() {
        val out = PreviewText.stripSystemReminders(legacyNote("## Recalled relevant memories"))
        assertFalse(out.contains("HARNESS NOTE"))
        assertFalse(out.contains("<system-reminder>"))
        assertFalse(out.contains("## Recalled"))
    }

    @Test
    fun `a block-only part collapses to empty`() {
        assertEquals("", PreviewText.stripSystemReminders(legacyNote("anything")))
    }

    @Test
    fun `real user text around the frame survives`() {
        val raw = "帮我修这个 bug\n${legacyNote("Current objective — x")}\n谢谢"
        val out = PreviewText.stripSystemReminders(raw)
        assertTrue(out.contains("帮我修这个 bug"))
        assertTrue(out.contains("谢谢"))
        assertFalse(out.contains("HARNESS NOTE"))
        assertFalse(out.contains("Current objective"))
    }

    @Test
    fun `back-to-back blocks do not merge into one match`() {
        val raw = "<system-reminder>a</system-reminder>\n<system-reminder>b</system-reminder>"
        assertEquals("", PreviewText.stripSystemReminders(raw))
    }

    @Test
    fun `text without framing is untouched`() {
        assertEquals("普通文本", PreviewText.stripSystemReminders("普通文本"))
    }

    /** JSON-escape a frame so it can be embedded in a parts_json literal. */
    private fun jsonEscaped(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    @Test
    fun `session-list preview never paints the head`() {
        val inline = """[{"type":"text","value":"${jsonEscaped(legacyNote("## Recalled relevant memories"))}"}]"""
        // The frame-only row collapses to blank → null (caller keeps existing behaviour).
        assertNull(PreviewText.extractTextPreview(inline))
    }

    @Test
    fun `session-list preview of a real message next to a note stays clean`() {
        val inline = """[{"type":"text","value":"先修编译"},{"type":"text","value":"${jsonEscaped(legacyNote("Session state restored by the host"))}"}]"""
        val preview = PreviewText.extractTextPreview(inline)
        assertEquals("先修编译", preview)
    }
}
