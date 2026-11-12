package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-frozen-repeat-hint] ZCode model-anomaly shape: one hint per frozen
 * streak, attached to the tool result, never a user turn.
 */
class ToolRepeatTrackerTest {

    @Test
    fun `a frozen streak hints exactly once`() {
        val tracker = ToolRepeatTracker()
        assertNull(tracker.observe("sig", "result"))
        val hint = tracker.observe("sig", "result")
        requireNotNull(hint)
        assertTrue("the hint must say the call still runs", hint.contains("still runs"))
        assertNull("one hint per streak", tracker.observe("sig", "result"))
    }

    @Test
    fun `progress resets the streak and re-arms the hint`() {
        val tracker = ToolRepeatTracker()
        assertNull(tracker.observe("sig", "r1"))
        requireNotNull(tracker.observe("sig", "r1"))
        assertNull("different result = progress", tracker.observe("sig", "r2"))
        requireNotNull(tracker.observe("sig", "r2")) { "a new frozen streak hints again" }
    }

    @Test
    fun `different dispatch never counts as a frozen repeat`() {
        val tracker = ToolRepeatTracker()
        assertNull(tracker.observe("sig-a", "r"))
        assertNull(tracker.observe("sig-b", "r"))
    }
}
