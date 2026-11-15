package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-toolset-delta] Pins for the tool-set delta declaration
 * (pi agent-loop.ts declareToolChanges port). The injection DECISION
 * (tracker: first observation silent, unchanged set silent, change -> delta)
 * and the declaration text are pure and pinned here; the single call-site line
 * in ChatViewModelAgentLoopExt delegates to exactly these.
 */
class ToolSetDeltaTest {

    // ------------------------------------------------------------------
    // diffToolSets (pure set diff)
    // ------------------------------------------------------------------

    @Test
    fun `added and removed are computed both ways with order preserved`() {
        val delta = diffToolSets(
            listOf("shell_execute", "file_read", "read_image", "file_write"),
            listOf("file_read", "web_search", "file_write", "code_search"),
        )
        assertEquals(listOf("web_search", "code_search"), delta.added)
        // Both prev-only names are removals, in PREV order: shell_execute and
        // read_image are both absent from the next set.
        assertEquals(listOf("shell_execute", "read_image"), delta.removed)
        assertFalse(delta.isEmpty)
    }

    @Test
    fun `duplicate inputs are set-normalized`() {
        val delta = diffToolSets(listOf("a", "a", "b"), listOf("a", "c", "c"))
        assertEquals(listOf("c"), delta.added)
        assertEquals(listOf("b"), delta.removed)
    }

    @Test
    fun `identical sets produce an empty delta`() {
        val delta = diffToolSets(listOf("a", "b"), listOf("b", "a"))
        assertTrue(delta.added.isEmpty())
        assertTrue(delta.removed.isEmpty())
        assertTrue(delta.isEmpty)
    }

    // ------------------------------------------------------------------
    // Declaration text (house <system-reminder> style, pi +X/-Y wording)
    // ------------------------------------------------------------------

    @Test
    fun `declaration carries marker, signed names and the action instruction`() {
        val text = ToolSetDeltaNotice.text(
            ToolSetDelta(added = listOf("web_search", "code_search"), removed = listOf("read_image")),
        )
        assertTrue(text.startsWith(ToolSetDeltaNotice.MARKER))
        assertTrue(text.contains("+web_search, +code_search"))
        assertTrue(text.contains("-read_image"))
        assertTrue(text.contains("removed tools now fail"))
    }

    @Test
    fun `declaration renders additions-only and removals-only shapes`() {
        val addOnly = ToolSetDeltaNotice.text(ToolSetDelta(added = listOf("x"), removed = emptyList()))
        assertTrue(addOnly.contains("Tools changed: +x."))
        val removeOnly = ToolSetDeltaNotice.text(ToolSetDelta(added = emptyList(), removed = listOf("y")))
        assertTrue(removeOnly.contains("Tools changed: -y."))
    }

    // ------------------------------------------------------------------
    // Tracker decision (cross-turn no-change => no injection)
    // ------------------------------------------------------------------

    @Test
    fun `first observation initializes silently`() {
        val tracker = ToolSetTracker()
        assertNull(tracker.next(listOf("a", "b")))
    }

    @Test
    fun `unchanged set across turns injects nothing`() {
        val tracker = ToolSetTracker()
        tracker.next(listOf("a", "b"))
        assertNull(tracker.next(listOf("a", "b")))
        assertNull(tracker.next(listOf("b", "a"))) // order-only change is not a change
    }

    @Test
    fun `changed set declares the delta exactly once`() {
        val tracker = ToolSetTracker()
        tracker.next(listOf("a", "b"))
        val delta = tracker.next(listOf("a", "b", "c"))
        assertEquals(listOf("c"), delta!!.added)
        assertTrue(delta.removed.isEmpty())
        // Same set as last turn again -> silent (declared already).
        assertNull(tracker.next(listOf("a", "b", "c")))
    }

    @Test
    fun `removal-only change is declared`() {
        val tracker = ToolSetTracker()
        tracker.next(listOf("a", "b"))
        val delta = tracker.next(listOf("a"))
        assertTrue(delta!!.added.isEmpty())
        assertEquals(listOf("b"), delta.removed)
    }
}
