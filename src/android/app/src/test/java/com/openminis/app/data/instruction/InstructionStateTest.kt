package com.openminis.app.data.instruction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionStateTest {

    // ---- observe: initial ----

    @Test
    fun observeWithNoStoredRowIsInitialAndDeltaEqualsSanitizedCurrent() {
        val obs = InstructionState.observe(
            storedValues = null,
            current = mapOf(InstructionState.KEY_GOAL to "g1"),
        )
        assertTrue(obs.initial)
        assertTrue(obs.previousValues.isEmpty())
        assertEquals(mapOf(InstructionState.KEY_GOAL to "g1"), obs.currentValues)
        assertEquals(mapOf(InstructionState.KEY_GOAL to "g1"), obs.delta.changes)
    }

    @Test
    fun observeInitialWithEmptySourcesIsStillInitialWithEmptyDelta() {
        val obs = InstructionState.observe(storedValues = null, current = emptyMap())
        assertTrue(obs.initial)
        assertTrue(obs.delta.isEmpty)
    }

    // ---- observe: diffs ----

    @Test
    fun observeUnchangedSourcesProducesEmptyDelta() {
        val stored = mapOf(InstructionState.KEY_GOAL to "g1")
        val obs = InstructionState.observe(stored, mapOf(InstructionState.KEY_GOAL to "g1"))
        assertFalse(obs.initial)
        assertTrue(obs.delta.isEmpty)
    }

    @Test
    fun observeChangedValueReportsTheChangedKeyOnly() {
        val stored = mapOf(
            InstructionState.KEY_GOAL to "g1",
            InstructionState.KEY_APP_GLOBAL to "a1",
        )
        val obs = InstructionState.observe(
            stored,
            mapOf(InstructionState.KEY_GOAL to "g2", InstructionState.KEY_APP_GLOBAL to "a1"),
        )
        assertEquals(mapOf(InstructionState.KEY_GOAL to "g2"), obs.delta.changes)
    }

    @Test
    fun observeRemovedKeyReportsNullValue() {
        val stored = mapOf(
            InstructionState.KEY_GOAL to "g1",
            InstructionState.KEY_SESSION_GLOBAL to "s1",
        )
        val obs = InstructionState.observe(stored, mapOf(InstructionState.KEY_GOAL to "g1"))
        assertEquals(mapOf(InstructionState.KEY_SESSION_GLOBAL to null), obs.delta.changes)
    }

    @Test
    fun observeAddedKeyReportsNewValue() {
        val stored = mapOf(InstructionState.KEY_GOAL to "g1")
        val obs = InstructionState.observe(
            stored,
            mapOf(InstructionState.KEY_GOAL to "g1", InstructionState.KEY_APP_GLOBAL to "a1"),
        )
        assertEquals(mapOf(InstructionState.KEY_APP_GLOBAL to "a1"), obs.delta.changes)
    }

    @Test
    fun observeIgnoresUnknownKeysAndEmptyValues() {
        val obs = InstructionState.observe(
            storedValues = null,
            current = mapOf(
                "rogue" to "r1",
                InstructionState.KEY_GOAL to "",
                InstructionState.KEY_APP_GLOBAL to "a1",
            ),
        )
        assertEquals(mapOf(InstructionState.KEY_APP_GLOBAL to "a1"), obs.currentValues)
    }

    // ---- applyDelta ----

    @Test
    fun applyDeltaFoldsAddChangeAndRemoveOntoPrevious() {
        val previous = mapOf(
            InstructionState.KEY_GOAL to "g1",
            InstructionState.KEY_SESSION_GLOBAL to "s1",
        )
        val delta = InstructionState.InstructionDelta(
            mapOf(
                InstructionState.KEY_GOAL to "g2",
                InstructionState.KEY_SESSION_GLOBAL to null,
                InstructionState.KEY_APP_GLOBAL to "a1",
            ),
        )
        assertEquals(
            mapOf(
                InstructionState.KEY_GOAL to "g2",
                InstructionState.KEY_APP_GLOBAL to "a1",
            ),
            InstructionState.applyDelta(previous, delta),
        )
    }

    @Test
    fun applyDeltaRoundTripsObserve() {
        val previous = mapOf(InstructionState.KEY_GOAL to "g1")
        val current = mapOf(InstructionState.KEY_GOAL to "g2", InstructionState.KEY_APP_GLOBAL to "a1")
        val obs = InstructionState.observe(previous, current)
        assertEquals(current, InstructionState.applyDelta(previous, obs.delta))
    }

    // ---- render ----

    @Test
    fun renderInitialListsSortedKeysWithPlusPrefixes() {
        val text = InstructionState.renderInitial(
            mapOf(
                InstructionState.KEY_APP_GLOBAL to "a1",
                InstructionState.KEY_GOAL to "g1",
            ),
        )
        assertEquals("+ app-global: a1\n+ goal: g1", text)
    }

    @Test
    fun renderUpdateShowsRemovedThenChangedPairs() {
        val previous = mapOf(InstructionState.KEY_GOAL to "g1", InstructionState.KEY_SESSION_GLOBAL to "s1")
        val current = mapOf(InstructionState.KEY_GOAL to "g2")
        val obs = InstructionState.observe(previous, current)
        val text = InstructionState.renderUpdate(previous, current, obs.delta)
        assertEquals("- goal: g1\n+ goal: g2\n- session-global", text)
    }

    @Test
    fun renderUpdateTruncatesLongValues() {
        val longValue = "x".repeat(300)
        val obs = InstructionState.observe(null, mapOf(InstructionState.KEY_GOAL to longValue))
        val text = InstructionState.renderUpdate(emptyMap(), obs.currentValues, obs.delta)
        assertTrue(text.endsWith("${"x".repeat(InstructionState.RENDER_VALUE_LIMIT)}[truncated]"))
        assertTrue(text.length <= InstructionState.RENDER_VALUE_LIMIT + 20)
    }

    // ---- JSON round-trip ----

    @Test
    fun valuesJsonAndParseRoundTripAllKeyTypes() {
        val values = mapOf(
            InstructionState.KEY_GOAL to "g\"1\\\n\t",
            InstructionState.KEY_APP_GLOBAL to "a1",
        )
        val json = InstructionState.valuesJson(values)
        assertEquals(values, InstructionState.parseValues(json))
    }

    @Test
    fun parseValuesPreservesUnknownKeysAndSanitizeDropsThem() {
        // Decoding is pure: nested instruction values (goal JSON) must round-trip
        // intact, so parseValues keeps every key. Semantic filtering is
        // sanitizeValues' job on the observe/applyDelta paths.
        val json = "{\"rogue\":\"r1\",\"goal\":\"g1\"}"
        assertEquals(
            mapOf("rogue" to "r1", InstructionState.KEY_GOAL to "g1"),
            InstructionState.parseValues(json),
        )
        assertEquals(
            mapOf(InstructionState.KEY_GOAL to "g1"),
            InstructionState.sanitizeValues(InstructionState.parseValues(json)),
        )
    }

    @Test
    fun parseValuesReturnsEmptyOnMalformedInput() {
        assertTrue(InstructionState.parseValues("{not json").isEmpty())
        assertTrue(InstructionState.parseValues("").isEmpty())
    }

    @Test
    fun escapeJsonHandlesControlCharacters() {
        assertEquals("a\\nb\\tc\\\"d\\\\e", InstructionState.escapeJson("a\nb\tc\"d\\e"))
    }
}
