package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the [ToolOutputSpill] additions that serve the MCP output
 * limit ([T-mcp-output-limit]): the full-text offload used as the pi
 * `saveOutput` callback, and the pi warning lines (token estimate + total
 * lines) the generic previews now carry.
 */
class ToolOutputSpillOffloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `offload writes the full text once and returns a readable guest path`() {
        val dir = tmp.newFolder("tool-spill")
        val body = "M".repeat(30_000)
        val guest = ToolOutputSpill.offloadFullOutput(dir, "mcp-srv-tool", body)

        assertEquals("/var/minis/workspace/tool-spill/mcp-srv-tool.txt", guest)
        assertTrue("guest path must be a read-back target", ToolOutputSpill.isSpillGuestPath(guest))
        assertEquals(body, File(dir, "mcp-srv-tool.txt").readText())
    }

    @Test
    fun `offload with a reused key never overwrites the first file`() {
        val dir = tmp.newFolder("tool-spill")
        val first = ToolOutputSpill.offloadFullOutput(dir, "mcp-srv-tool", "one".repeat(20_000))
        val second = ToolOutputSpill.offloadFullOutput(dir, "mcp-srv-tool", "two".repeat(20_000))

        assertNotEquals(first, second)
        assertEquals("one".repeat(20_000), File(dir, File(first!!.removePrefix("/var/minis/workspace/tool-spill/")).name).readText())
        assertEquals("two".repeat(20_000), File(dir, File(second!!.removePrefix("/var/minis/workspace/tool-spill/")).name).readText())
    }

    @Test
    fun `offload sanitizes odd server-tool keys`() {
        val dir = tmp.newFolder("tool-spill")
        val guest = ToolOutputSpill.offloadFullOutput(dir, "mcp-bad/server:tool?", "x".repeat(17_000))
        assertTrue(guest!!.matches(Regex("/var/minis/workspace/tool-spill/[A-Za-z0-9._-]+\\.txt")))
    }

    // ── pi warning lines in the generic previews ──────────────────────────────

    private val oversized = "line\n".repeat(500) + "x".repeat(17_000) // 19500 chars

    @Test
    fun `formatPreview announces the token estimate and the total line count`() {
        val preview = ToolOutputSpill.formatPreview(
            "shell_execute",
            "/var/minis/workspace/tool-spill/abc.txt",
            oversized,
        )
        assertTrue(preview.startsWith("[tool-output-spill]"))
        // 19500 bytes → ceil / 4 = 4875 (pi output-limit.ts estimate).
        assertTrue(
            "token estimate line missing: ${preview.take(120)}",
            preview.contains("Warning: truncated output (original token count: 4875)"),
        )
        assertTrue("total lines line missing", preview.contains("Total output lines: 501"))
        assertTrue(preview.contains("file_read"))
        // The capped (no-file) variant keeps the estimate lines too.
        val capped = ToolOutputSpill.formatPreview("shell_execute", null, oversized)
        assertTrue(capped.contains("Warning: truncated output (original token count: 4875)"))
        assertTrue(capped.contains("could not be saved"))
    }

    @Test
    fun `alreadySpilledPreview announces the same stats`() {
        val guest = "/var/minis/workspace/tool-spill/abc.txt"
        val preview = ToolOutputSpill.alreadySpilledPreview("file_read", guest, oversized)
        assertTrue(preview.startsWith("[tool-output-spill]"))
        assertTrue(preview.contains("Warning: truncated output (original token count: 4875)"))
        assertTrue(preview.contains("Total output lines: 501"))
        assertTrue(preview.contains("NOT being offloaded again"))
    }

    @Test
    fun `previews stay placeholders after the estimate lines were added`() {
        val capped = ToolOutputSpill.cappedPreview("shell_execute", oversized)
        assertTrue(ToolOutputSpill.isOffloadPlaceholder(capped))
        val written = ToolOutputSpill.writeSpill(tmp.newFolder("spill2"), "shell_execute", "call-x", oversized)
        assertTrue(ToolOutputSpill.isOffloadPlaceholder(written))
        assertEquals(SpillPlan.PASSTHROUGH, ToolOutputSpill.planFor(written, true, null))
    }
}
