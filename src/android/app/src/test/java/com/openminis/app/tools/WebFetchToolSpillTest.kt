package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-webfetch-spill] The `web_fetch` output cap is a preview trigger, not a
 * loss point. The old `htmlToText(raw).take(8_000)` silently discarded
 * everything past the cap with no spill file and no recovery path. The
 * over-cap decision is pinned here: with a spill path the body is the shared
 * [ToolOutputSpill] placeholder (head+tail + guest path); without one the
 * FULL converted text is deferred to the unified exit instead of dropped.
 */
class WebFetchToolSpillTest {

    @Test
    fun triggerThresholdIsUnchanged() {
        assertEquals(8_000, WebFetchTool.MAX_OUTPUT)
    }

    @Test
    fun overCapWithSpillPathBecomesTheSharedPlaceholderPreview() {
        val big = "H".repeat(8_000) + "M".repeat(20_000) + "T".repeat(5_000)
        val out = WebFetchTool.presentOverCap(big, "/var/minis/workspace/tool-spill/web_fetch.txt")
        // Same placeholder shape every oversized tool output uses.
        assertTrue(out.startsWith("[tool-output-spill] web_fetch produced ${big.length} chars."))
        assertTrue(out.contains("Full output saved to /var/minis/workspace/tool-spill/web_fetch.txt"))
        assertTrue(out.contains("file_read"))
        // Head and tail preserved, middle elided, bounded body.
        assertTrue(out.contains("H".repeat(32)))
        assertTrue(out.contains("T".repeat(32)))
        assertFalse(out.contains("M".repeat(100)))
        assertTrue(out.length < big.length)
    }

    @Test
    fun overCapWithoutASpillPathDefersTheFullTextToTheUnifiedExit() {
        // No session wired here: the FULL converted text goes to the caller,
        // whose ToolOutputPolicy.apply (which resolves the session) owns the
        // cap + spill. A silent take() is exactly what must NOT come back.
        val big = "H".repeat(20_000)
        assertEquals(big, WebFetchTool.presentOverCap(big, null))
    }
}
