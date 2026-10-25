package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-stop-dup-row] Contract for the interrupted-turn partial text
 * decision. Regression: stopping during a tool execution right after a round
 * boundary made `unpersistedAssistantText()` return "" (everything streamed
 * so far was already committed by the round persist), and the old unconditional
 * fallback re-persisted the canonical message's FULL cumulative content as a
 * second assistant row — after reload the reply rendered twice (split
 * per-round paragraphs + one run-on duplicate under the fold bar).
 */
class StopPartialTextTest {

    @Test
    fun `unpersisted tail wins over captured content`() {
        assertEquals(
            "round three tail",
            resolveInterruptedPartialText(
                unpersisted = "round three tail",
                capturedContent = "round one round two round three tail",
                runPersistedAny = true,
            ),
        )
    }

    @Test
    fun `empty unpersisted with durable rounds commits nothing`() {
        // The duplicate-row regression: stop landed after the last round
        // persist marked the accumulated text, so nothing is unpersisted.
        assertEquals(
            "",
            resolveInterruptedPartialText(
                unpersisted = "",
                capturedContent = "device confirmed 2.0.40/240 plus every earlier round",
                runPersistedAny = true,
            ),
        )
    }

    @Test
    fun `empty unpersisted without any durable row rescues canonical content`() {
        // Stream died before the first round persist (or no ActiveRun at
        // all): the partial reply exists only in memory and must survive.
        assertEquals(
            "half a sentence",
            resolveInterruptedPartialText(
                unpersisted = "",
                capturedContent = "half a sentence",
                runPersistedAny = false,
            ),
        )
    }

    @Test
    fun `empty captured content stays empty regardless of persist state`() {
        assertEquals(
            "",
            resolveInterruptedPartialText(
                unpersisted = "",
                capturedContent = "",
                runPersistedAny = false,
            ),
        )
        assertEquals(
            "",
            resolveInterruptedPartialText(
                unpersisted = "",
                capturedContent = "",
                runPersistedAny = true,
            ),
        )
    }
}
