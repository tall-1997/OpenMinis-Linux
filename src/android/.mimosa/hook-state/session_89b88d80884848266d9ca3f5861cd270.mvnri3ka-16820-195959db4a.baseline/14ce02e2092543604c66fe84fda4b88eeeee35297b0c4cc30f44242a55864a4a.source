package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputSpillParseTest {
    @Test
    fun previewContainsAppLinkAndParseGuestPath() {
        val preview = ToolOutputSpill.formatPreview(
            toolName = "shell_execute",
            guestPath = "/var/minis/workspace/tool-spill/abc.txt",
            output = "head".padEnd(32, 'H') + "M".repeat(100) + "tail".padEnd(32, 'T'),
        )
        assertTrue(preview.contains("tool-output-spill"))
        assertTrue(preview.contains("minis://workspace/tool-spill/abc.txt"))
        assertEquals(
            "/var/minis/workspace/tool-spill/abc.txt",
            ToolOutputSpill.parseGuestPath(preview),
        )
    }
}
