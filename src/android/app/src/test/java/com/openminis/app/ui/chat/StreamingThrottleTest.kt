package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingThrottleTest {

    @Test
    fun noBacklogMatchesThePlainLadder() {
        for (len in listOf(0, 499, 500, 1_999, 2_000, 31_999, 32_000, 63_999, 64_000, 127_999, 128_000, 500_000)) {
            assertEquals(
                "len=$len",
                StreamingThrottle.ladderFor(len),
                StreamingThrottle.throttleFor(len, unflushedChars = 0),
            )
        }
    }

    @Test
    fun flushSprintClampsTo300() {
        assertEquals(300L, StreamingThrottle.throttleFor(len = 200_000, unflushedChars = 2_000))
        assertEquals(300L, StreamingThrottle.throttleFor(len = 2_000, unflushedChars = 5_000))
    }

    @Test
    fun parseSprintClampsTo1sNeverBelowTheLadder() {
        // A full-document parse costs several times a string flush; the
        // render hop sprints to the ladder's own <64K tier, never below.
        assertEquals(
            1_000L,
            StreamingThrottle.throttleFor(
                len = 200_000,
                unflushedChars = 2_000,
                sprintMs = StreamingThrottle.PARSE_SPRINT_MS,
            ),
        )
        // Ladder already faster than the parse ceiling -> ladder wins.
        assertEquals(
            500L,
            StreamingThrottle.throttleFor(
                len = 30_000,
                unflushedChars = 2_000,
                sprintMs = StreamingThrottle.PARSE_SPRINT_MS,
            ),
        )
        assertEquals(
            1_000L,
            streamingThrottleFor(content = "^".repeat(200_000), unflushedChars = 2_000),
        )
    }

    @Test
    fun justUnderTheBacklogThresholdKeepsTheLadder() {
        assertEquals(
            2_000L,
            StreamingThrottle.throttleFor(len = 200_000, unflushedChars = StreamingThrottle.TAIL_SPRINT_CHARS - 1),
        )
    }
}
