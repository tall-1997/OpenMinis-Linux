package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-settle-programmatic-scroll]
 *
 * A scroll-end may only rearm Reading when it belongs to a user drag. The
 * regression this pins: a hard fling can run for seconds after the finger
 * lifts, so a time window measured from `DragInteraction.Stop` classified the
 * fling's own end as "programmatic", left the viewport Pinned, and the next
 * streamed token yanked it back to the bottom.
 */
class SettleAfterInteractionGateTest {

    private fun may(
        scrollInProgress: Boolean = false,
        nearBottom: Boolean = false,
        streaming: Boolean = false,
        pinned: Boolean = true,
        dragEndPending: Boolean = true,
    ) = SettleAfterInteractionGate.mayRearmFromScrollEnd(
        scrollInProgress = scrollInProgress,
        nearBottom = nearBottom,
        streaming = streaming,
        pinned = pinned,
        dragEndPending = dragEndPending,
    )

    @Test
    fun `a drag that ended off bottom rearms Reading`() {
        assertTrue(may())
    }

    @Test
    fun `a programmatic scroll end never rearms`() {
        assertFalse(may(dragEndPending = false))
    }

    @Test
    fun `a drag that ended at the bottom keeps following`() {
        assertFalse(may(nearBottom = true))
    }

    @Test
    fun `a scroll that is still in progress is not an end`() {
        assertFalse(may(scrollInProgress = true))
    }

    @Test
    fun `a live stream owns the pinned viewport`() {
        assertFalse(may(streaming = true))
    }

    @Test
    fun `an already rearmed viewport has nothing to do`() {
        assertFalse(may(pinned = false))
    }
}
