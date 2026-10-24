package com.openminis.app.data

import com.openminis.app.data.repository.MultiAgentSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiAgentSettingsTest {

    @Test
    fun clampConcurrent_staysInRange() {
        assertEquals(1, MultiAgentSettings.clampConcurrent(0))
        assertEquals(1, MultiAgentSettings.clampConcurrent(-3))
        assertEquals(8, MultiAgentSettings.clampConcurrent(99))
        assertEquals(3, MultiAgentSettings.clampConcurrent(3))
    }

    @Test
    fun clampTurns_defaultsToSixtyRange() {
        assertEquals(1, MultiAgentSettings.clampTurns(0))
        assertEquals(12, MultiAgentSettings.clampTurns(12))
        assertEquals(60, MultiAgentSettings.clampTurns(99))
    }

    @Test
    fun resizeSlots_padsTruncatesAndKeepsDuplicates() {
        val ids = listOf("a", "b", "a", "c", "d", "")
        assertEquals(listOf("a", "b", "a"), MultiAgentSettings.resizeSlots(ids, 3))
        assertEquals(listOf("a"), MultiAgentSettings.resizeSlots(ids, 1))
        assertEquals(listOf("a", "b", "", ""), MultiAgentSettings.resizeSlots(listOf("a", "b"), 4))
        assertEquals(listOf("", "", ""), MultiAgentSettings.resizeSlots(emptyList(), 3))
    }

    @Test
    fun setSlot_writesIndexWithoutShiftingNeighbors() {
        assertEquals(
            listOf("a", "x", ""),
            MultiAgentSettings.setSlot(listOf("a", "b"), 1, "x", 3),
        )
        assertEquals(
            listOf("", "m2", ""),
            MultiAgentSettings.setSlot(emptyList(), 1, "m2", 3),
        )
        assertEquals(
            listOf("a", "", "c"),
            MultiAgentSettings.setSlot(listOf("a", "b", "c"), 1, "", 3),
        )
    }

    @Test
    fun clampSupervisorTurns_staysInRange() {
        assertEquals(1, MultiAgentSettings.clampSupervisorTurns(0))
        assertEquals(1, MultiAgentSettings.clampSupervisorTurns(-3))
        assertEquals(50, MultiAgentSettings.clampSupervisorTurns(50))
        assertEquals(200, MultiAgentSettings.clampSupervisorTurns(999))
    }

    @Test
    fun resolveWorkerModelId_prefersRequestedThenSlotThenSession() {
        assertEquals("req", MultiAgentSettings.resolveWorkerModelId("req", "slot", "active"))
        assertEquals("slot", MultiAgentSettings.resolveWorkerModelId(null, "slot", "active"))
        assertEquals("active", MultiAgentSettings.resolveWorkerModelId(null, "", "active"))
        assertEquals("active", MultiAgentSettings.resolveWorkerModelId("  ", "", "active"))
    }

    @Test
    fun pickModelId_prefersRequestedWhenInPool() {
        val pool = listOf("m1", "m2", "m3")
        assertEquals("m2", MultiAgentSettings.pickModelId(pool, "m2", 0))
        assertEquals("m1", MultiAgentSettings.pickModelId(pool, "missing", 0))
        assertEquals("m3", MultiAgentSettings.pickModelId(pool, null, 2))
        assertEquals("m1", MultiAgentSettings.pickModelId(pool, null, 3))
    }

    @Test
    fun pickModelId_emptySlotsUseMainUnlessRequested() {
        assertEquals("x", MultiAgentSettings.pickModelId(emptyList(), "x", 0))
        assertNull(MultiAgentSettings.pickModelId(emptyList(), null, 0))
        assertEquals("x", MultiAgentSettings.pickModelId(listOf("", "", ""), "x", 0))
        assertNull(MultiAgentSettings.pickModelId(listOf("", "", ""), null, 1))
    }

    @Test
    fun pickModelId_mapsConcurrentIndexToSlotIncludingBlanks() {
        val slots = listOf("m1", "", "m3")
        assertEquals("m1", MultiAgentSettings.pickModelId(slots, null, 0))
        assertNull(MultiAgentSettings.pickModelId(slots, null, 1))
        assertEquals("m3", MultiAgentSettings.pickModelId(slots, null, 2))
        assertEquals("m1", MultiAgentSettings.pickModelId(slots, null, 3))
        assertEquals("m1", MultiAgentSettings.pickModelId(listOf("m1", "m1"), null, 1))
    }

    @Test
    fun retainLive_blanksStaleIdsWithoutCompactingSlots() {
        val stored = listOf("aa9ff554-gone", "live-a", "live-b")
        val live = setOf("live-a", "live-b", "live-c")
        assertEquals(listOf("", "live-a", "live-b"), MultiAgentSettings.retainLive(stored, live, 3))
        assertEquals(listOf("", "", ""), MultiAgentSettings.retainLive(stored, emptySet(), 3))
        assertEquals(listOf("", "live-a"), MultiAgentSettings.retainLive(stored, live, 2))
    }

    @Test
    fun teamModelNames_labelsEachSlotAndOmitsUuids() {
        val stored = listOf("aa9ff554-gone", "live-a")
        val names = mapOf("live-a" to "GPT")
        assertEquals(
            "sub-agent 1=the main session model, sub-agent 2=GPT",
            MultiAgentSettings.teamModelNames(stored, names),
        )
        assertEquals("the main session model", MultiAgentSettings.teamModelNames(stored, emptyMap()))
        assertEquals("the main session model", MultiAgentSettings.teamModelNames(listOf("", ""), emptyMap()))
    }
}
