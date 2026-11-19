package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFetchToolSpillTest {
    private val url = "https://example.test/document"

    @Test
    fun `sessionless fetch hands off the complete extracted text`() {
        val html = "<article>" + "H".repeat(8_000) + "M".repeat(20_000) + "T".repeat(5_000) + "</article>"
        val extracted = WebFetchTool.htmlToText(html)
        assertEquals("URL: $url\n\n${html.removePrefix("<article>").removeSuffix("</article>")}",
            WebFetchTool.presentResponse(url, extracted, null, null))
    }

    @Test
    fun `unified exit keeps final URL and end of a page in the short preview`() {
        val body = "INTRO\n" + "middle".repeat(5_000) + "\nCONCLUSION"
        val output = WebFetchTool.presentResponse(url, body, null, null)
        val preview = ToolOutputPolicy.apply(output, "fetch", null, null, WebFetchTool.NAME)
        assertTrue(preview.startsWith("[tool-output-spill] web_fetch"))
        assertTrue(preview.contains("URL: $url\n\nINTRO"))
        assertTrue(preview.endsWith("CONCLUSION"))
        assertTrue(ToolOutputSpill.previewBody(preview).length <= ToolOutputSpill.PREVIEW_BODY_CHARS + 40)
        assertFalse(preview.contains("minis://workspace"))
        assertNull(ToolOutputSpill.parseGuestPath(preview))
    }
}
