package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-recall-turn-dedup] Pin the cross-turn dedup decision: the same recalled
 * lines are announced once per topic. A turn repeats on every request (each
 * request is a fresh context); a NEW turn re-announces only when the lines
 * changed — or when the message that carried the previous block left the
 * window (the memories are gone from context and must be re-surfaced).
 */
class RecallTurnDedupTest {

    private val keys = setOf("2026-11-04.md:line one", "2026-11-03.md:line two")

    @Test
    fun firstAnnouncementAlwaysPasses() {
        assertTrue(shouldAnnounceRecall("turn-a", null, keys, null, lastCarrierAlive = false))
    }

    @Test
    fun sameTurnRepeats() {
        assertTrue(shouldAnnounceRecall("turn-a", "turn-a", keys, keys, lastCarrierAlive = true))
    }

    @Test
    fun newTurnWithIdenticalLinesSkips() {
        assertFalse(shouldAnnounceRecall("turn-b", "turn-a", keys, keys, lastCarrierAlive = true))
    }

    @Test
    fun newTurnWithChangedLinesAnnounces() {
        assertTrue(
            shouldAnnounceRecall(
                "turn-b",
                "turn-a",
                keys + "2026-11-10.md:line three",
                keys,
                lastCarrierAlive = true,
            ),
        )
    }

    @Test
    fun newTurnWithIdenticalLinesAnnouncesWhenCarrierLeftTheWindow() {
        assertTrue(shouldAnnounceRecall("turn-b", "turn-a", keys, keys, lastCarrierAlive = false))
    }

    /**
     * [T-recall-carrier-replay-once] The evicted-carrier replay is a ONE-SHOT:
     * once the block has been re-surfaced after compaction, later turns must
     * stay quiet even though the carrier is still gone. Otherwise a compacted
     * session re-announces the same memories on every request.
     */
    @Test
    fun newTurnWithIdenticalLinesSkipsAfterTheCarrierReplayAlreadyHappened() {
        assertFalse(
            shouldAnnounceRecall(
                "turn-c",
                "turn-b",
                keys,
                keys,
                lastCarrierAlive = false,
                carrierReplayAlreadyDone = true,
            ),
        )
    }
}
