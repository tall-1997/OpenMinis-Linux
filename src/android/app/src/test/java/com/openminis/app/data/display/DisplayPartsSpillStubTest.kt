package com.openminis.app.data.display

import com.openminis.app.data.repository.PREVIEW_SENTINEL
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-body-spill-stub] Reading a spilled body must never end with the offload
 * placeholder on screen.
 *
 * The placeholder ("body stored (N bytes)") is valid JSON shaped like a normal
 * text message, so a hydration fallback that reaches `parts_json` parses it
 * happily and paints it as content. These pin the detection and the preview
 * fallback that replaces it.
 */
class DisplayPartsSpillStubTest {

    private fun textOf(note: String): String =
        JSONArray(note).optJSONObject(0)?.optString("value").orEmpty()

    @Test
    fun `the offload placeholder is recognised`() {
        assertTrue(DisplayParts.isStub("""[{"type":"text","value":"body stored (9000 bytes)"}]"""))
        assertTrue(DisplayParts.isStub("""[{"type":"text","value":"body omitted; store failed"}]"""))
    }

    @Test
    fun `real content is not mistaken for the placeholder`() {
        assertFalse(DisplayParts.isStub("""[{"type":"text","value":"body stored is a lie"}]"""))
        assertFalse(DisplayParts.isStub("""[{"type":"text","value":"hello"}]"""))
        assertFalse(DisplayParts.isStub("""[{"type":"toolUse","value":{}}]"""))
        assertFalse(DisplayParts.isStub(null))
        assertFalse(DisplayParts.isStub(""))
        assertFalse(DisplayParts.isStub("not json"))
    }

    @Test
    fun `the preview tail stands in for a missing body`() {
        val head = """[{"type":"text","val"""
        val preview = head + PREVIEW_SENTINEL + "这是那条消息的正文，原文读不到了"
        val note = DisplayParts.previewNote(preview)
        assertNotNull(note)
        // Only the human tail is shown; the truncated JSON head must not leak
        // into the bubble as content.
        assertEquals("这是那条消息的正文，原文读不到了", textOf(note!!))
    }

    @Test
    fun `a long preview tail is capped`() {
        val preview = "head" + PREVIEW_SENTINEL + "x".repeat(DisplayParts.PREVIEW_NOTE_CHARS + 500)
        val note = DisplayParts.previewNote(preview)!!
        val shown = textOf(note)
        assertEquals(DisplayParts.PREVIEW_NOTE_CHARS + 1, shown.length)
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun `an empty or blank preview yields no fallback`() {
        assertNull(DisplayParts.previewNote(null))
        assertNull(DisplayParts.previewNote(""))
        assertNull(DisplayParts.previewNote("   "))
        assertNull(DisplayParts.previewNote(PREVIEW_SENTINEL))
    }

    @Test
    fun `an inline body still needs no hydration`() {
        assertFalse(DisplayParts.needsHydration(null, 100, """[{"type":"text","value":"hi"}]"""))
        assertTrue(DisplayParts.needsHydration("bodies/ab", 100, "[]"))
    }

    @Test
    fun `the note envelope is what the bubble parses`() {
        assertEquals(1, JSONArray(DisplayParts.note("原文")).length())
    }
}
