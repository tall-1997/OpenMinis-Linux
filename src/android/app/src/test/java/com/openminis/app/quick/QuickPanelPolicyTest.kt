package com.openminis.app.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [E-batch-quick-invocation] Pure decision logic for the quick surface:
 * panel-session reuse window, share-text extraction, ball gesture windows and
 * edge docking. Plain JVM — no Android runtime involved.
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

    // ---- classifyGesture --------------------------------------------------- //

    @org.junit.Test
    fun `gesture under the slop is a tap and beyond it a drag`() {
        // Zero movement → tap.
        assertEquals(QuickPanelPolicy.Gesture.TAP, QuickPanelPolicy.classifyGesture(100f, 100f, 100f, 100f))
        // Just under the 12 px slop on one axis → still a tap.
        assertEquals(QuickPanelPolicy.Gesture.TAP, QuickPanelPolicy.classifyGesture(100f, 100f, 111f, 100f))
        // Exactly at the slop → drag.
        assertEquals(QuickPanelPolicy.Gesture.DRAG, QuickPanelPolicy.classifyGesture(100f, 100f, 112f, 100f))
        // Vertical movement counts too.
        assertEquals(QuickPanelPolicy.Gesture.DRAG, QuickPanelPolicy.classifyGesture(100f, 100f, 100f, 130f))
    }

    // ---- isWithinDoubleTapWindow ------------------------------------------- //

    @org.junit.Test
    fun `double tap window matches only a fresh second tap`() {
        val first = 500L
        assertTrue(QuickPanelPolicy.isWithinDoubleTapWindow(first, first + 200))
        // At the boundary → expired (half-open).
        assertFalse(QuickPanelPolicy.isWithinDoubleTapWindow(first, first + QuickPanelPolicy.DOUBLE_TAP_WINDOW_MS))
        assertFalse(QuickPanelPolicy.isWithinDoubleTapWindow(first, first + 400))
        // No pending tap (0 / negative) never matches.
        assertFalse(QuickPanelPolicy.isWithinDoubleTapWindow(0L, first + 100))
        assertFalse(QuickPanelPolicy.isWithinDoubleTapWindow(-1L, first + 100))
    }

    // ---- dockX / clampY ----------------------------------------------------- //

    @org.junit.Test
    fun `ball docks to the nearer horizontal edge`() {
        val w = 1080
        val ball = 132  // 48dp @2.75x
        val margin = 22
        // Left half → left edge.
        assertEquals(margin, QuickPanelPolicy.dockX(100, ball, w, margin))
        // Right half → right edge.
        assertEquals(w - ball - margin, QuickPanelPolicy.dockX(w - ball - 100, ball, w, margin))
        // Centre decides: just left of the middle → left.
        assertEquals(margin, QuickPanelPolicy.dockX(w / 2 - ball / 2 - 1, ball, w, margin))
        // Just right of the middle → right.
        assertEquals(w - ball - margin, QuickPanelPolicy.dockX(w / 2 - ball / 2 + 1, ball, w, margin))
    }

    @org.junit.Test
    fun `ball vertical position is clamped on screen`() {
        val h = 2400
        val ball = 132
        assertEquals(0, QuickPanelPolicy.clampY(-50, ball, h))
        assertEquals(h - ball, QuickPanelPolicy.clampY(h + 500, ball, h))
        assertEquals(700, QuickPanelPolicy.clampY(700, ball, h))
        // Degenerate sizes pass through unchanged rather than divide by zero.
        assertEquals(42, QuickPanelPolicy.clampY(42, 0, h))
        assertEquals(42, QuickPanelPolicy.clampY(42, ball, 0))
    }

    // ---- screenContextPreamble ---------------------------------------------- //

    @org.junit.Test
    fun `screen preamble wraps package and is null without capture`() {
        assertNull(QuickPanelPolicy.screenContextPreamble(null, "com.foo"))
        assertNull(QuickPanelPolicy.screenContextPreamble("   ", "com.foo"))

        val preamble = QuickPanelPolicy.screenContextPreamble("Hello screen", "com.example.app")!!
        assertTrue(preamble.startsWith("[The user double-tapped"))
        assertTrue(preamble.contains("com.example.app"))
        assertTrue(preamble.endsWith("Hello screen"))
        // Missing package degrades to a placeholder, not null.
        assertTrue(QuickPanelPolicy.screenContextPreamble("Hi", null)!!.contains("unknown app"))
    }

    @org.junit.Test
    fun `screen preamble is capped at the max character budget`() {
        val huge = "x".repeat(QuickPanelPolicy.MAX_SCREEN_TEXT_CHARS + 500)
        val preamble = QuickPanelPolicy.screenContextPreamble(huge, "com.foo")!!
        // Preamble header + at most MAX chars of body.
        assertTrue(preamble.length <= 100 + QuickPanelPolicy.MAX_SCREEN_TEXT_CHARS)
        assertTrue(preamble.contains("x".repeat(100)))
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
