package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * review P2-11: the spill preview's head/tail slices must never split a
 * surrogate pair, and the TAIL edge keeps its SHIPPED shape — sliceTail
 * drops an orphaned leading low surrogate whole (one code unit FEWER than
 * the old ToolOutputPolicy.safeSubstring previews, which extended the window
 * backwards to keep the pair). Pinned here so the "byte-identical" claim the
 * KDoc used to carry cannot regress: any future change to either slicer at
 * an emoji boundary must update these assertions deliberately.
 */
class ToolOutputSpillPreviewBoundaryTest {

    private val emoji = "😀"

    private fun preview(output: String): String =
        ToolOutputSpill.formatPreview("test_tool", null, output)

    /**
     * The model-facing bytes are UTF-8: an unpaired surrogate encodes to
     * '?' (0x3F). A clean roundtrip means no pair was cut anywhere in the
     * preview.
     */
    private fun assertNoCutPair(text: String) {
        assertEquals(
            "the preview must not carry unpaired surrogates (cut pairs)",
            text,
            text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `tail window starting mid pair drops the pair whole`() {
        // Layout: 6000 x's, then an emoji pair, then 3999 y's.
        // The tail window starts exactly ON the pair's low surrogate:
        // sliceTail(4000) keeps 3999 units — the pair is dropped whole and
        // the tail stays one code unit short of its budget.
        val output = "x".repeat(ToolOutputSpill.HEAD) + emoji + "y".repeat(3999)
        val result = preview(output)
        assertNoCutPair(result)
        // The pair never appears anywhere in the preview (head is clean, tail
        // starts past it).
        assertFalse("the pair must be dropped whole, not half-kept", result.contains(emoji))
        val tailBody = result.substringAfterLast("…\n\n")
        assertEquals(
            "the tail is the shipped 3999-unit window, not the safeSubstring 4000",
            "y".repeat(3999),
            tailBody,
        )
    }

    @Test
    fun `head window ending mid pair drops the pair whole`() {
        // Layout: 5999 x's, then an emoji pair, then 100 z's. The head window
        // ends exactly ON the pair's high surrogate: sliceHead(6000) keeps
        // 5999 units — the pair is dropped whole from the head, and the tail
        // (below the HEAD+TAIL threshold) is empty.
        val output = "x".repeat(5999) + emoji + "z".repeat(100)
        val result = preview(output)
        assertNoCutPair(result)
        assertFalse(result.contains(emoji))
        assertFalse("no tail section when the output fits HEAD+TAIL", result.contains("chars omitted"))
    }

    @Test
    fun `emoji fully inside the tail window survives as a complete pair`() {
        // Control: the tail starts in a clean region; an emoji INSIDE the
        // last 4000 units survives complete — what matters is the window
        // boundary, not the pair's position inside the window.
        val output = "x".repeat(ToolOutputSpill.HEAD) + "m".repeat(50) + emoji +
            "y".repeat(3999 - 50)
        val result = preview(output)
        assertNoCutPair(result)
        val tailBody = result.substringAfterLast("…\n\n")
        assertEquals("m".repeat(49) + emoji + "y".repeat(3999 - 50), tailBody)
        // The complete pair survives the UTF-8 roundtrip (proves the pair was
        // not cut anywhere in the preview).
        assertTrue("the complete pair must survive in the tail", tailBody.contains(emoji))
    }
}
