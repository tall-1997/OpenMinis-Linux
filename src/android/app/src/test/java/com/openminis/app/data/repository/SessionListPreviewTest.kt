package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-session-list-stub-preview] The session list's `last_message` must never
 * show the offload placeholder.
 *
 * The bug this pins: appendMessage computed the session-list preview with
 * `extractTextPreview(stored.inline)`. For an offloaded row stored.inline IS
 * the stub — `[{"type":"text","value":"body stored (2232 bytes)"}]` — which is
 * valid JSON shaped exactly like a normal text message, so the extractor
 * happily summarised the placeholder and painted "body stored (2232 bytes)"
 * into the session list as if the model had said it.
 *
 * The fix: stub rows rebuild the preview from the `preview` cell, where
 * messagePreviewCell already stored the real text after PREVIEW_SENTINEL
 * (computed from the FULL body BEFORE offload). Non-stub rows keep the
 * extractTextPreview behaviour unchanged.
 */
class SessionListPreviewTest {

    private fun preview(inline: String?, previewCell: String?): String? =
        PreviewText.sessionListPreview(inline, previewCell)

    private fun stub(note: String): String =
        com.openminis.app.data.display.DisplayParts.note(note)

    // ─── The shape that caused the bug ───────────────────────────────

    @Test
    fun `an offloaded stub row rebuilds the preview from the preview cell`() {
        val inline = stub("body stored (2232 bytes)")
        val previewCell = messagePreviewCell("""[{"type":"text","value":"真正的消息正文"}]""")
        assertEquals("真正的消息正文", preview(inline, previewCell))
    }

    @Test
    fun `the stub text itself is never treated as content`() {
        // Direct regression pin: extractTextPreview(stub) returns the
        // placeholder — exactly what leaked into sessions.last_message
        // before the fix. With no preview cell the rebuild must yield null,
        // never the placeholder.
        val inline = stub("body stored (2232 bytes)")
        assertEquals("body stored (2232 bytes)", PreviewText.extractTextPreview(inline))
        assertNull(preview(inline, null))
    }

    @Test
    fun `an omitted stub row rebuilds the same way`() {
        val inline = stub("body omitted; store failed")
        val previewCell = messagePreviewCell("""[{"type":"text","value":"正文还在"}]""")
        assertEquals("正文还在", preview(inline, previewCell))
    }

    @Test
    fun `a stub row whose preview cell has no sentinel falls back to the raw head`() {
        // toolResult rows yield no extractable text, so messagePreviewCell
        // returns the raw head without a sentinel. The rebuild recomputes
        // from that head — which for a tool-result row is still nothing,
        // matching the flicker guard: no preview, never the placeholder.
        val inline = stub("body stored (13 bytes)")
        val rawHead = """[{"type":"toolResult","value":{"output":"ok"}}]"""
        assertNull(preview(inline, rawHead))
    }

    @Test
    fun `a stub row with a null or blank preview cell yields null`() {
        assertNull(preview(stub("body stored (5 bytes)"), null))
        assertNull(preview(stub("body stored (5 bytes)"), ""))
        assertNull(preview(stub("body stored (5 bytes)"), "   "))
    }

    // ─── Non-stub rows: behaviour unchanged ──────────────────────────

    @Test
    fun `a normal text row keeps the extractTextPreview behaviour`() {
        val inline = """[{"type":"text","value":"普通消息"}]"""
        assertEquals("普通消息", preview(inline, null))
    }

    @Test
    fun `a tool-use row still yields its summary`() {
        val inline =
            """[{"type":"toolUse","value":{"name":"shell_execute","description":"Install Git"}}]"""
        assertEquals(
            PreviewText.extractTextPreview(inline),
            preview(inline, null),
        )
    }

    @Test
    fun `a tool-result row still yields null (flicker guard intact)`() {
        val inline = """[{"type":"toolResult","value":{"output":"ok"}}]"""
        assertNull(preview(inline, null))
    }

    @Test
    fun `real text starting with the stub prefix still shows its text`() {
        // isStub flags a single text part starting with the prefix even when
        // it is a real message — but the rebuild path reads the preview cell,
        // whose tail IS that text, so the user still sees it. Never the
        // extractor-on-stub path.
        val inline = """[{"type":"text","value":"body stored ( 是什么意思"}]"""
        val previewCell = messagePreviewCell(inline)
        assertEquals("body stored ( 是什么意思", preview(inline, previewCell))
    }

    @Test
    fun `null and blank inline yield null`() {
        assertNull(preview(null, null))
        assertNull(preview("", null))
        assertNull(preview("   ", """[{"type":"text","value":"x"}]"""))
    }
}
