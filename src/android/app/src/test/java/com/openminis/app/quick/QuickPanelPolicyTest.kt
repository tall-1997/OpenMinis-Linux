package com.openminis.app.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [E-batch-quick-invocation] Pure decision logic for the quick surface:
 * panel-session reuse window, share-text extraction and notification
 * helpers. Plain JVM — no Android runtime involved.
 */
class QuickPanelPolicyTest {

    private val now = 1_000_000_000L

    // ---- shouldReusePanelSession ------------------------------------------ //

    @org.junit.Test
    fun `reuse rejected when no panel session recorded`() {
        assertFalse(QuickPanelPolicy.shouldReusePanelSession(null, now - 1000, now))
        assertFalse(QuickPanelPolicy.shouldReusePanelSession("", now - 1000, now))
    }

    @org.junit.Test
    fun `reuse accepted inside the window and rejected at or past it`() {
        val id = "session-1"
        // Just inside 10 minutes.
        assertTrue(
            QuickPanelPolicy.shouldReusePanelSession(id, now - (QuickPanelPolicy.RECENT_PANEL_MS - 1), now),
        )
        // Exactly at the window boundary (half-open interval): expired.
        assertFalse(QuickPanelPolicy.shouldReusePanelSession(id, now - QuickPanelPolicy.RECENT_PANEL_MS, now))
        // Long past it.
        assertFalse(QuickPanelPolicy.shouldReusePanelSession(id, now - 2 * QuickPanelPolicy.RECENT_PANEL_MS, now))
        // Garbage timestamp never reuses.
        assertFalse(QuickPanelPolicy.shouldReusePanelSession(id, 0L, now))
        // Timestamps in the future do not reuse either (negative delta).
        assertFalse(QuickPanelPolicy.shouldReusePanelSession(id, now + 5000, now))
    }

    // ---- extractShareText -------------------------------------------------- //

    @org.junit.Test
    fun `share text joins subject and text and trims blanks`() {
        assertEquals(
            "A page\nhttps://example.com",
            QuickPanelPolicy.extractShareText("A page", "https://example.com"),
        )
        // Subject-only share.
        assertEquals("Just a title", QuickPanelPolicy.extractShareText("Just a title", null))
        // Text-only share.
        assertEquals("Body only", QuickPanelPolicy.extractShareText(null, "Body only"))
        // Blank entries are dropped.
        assertEquals("Real", QuickPanelPolicy.extractShareText("  ", "Real"))
        assertEquals("", QuickPanelPolicy.extractShareText(null, null))
    }

    @org.junit.Test
    fun `share text deduplicates identical subject and text`() {
        // Many senders put the same string in both slots.
        assertEquals(
            "Same content",
            QuickPanelPolicy.extractShareText("Same content", "Same content"),
        )
    }

    // ---- status / preview helpers ------------------------------------------- //

    @org.junit.Test
    fun `failed statuses and preview truncation`() {
        assertTrue(QuickPanelPolicy.isFailedStatus("Error"))
        assertTrue(QuickPanelPolicy.isFailedStatus("Timeout"))
        assertTrue(QuickPanelPolicy.isFailedStatus(null))
        assertFalse(QuickPanelPolicy.isFailedStatus("Completed"))

        assertEquals("(no response)", QuickPanelPolicy.buildPreview(null))
        assertEquals("(no response)", QuickPanelPolicy.buildPreview("   "))
        assertEquals("short", QuickPanelPolicy.buildPreview("short"))
        assertEquals(10, QuickPanelPolicy.buildPreview("a".repeat(50), maxChars = 10).length)
    }
}
