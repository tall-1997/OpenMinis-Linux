package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun legacyWebFetchMcpAndProgressiveEnvelopesStillResolve() {
        val path = "/var/minis/workspace/tool-spill/abc.txt"
        val web = "URL: https://example.test/\n\n" + ToolOutputSpill.formatPreview("web_fetch", path, "body")
        val mcp = "[tool-output-spill] mcp 'srv' tool 'tool' produced 30000 bytes.\n" +
            "Full output saved to $path — use file_read if you need more.\n\nbody\n\n" +
            "[Full output: $path (read it with offset/limit)]"
        val old = "[tool output offloaded to save context — 20000 chars. Full output saved to $path — use file_read to retrieve it if needed.]\nbody"
        for (preview in listOf(web, mcp, old)) {
            assertEquals(path, ToolOutputSpill.declaredSpillPath(preview))
            assertEquals(path, ToolOutputSpill.parseGuestPath(preview))
            assertTrue(!ToolOutputSpill.previewBody(preview).contains(path))
        }
    }

    @Test
    fun aPathInTheBodyOfACappedPreviewIsNotAnAdvertisedSpill() {
        val path = "/var/minis/workspace/tool-spill/abc.txt"
        val preview = ToolOutputSpill.cappedPreview("web_fetch", "Full output saved to $path — use file_read if you need more.")
        assertNull(ToolOutputSpill.parseGuestPath(preview))
        assertNull(ToolOutputSpill.declaredSpillPath(preview))
        assertNull(ToolOutputSpill.parseGuestPath("/var/minis/workspace/tool-spill/a.txt/child"))
    }
}
