package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputSpillPreviewBoundaryTest {
    private val emoji = "\uD83D\uDE00"
    private val half = ToolOutputSpill.PREVIEW_BODY_CHARS / 2
    private val middle = "m".repeat(ToolOutputSpill.LIMIT)

    private fun preview(output: String): String = ToolOutputSpill.cappedPreview("test_tool", output).also {
        assertEquals(it, it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }

    @Test
    fun `tail window starting mid pair drops the pair whole`() {
        val output = "x".repeat(half) + middle + emoji + "y".repeat(half - 1)
        val result = preview(output)
        assertFalse(result.contains(emoji))
        assertEquals("y".repeat(half - 1), result.substringAfterLast("…\n\n"))
    }

    @Test
    fun `head window ending mid pair drops the pair whole`() {
        val output = "x".repeat(half - 1) + emoji + middle + "y".repeat(half)
        val body = ToolOutputSpill.previewBody(preview(output))
        assertTrue(body.startsWith("x".repeat(half - 1) + "\n\n…"))
        assertFalse(body.contains(emoji))
    }

    @Test
    fun `emoji fully inside the tail window survives as a complete pair`() {
        val tail = "z" + emoji + "y".repeat(half - 3)
        val result = preview("x".repeat(half) + middle + tail)
        assertEquals(tail, result.substringAfterLast("…\n\n"))
    }

    @Test
    fun `fitting body does not lose text between slices`() {
        val output = "x".repeat(half - 1) + emoji + "y".repeat(half - 1)
        assertEquals(output, ToolOutputSpill.previewBody(preview(output)))
    }
}
