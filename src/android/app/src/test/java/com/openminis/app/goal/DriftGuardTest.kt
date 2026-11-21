package com.openminis.app.goal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftGuardTest {
    @Test
    fun bucketStartsAtTenAndIsStableWithinTheWindow() {
        assertEquals(0, DriftGuard.bucketFor(0))
        assertEquals(0, DriftGuard.bucketFor(9))
        assertEquals(10, DriftGuard.bucketFor(10))
        assertEquals(10, DriftGuard.bucketFor(19))
        assertEquals(20, DriftGuard.bucketFor(20))
    }

    @Test
    fun markerIsUniquePerBucket() {
        assertEquals("<!-- drift-check:10 -->", DriftGuard.markerFor(10))
        assertEquals("<!-- drift-check:20 -->", DriftGuard.markerFor(20))
    }

    @Test
    fun noteCarriesMarkerAndTheThreeQuestions() {
        val note = DriftGuard.buildNote(DriftGuard.markerFor(10), "<goal_context/>", 10)
        assertTrue(note.contains("<!-- drift-check:10 -->"))
        assertTrue(note.contains("Objective: restate"))
        assertTrue(note.contains("Verdict: on-track"))
        assertTrue(note.contains("<goal_context/>"))
    }

    @Test
    fun customCadenceChangesTheFloorAndTheBucket() {
        // everyN=5: the first check fires at round 5, not 10.
        assertEquals(0, DriftGuard.bucketFor(4, 5))
        assertEquals(5, DriftGuard.bucketFor(5, 5))
        assertEquals(15, DriftGuard.bucketFor(18, 5))
        // The note reports the configured cadence, not the shipped 10.
        val note = DriftGuard.buildNote(DriftGuard.markerFor(5), "", 5, 5)
        assertTrue(note.contains("## Drift check — 5 tool calls"))
        assertTrue(note.contains("the last 5 tool calls"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveCadenceIsRejected() {
        DriftGuard.bucketFor(50, 0)
    }

    @Test
    fun loopDetectionFiresOnlyOnIdenticalFullWindows() {
        val same = List(DriftGuard.LOOP_WINDOW) { "cmd-a" }
        assertTrue(DriftGuard.detectLoop(same))
        assertFalse(DriftGuard.detectLoop(same.take(DriftGuard.LOOP_WINDOW - 1)))
        assertFalse(DriftGuard.detectLoop(List(DriftGuard.LOOP_WINDOW) { "cmd-${it % 2}" }))
        assertFalse(DriftGuard.detectLoop(List(DriftGuard.LOOP_WINDOW) { "" }))
        assertFalse(DriftGuard.detectLoop(emptyList()))
    }

    @Test
    fun noteCarriesTheTriggerReasonWhenPresent() {
        val note = DriftGuard.buildNote(DriftGuard.markerFor(10), "", 10, 10, "loop: repeated calls")
        assertTrue(note.contains("Trigger: loop: repeated calls"))
        assertFalse(DriftGuard.buildNote(DriftGuard.markerFor(10), "", 10).contains("Trigger:"))
    }
}
