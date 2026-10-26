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
