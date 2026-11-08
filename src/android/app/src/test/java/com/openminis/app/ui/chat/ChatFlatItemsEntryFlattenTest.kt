package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-entry-sync-flatten] The cold-entry synchronous flatten is the
 * ONLY tick allowed to build on the composition thread, so its gate must be
 * airtight:
 * - it only fires while nothing has been published yet (wasEmpty),
 * - never during a live stream (streamEmpty — every streaming token would
 *   otherwise land on the main thread and resurrect the T94 ANR),
 * - and only below the size bound, where the synchronous build costs a few
 *   ms. Above the bound the blank first frame is the accepted price of
 *   keeping huge transcripts off the composition thread.
 */
class ChatFlatItemsEntryFlattenTest {

    @Test
    fun singleMessageColdEntryTakesTheSyncPath() {
        assertTrue(shouldSyncFlattenFirstFrame(wasEmpty = true, streamEmpty = true, msgCount = 1))
    }

    @Test
    fun boundInclusiveAtTwoHundred() {
        assertTrue(
            shouldSyncFlattenFirstFrame(
                wasEmpty = true,
                streamEmpty = true,
                msgCount = ENTRY_SYNC_FLATTEN_MAX_MESSAGES,
            )
        )
    }

    @Test
    fun oneAboveBoundKeepsTheOffMainPath() {
        assertFalse(
            shouldSyncFlattenFirstFrame(
                wasEmpty = true,
                streamEmpty = true,
                msgCount = ENTRY_SYNC_FLATTEN_MAX_MESSAGES + 1,
            )
        )
    }

    @Test
    fun emptyTranscriptHasNoRowsToPublish() {
        assertFalse(shouldSyncFlattenFirstFrame(wasEmpty = true, streamEmpty = true, msgCount = 0))
    }

    @Test
    fun publishedFramesNeverTakeTheSyncPath() {
        // Every tick after the first publication is an incremental refresh
        // during composition of an existing list; the T94 off-main path owns
        // those, no matter how small the transcript is.
        assertFalse(shouldSyncFlattenFirstFrame(wasEmpty = false, streamEmpty = true, msgCount = 3))
    }

    @Test
    fun streamingTicksNeverTakeTheSyncPath() {
        // A live stream emits per token; those must stay off the
        // composition thread even for a tiny transcript.
        assertFalse(shouldSyncFlattenFirstFrame(wasEmpty = true, streamEmpty = false, msgCount = 3))
    }
}
