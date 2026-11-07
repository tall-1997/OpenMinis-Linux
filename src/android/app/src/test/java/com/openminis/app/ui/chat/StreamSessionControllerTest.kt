package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSessionControllerTest {
    @Test
    fun throttleTiersMatchLegacyLadder() {
        val c = StreamSessionController(
            scope = TestScope(),
            messages = MutableStateFlow(emptyList()),
            streamingById = MutableStateFlow(emptyMap()),
            newlineFlushMinChars = 50,
            newlineFlushMaxLen = 5_000,
        )
        assertEquals(200L, c.streamFlushThrottleMs(0, 0))
        assertEquals(200L, c.streamFlushThrottleMs(499, 0))
        assertEquals(300L, c.streamFlushThrottleMs(500, 0))
        assertEquals(500L, c.streamFlushThrottleMs(2_000, 0))
        assertEquals(1_000L, c.streamFlushThrottleMs(32_000, 0))
        assertEquals(1_500L, c.streamFlushThrottleMs(64_000, 0))
        assertEquals(2_000L, c.streamFlushThrottleMs(128_000, 0))
    }

    /**
     * [T-android-tail-sprint] A large unflushed backlog (gateway tail burst,
     * smooth-forwarding relay) clamps the ladder to the sprint cadence — the
     * display must chase the arrival, not the document length. Small
     * backlogs keep the ladder verbatim.
     */
    @Test
    fun largeBacklogClampsThrottleToTheSprintCadence() {
        val c = StreamSessionController(
            scope = TestScope(),
            messages = MutableStateFlow(emptyList()),
            streamingById = MutableStateFlow(emptyMap()),
            newlineFlushMinChars = 50,
            newlineFlushMaxLen = 5_000,
        )
        // Below the sprint threshold: ladder verbatim at every tier.
        assertEquals(500L, c.streamFlushThrottleMs(8_000, StreamingThrottle.TAIL_SPRINT_CHARS - 1))
        assertEquals(2_000L, c.streamFlushThrottleMs(128_000, StreamingThrottle.TAIL_SPRINT_CHARS - 1))
        // At/above it: every tier clamps to SPRINT_MS.
        assertEquals(
            StreamingThrottle.SPRINT_MS,
            c.streamFlushThrottleMs(8_000, StreamingThrottle.TAIL_SPRINT_CHARS),
        )
        assertEquals(
            StreamingThrottle.SPRINT_MS,
            c.streamFlushThrottleMs(128_000, 40_000),
        )
    }

    @Test
    fun sprintThrottleIsNeverSlowerThanTheLadder() {
        for (len in intArrayOf(0, 499, 500, 2_000, 32_000, 64_000, 128_000, 200_000)) {
            for (unflushed in intArrayOf(0, 100, StreamingThrottle.TAIL_SPRINT_CHARS, 50_000)) {
                val sprint = StreamingThrottle.throttleFor(len, unflushed, firstTierMs = 150L)
                val ladder = StreamingThrottle.ladderFor(len, firstTierMs = 150L)
                assertTrue(
                    "sprint($len,$unflushed)=$sprint must not exceed ladder=$ladder",
                    sprint <= ladder,
                )
            }
        }
    }
}
