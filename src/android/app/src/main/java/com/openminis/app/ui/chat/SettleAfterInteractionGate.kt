package com.openminis.app.ui.chat

// [port-upstream-1.16] Ported from OpenMinis 1.16-beta (verbatim).

/**
 * [T-android-settle-programmatic-scroll] Decides whether a scroll-end may flip
 * the viewport from Pinned into Reading.
 *
 * Two checkpoints decide ownership:
 *  - `DragInteraction.Stop` (finger lift) settles it immediately: still at the
 *    bottom → keep following, anywhere else → Reading anchored where the user
 *    stopped;
 *  - a fling keeps scrolling AFTER the finger is up, so when the drag carried
 *    the viewport off-bottom the authoritative checkpoint is the
 *    `isScrollInProgress` falling edge.
 *
 * The second checkpoint sees EVERY falling edge — the streaming auto-follow
 * glide, the pin-to-bottom jump and a queued settle all end programmatic
 * scrolls without a finger. Only a drag raises `dragEndPending`, and that flag
 * is consumed by the first scroll-end after it, so those programmatic endings
 * cannot rearm anything. The flag deliberately has no deadline: a hard fling
 * can run for several seconds, and a time window measured from the finger lift
 * classified exactly those endings as "programmatic" — the viewport stayed
 * Pinned and the next streamed token yanked it back to the bottom.
 */
object SettleAfterInteractionGate {

    /**
     * @param scrollInProgress current `listState.isScrollInProgress`
     * @param nearBottom       viewport is within the near-bottom threshold
     * @param streaming        a reply is currently streaming
     * @param pinned           the scroll policy still believes it is following
     * @param dragEndPending   a `DragInteraction.Stop` has not had its scroll end yet
     * @return true when this scroll-end belongs to the user's drag
     */
    fun mayRearmFromScrollEnd(
        scrollInProgress: Boolean,
        nearBottom: Boolean,
        streaming: Boolean,
        pinned: Boolean,
        dragEndPending: Boolean,
    ): Boolean {
        if (scrollInProgress) return false
        if (!dragEndPending) return false
        if (!pinned) return false
        if (streaming) return false
        return !nearBottom
    }
}
