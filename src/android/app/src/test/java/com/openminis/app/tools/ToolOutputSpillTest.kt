package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputSpillTest {

    @Test
    fun `preview keeps head and tail and guest path`() {
        val output = "H".repeat(8_000) + "M".repeat(20_000) + "T".repeat(5_000)
        val preview = ToolOutputSpill.formatPreview(
            toolName = "shell_execute",
            guestPath = "/var/minis/workspace/tool-spill/abc.txt",
            output = output,
        )
        assertTrue(preview.contains("tool-output-spill"))
        assertTrue(preview.contains("/var/minis/workspace/tool-spill/abc.txt"))
        assertTrue(preview.contains("file_read"))
        assertTrue(preview.startsWith("[tool-output-spill]"))
        assertTrue(preview.contains("H".repeat(32)))
        assertTrue(preview.contains("T".repeat(32)))
        assertFalse(preview.contains("M".repeat(100)))
        assertTrue(preview.length < output.length)
    }

    /**
     * [T-tool-output-single-mechanism] A spill that cannot happen (no session,
     * mkdirs refused, write threw) must still cost the context window a bounded
     * preview — the duplicated `ToolOutputPolicy` path used to `return text`
     * uncapped on write failure, which is how megabytes reached the model.
     */
    @Test
    fun `capped preview is bounded and admits there is no file`() {
        val output = "H".repeat(8_000) + "M".repeat(20_000) + "T".repeat(5_000)
        val preview = ToolOutputSpill.cappedPreview("shell_execute", output)
        assertTrue(preview.contains("could not be saved"))
        assertFalse(preview.contains("minis://workspace"))
        assertTrue(preview.contains("H".repeat(32)))
        assertTrue(preview.contains("T".repeat(32)))
        assertFalse(preview.contains("M".repeat(100)))
        assertTrue(preview.length < output.length)
    }

    /**
     * [T-tool-output-offload-loop GH#343] Every bounded placeholder this object
     * produces is recognised, so a second processing pass returns it verbatim
     * instead of writing a placeholder that points at a placeholder.
     */
    @Test
    fun `offload placeholders are detected`() {
        val capped = ToolOutputSpill.cappedPreview("shell_execute", "H".repeat(8_000))
        assertTrue(ToolOutputSpill.isOffloadPlaceholder(capped))
        assertTrue(
            ToolOutputSpill.isOffloadPlaceholder(
                "[tool output offloaded to save context — 20000 chars. Full output saved to /var/minis/workspace/tool-spill/t.txt]",
            ),
        )
        assertTrue(ToolOutputSpill.isOffloadPlaceholder("[tool output trimmed for context: 5 chars total]"))
        assertFalse(ToolOutputSpill.isOffloadPlaceholder("plain command output"))
    }

    /** [T-tool-output-offload-loop GH#343] Only the spill dir counts as a read-back target. */
    @Test
    fun `only spill guest paths are recognised`() {
        assertTrue(ToolOutputSpill.isSpillGuestPath("/var/minis/workspace/tool-spill/abc.txt"))
        assertTrue(ToolOutputSpill.isSpillGuestPath("/var/minis/workspace/tool-spill/trim-abc.txt"))
        assertFalse(ToolOutputSpill.isSpillGuestPath("/var/minis/workspace/notes.txt"))
        assertFalse(ToolOutputSpill.isSpillGuestPath("/var/minis/workspace/tool-spill"))
        assertFalse(ToolOutputSpill.isSpillGuestPath(null))
    }
}
