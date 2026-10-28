package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-entry-pin-tail-starve] The bounded session-entry pin wait.
 *
 * The regression this pins down: `ensureSessionTailLoaded` defers while a
 * turn is streaming (leaves `sessionTailReady=false` and owns nothing — the
 * queued flag is consumed only at the next agent-loop boundary), so an
 * unbounded `sessionTailReady.first { it }` parked the entry pin for the
 * whole generation and a re-entered session sat on the retained mid-history
 * viewport instead of the newest message.
 *
 * [T-android-entry-pin-self-block] The second regression, uncovered after
 * #162 still reported a mid-history landing: the pin's gate required the
 * completed layout to already show the newest row (`firstVisibleIndex == 0`),
 * but nothing issues a scroll to index 0 during entry before the pin — the
 * requirement waited on the pin's own effect, and the bounded wait expired
 * before any correction happened. The gate now decides on the tail rows
 * only; the pin's scrollToItem force-remeasure is the corrector.
 */
class SessionEntryScrollTest {

    @Test
    fun `tail already ready pins immediately`() {
        assertEquals(0L, SessionEntryScroll.entryPinWaitBoundMs(tailReady = true, isStreaming = false, hasNewerMessages = false))
    }

    @Test
    fun `streaming entry without a newer gap pins immediately`() {
        // During a live turn the painted window IS the tail: the stream
        // appends rows and notePersistedUiRow extends the newest cursor, so
        // waiting for the flag would outlast the generation for nothing.
        assertEquals(0L, SessionEntryScroll.entryPinWaitBoundMs(tailReady = false, isStreaming = true, hasNewerMessages = false))
    }

    @Test
    fun `streaming entry with a newer gap still waits bounded`() {
        // A gap means the painted window is NOT the tail; the attach job must
        // get a chance to drain before the pin lands.
        assertEquals(
            SessionEntryScroll.TAIL_ATTACH_WAIT_BOUND_MS,
            SessionEntryScroll.entryPinWaitBoundMs(tailReady = false, isStreaming = true, hasNewerMessages = true),
        )
    }

    @Test
    fun `idle entry with a converging attach waits bounded`() {
        // The common cold/warm entry: no stream, attach converging on IO —
        // the wait is bounded so a slow database can never park the pin.
        val bound = SessionEntryScroll.entryPinWaitBoundMs(tailReady = false, isStreaming = false, hasNewerMessages = true)
        assertEquals(SessionEntryScroll.TAIL_ATTACH_WAIT_BOUND_MS, bound)
        val boundNoGap = SessionEntryScroll.entryPinWaitBoundMs(tailReady = false, isStreaming = false, hasNewerMessages = false)
        assertEquals(SessionEntryScroll.TAIL_ATTACH_WAIT_BOUND_MS, boundNoGap)
    }

    // [T-android-entry-pin-before-compose] The pin must not run until a tail
    // row is composed. scrollToItem(0) against a header-only list resolves
    // there, and the tail rows that join afterwards displace the viewport
    // into mid-history — the "进入输出中的长会话停在历史中间" report.

    @Test
    fun `pin waits while no tail row is composed`() {
        assertEquals(false, SessionEntryScroll.entryPinReady(0, tailLoading = false, listItemCount = 0, firstVisibleIndex = 0))
    }

    @Test
    fun `pin waits while the tail attach is still appending`() {
        // Pinning between chunks lands on whichever row is newest at that
        // instant; the next chunk then shoves it into history.
        assertEquals(false, SessionEntryScroll.entryPinReady(40, tailLoading = true, listItemCount = 41, firstVisibleIndex = 0))
    }

    @Test
    fun `pin waits while the composed rows are not laid out yet`() {
        // A row can be counted a frame before the lazy list measures it.
        // Scrolling in that frame resolves against an empty list and is a
        // no-op, so the pin has to wait for the layout too.
        assertEquals(false, SessionEntryScroll.entryPinReady(12, tailLoading = false, listItemCount = 0, firstVisibleIndex = 0))
    }

    // [T-android-entry-pin-self-block] The pin's scrollToItem(0,0) is the
    // ONLY corrector during entry: nothing else issues a scroll to index 0
    // (the streaming glide requires firstVisible(0,0) already true, the
    // trailing-row pin needs a tool/typing row, the reserve pin needs a
    // reserve change). The old `firstVisibleIndex == 0` gate requirement
    // waited on the pin's own effect — whenever the retained anchor parked
    // the viewport on a non-zero row, the bounded wait expired before any
    // correction happened: the reported "点击进入正在长会话时停留的位置不是
    // 最新消息位置" after #162.

    @Test
    fun `pin runs while the retained head is still parked`() {
        // What the retained anchor does during entry is RESTORE, not settle:
        // the LazyListState is hoisted on the session VM and survives screen
        // disposal, while flatItems (composition-scoped remember) restarts
        // EMPTY. The first measure runs against a near-empty list (the
        // __load_older__ pill is the only item on a long session), coerces
        // the retained mid-history index to 0 and stores
        // lastKnownFirstItemKey = "__load_older__"; the first flatItems
        // publish key-follows that key to the END of the DSL item order —
        // the visual TOP under reverseLayout — and the viewport lands on the
        // oldest loaded rows. The gate must not wait for firstVisibleIndex
        // to become 0: only the pin itself produces that, via scrollToItem
        // whose forceRemeasure remeasures synchronously from the requested
        // index against the CURRENT provider (it cannot resolve against the
        // stale layout) and forgets the stored key, so the restored anchor
        // cannot re-assert.
        assertEquals(true, SessionEntryScroll.entryPinReady(40, tailLoading = false, listItemCount = 41, firstVisibleIndex = 40))
        assertEquals(true, SessionEntryScroll.entryPinReady(400, tailLoading = false, listItemCount = 401, firstVisibleIndex = 1))
    }

    @Test
    fun `pin runs once the completed layout shows the newest row`() {
        assertEquals(true, SessionEntryScroll.entryPinReady(1, tailLoading = false, listItemCount = 2, firstVisibleIndex = 0))
        assertEquals(true, SessionEntryScroll.entryPinReady(400, tailLoading = false, listItemCount = 401, firstVisibleIndex = 0))
    }

    @Test
    fun `the bound is finite and generous to chunked IO`() {
        // A zero or tiny bound would reintroduce the pre-fix behaviour of
        // pinning before the tail attach lands; an unbounded wait is the
        // starvation bug itself. Keep it strictly between.
        val bound = SessionEntryScroll.TAIL_ATTACH_WAIT_BOUND_MS
        assert(bound > 0L)
        assert(bound <= 10_000L)
    }
}
