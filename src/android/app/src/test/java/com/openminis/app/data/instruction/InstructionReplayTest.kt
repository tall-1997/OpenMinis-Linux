package com.openminis.app.data.instruction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-p1-instruction-snapshot] Pure replay contract: folding the event stream
 * must reproduce the frozen `currentValuesJson`, removals must drop keys, the
 * leading initial row must not be treated as a delta, and a second initial row
 * must stop replay instead of corrupting the baseline.
 */
class InstructionReplayTest {

    private fun update(seq: Long, isInitial: Boolean, delta: String?) = InstructionUpdateEntity(
        sessionId = "s1",
        seq = seq,
        isInitial = isInitial,
        deltaJson = delta,
        renderedText = "",
        createdAt = seq,
    )

    private val initial = InstructionState.valuesJson(
        mapOf(InstructionState.KEY_GOAL to "g1"),
    )

    @Test
    fun rebuildFromInitialPlusDeltasReproducesCurrentValues() {
        val updates = listOf(
            update(1, isInitial = true, delta = null),
            update(2, isInitial = false, delta = InstructionState.deltaJson(
                InstructionState.InstructionDelta(mapOf(InstructionState.KEY_GOAL to "g2")),
            )),
            update(3, isInitial = false, delta = InstructionState.deltaJson(
                InstructionState.InstructionDelta(mapOf(InstructionState.KEY_APP_GLOBAL to "rules")),
            )),
        )
        val result = InstructionReplay.rebuild(initial, updates)
        assertEquals(
            mapOf(InstructionState.KEY_GOAL to "g2", InstructionState.KEY_APP_GLOBAL to "rules"),
            result.values,
        )
        assertEquals(2, result.applied)
        assertEquals(0, result.epochRestarts)
        assertEquals(InstructionReplay.STOP_COMPLETE, result.stopReason)
    }

    @Test
    fun removalDeltaDropsTheKey() {
        val updates = listOf(
            update(1, isInitial = true, delta = null),
            update(2, isInitial = false, delta = InstructionState.deltaJson(
                InstructionState.InstructionDelta(mapOf(InstructionState.KEY_GOAL to null)),
            )),
        )
        val result = InstructionReplay.rebuild(initial, updates)
        assertTrue(result.values.isEmpty())
        assertEquals(1, result.applied)
    }

    @Test
    fun initialRowsContributeNoDeltaAndAreSkipped() {
        val updates = listOf(update(1, isInitial = true, delta = null))
        val result = InstructionReplay.rebuild(initial, updates)
        assertEquals(InstructionState.parseValues(initial), result.values)
        assertEquals(0, result.applied)
    }

    @Test
    fun epochRestartIsFlaggedAndStopsReplay() {
        val updates = listOf(
            update(1, isInitial = true, delta = null),
            update(2, isInitial = false, delta = InstructionState.deltaJson(
                InstructionState.InstructionDelta(mapOf(InstructionState.KEY_GOAL to "g2")),
            )),
            update(3, isInitial = true, delta = null),
            update(4, isInitial = false, delta = InstructionState.deltaJson(
                InstructionState.InstructionDelta(mapOf(InstructionState.KEY_GOAL to "g3")),
            )),
        )
        val result = InstructionReplay.rebuild(initial, updates)
        assertEquals(1, result.epochRestarts)
        assertEquals(InstructionReplay.STOP_EPOCH_RESTART, result.stopReason)
        // Stops at the boundary: the post-restart delta must not be folded.
        assertEquals("g2", result.values.getValue(InstructionState.KEY_GOAL))
        assertEquals(1, result.applied)
    }

    @Test
    fun emptyUpdatesReturnTheInitialValues() {
        val result = InstructionReplay.rebuild(initial, emptyList())
        assertEquals(InstructionState.parseValues(initial), result.values)
        assertEquals(0, result.applied)
        assertEquals(InstructionReplay.STOP_COMPLETE, result.stopReason)
    }

    @Test
    fun outOfOrderInputIsSortedBySeq() {
        val updates = listOf(
            update(2, isInitial = false, delta = InstructionState.deltaJson(
                InstructionState.InstructionDelta(mapOf(InstructionState.KEY_GOAL to "g2")),
            )),
            update(3, isInitial = false, delta = InstructionState.deltaJson(
                InstructionState.InstructionDelta(mapOf(InstructionState.KEY_GOAL to "g3")),
            )),
        )
        val result = InstructionReplay.rebuild(initial, updates)
        assertEquals("g3", result.values.getValue(InstructionState.KEY_GOAL))
        assertEquals(2, result.applied)
    }
}
