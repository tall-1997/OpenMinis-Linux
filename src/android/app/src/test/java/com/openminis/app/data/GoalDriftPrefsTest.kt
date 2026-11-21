package com.openminis.app.data

import com.openminis.app.goal.GoalDriftReconciler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [T-goal-drift-reconciler] The stall-threshold knob: default 8, user-settable
 * within [GoalDriftReconciler.STALL_TURNS_MIN]..[STALL_TURNS_MAX]. Cache-level
 * tests only (same idiom as [GuardPrefsTest]) — the SharedPreferences round
 * trip is exercised on device; the reconciler clamps again on its side, so a
 * hand-edited prefs file cannot disable or bury the net.
 */
class GoalDriftPrefsTest {

    @Before
    fun setUp() {
        GoalDriftPrefs.resetForTest()
    }

    @After
    fun tearDown() {
        GoalDriftPrefs.resetForTest()
    }

    @Test
    fun `the shipped default is eight turns`() {
        assertEquals(8, GoalDriftPrefs.stallTurns())
        assertEquals(GoalDriftReconciler.STALL_TURNS_BEFORE_NUDGE, GoalDriftPrefs.stallTurns())
    }

    @Test
    fun `setting a value is remembered until changed`() {
        GoalDriftPrefs.setStallTurns(12)
        assertEquals(12, GoalDriftPrefs.stallTurns())
        GoalDriftPrefs.setStallTurns(6)
        assertEquals(6, GoalDriftPrefs.stallTurns())
    }

    @Test
    fun `values below the minimum clamp to four`() {
        GoalDriftPrefs.setStallTurns(0)
        assertEquals(GoalDriftReconciler.STALL_TURNS_MIN, GoalDriftPrefs.stallTurns())
        GoalDriftPrefs.setStallTurns(-7)
        assertEquals(GoalDriftReconciler.STALL_TURNS_MIN, GoalDriftPrefs.stallTurns())
    }

    @Test
    fun `values above the maximum clamp to thirty-two`() {
        GoalDriftPrefs.setStallTurns(99)
        assertEquals(GoalDriftReconciler.STALL_TURNS_MAX, GoalDriftPrefs.stallTurns())
    }

    @Test
    fun `setting the same value does not bump the revision`() {
        val before = GoalDriftPrefs.revision.value
        GoalDriftPrefs.setStallTurns(GoalDriftPrefs.stallTurns())
        assertEquals(before, GoalDriftPrefs.revision.value)
        GoalDriftPrefs.setStallTurns(GoalDriftPrefs.stallTurns() + 1)
        assertEquals(before + 1, GoalDriftPrefs.revision.value)
    }

    @Test
    fun `clamp bounds stay aligned with the reconciler constants`() {
        // Two sources of truth for the knob's range would drift apart; pin
        // the prefs side to the reconciler's constants here.
        assertEquals(4, GoalDriftReconciler.STALL_TURNS_MIN)
        assertEquals(32, GoalDriftReconciler.STALL_TURNS_MAX)
    }
}
