package com.openminis.app.goal

import com.openminis.app.tools.AgentPlanStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-goal-drift-reconciler] The reconciler is the drift fix's brain: its
 * signals must fire exactly on structural drift and never on ordinary
 * between-step pauses. These tests pin the counter contract, the reset
 * rules, the delivered-once marker and the nudge text — including the
 * degenerate inputs the F1 field report taught this codebase to fear
 * (`startsWith("")`-style bypasses live one careless comparison away).
 */
class GoalDriftReconcilerTest {

    private fun plan(id: String, status: String = "pending", position: Int = 0) =
        AgentPlanStore.Plan(id = id, title = "step $id", status = status, position = position)

    // -- fingerprint --------------------------------------------------------

    @Test
    fun `empty board has no fingerprint`() {
        assertNull(GoalDriftReconciler.boardFingerprint(emptyList()))
    }

    @Test
    fun `same board yields the same fingerprint regardless of list order`() {
        val a = GoalDriftReconciler.boardFingerprint(
            listOf(plan("1", position = 0), plan("2", position = 1)),
        )
        val b = GoalDriftReconciler.boardFingerprint(
            listOf(plan("2", position = 1), plan("1", position = 0)),
        )
        assertEquals(a, b)
    }

    @Test
    fun `a status flip changes the fingerprint`() {
        val before = GoalDriftReconciler.boardFingerprint(listOf(plan("1", "pending")))
        val after = GoalDriftReconciler.boardFingerprint(listOf(plan("1", "done")))
        assertNotEquals(before, after)
    }

    @Test
    fun `an added step changes the fingerprint`() {
        val before = GoalDriftReconciler.boardFingerprint(listOf(plan("1")))
        val after = GoalDriftReconciler.boardFingerprint(listOf(plan("1"), plan("2")))
        assertNotEquals(before, after)
    }

    // -- next(): stall counting and resets -----------------------------------

    @Test
    fun `an unchanged board advances the stall counter`() {
        val state = GoalDriftStore.DriftState(lastBoardSha = "fp-1", stallTurns = 3)
        val next = GoalDriftReconciler.next(state, goalActive = true, fingerprint = "fp-1")
        assertEquals(4, next.stallTurns)
        assertEquals("fp-1", next.lastBoardSha)
    }

    @Test
    fun `a board change resets the counter and the delivered-once marker`() {
        val state = GoalDriftStore.DriftState(
            lastBoardSha = "fp-1",
            stallTurns = 9,
            nudgedBoardSha = "fp-1",
        )
        val next = GoalDriftReconciler.next(state, goalActive = true, fingerprint = "fp-2")
        assertEquals("fp-2", next.lastBoardSha)
        assertEquals(0, next.stallTurns)
        assertNull("a NEW stall must earn its own nudge", next.nudgedBoardSha)
    }

    @Test
    fun `a goal that is not active resets everything`() {
        val state = GoalDriftStore.DriftState(lastBoardSha = "fp-1", stallTurns = 9, nudgedBoardSha = "fp-1")
        val next = GoalDriftReconciler.next(state, goalActive = false, fingerprint = "fp-1")
        assertEquals(GoalDriftStore.DriftState(), next)
    }

    @Test
    fun `a missing board resets everything`() {
        val state = GoalDriftStore.DriftState(lastBoardSha = "fp-1", stallTurns = 9)
        val next = GoalDriftReconciler.next(state, goalActive = true, fingerprint = null)
        assertEquals(GoalDriftStore.DriftState(), next)
    }

    @Test
    fun `no board ever counts as a stall`() {
        // A session without a plan board has nothing to reconcile against:
        // "board did not move" is meaningless when there is no board.
        var state = GoalDriftStore.DriftState()
        repeat(20) { state = GoalDriftReconciler.next(state, goalActive = true, fingerprint = null) }
        assertEquals(0, state.stallTurns)
    }

    // -- nudgeIfOwed ---------------------------------------------------------

    private fun stalled(): GoalDriftStore.DriftState =
        GoalDriftStore.DriftState(lastBoardSha = "fp-stalled", stallTurns = GoalDriftReconciler.STALL_TURNS_BEFORE_NUDGE)

    @Test
    fun `below the threshold nothing is owed`() {
        val state = GoalDriftStore.DriftState(lastBoardSha = "fp-1", stallTurns = GoalDriftReconciler.STALL_TURNS_BEFORE_NUDGE - 1)
        assertNull(GoalDriftReconciler.nudgeIfOwed(state, objective = "ship it", acceptance = null, pendingCount = 2))
    }

    @Test
    fun `at the threshold the nudge fires with objective and pending count`() {
        val text = GoalDriftReconciler.nudgeIfOwed(
            stalled(),
            objective = "migrate the auth module to v2",
            acceptance = null,
            pendingCount = 3,
        )
        requireNotNull(text)
        assertTrue(text.contains("migrate the auth module to v2"))
        assertTrue(text.contains("3"))
        assertTrue(text.contains("<system-reminder>"))
    }

    @Test
    fun `the nudge is delivered once per stalled fingerprint`() {
        val nudged = stalled().copy(nudgedBoardSha = "fp-stalled")
        assertNull(GoalDriftReconciler.nudgeIfOwed(nudged, objective = "x", acceptance = null, pendingCount = 2))
    }

    @Test
    fun `a stalled board with no fingerprint never nudges`() {
        val state = GoalDriftStore.DriftState(lastBoardSha = null, stallTurns = 99)
        assertNull(GoalDriftReconciler.nudgeIfOwed(state, objective = "x", acceptance = null, pendingCount = 2))
    }

    @Test
    fun `acceptance criteria are rendered as checked bullets`() {
        val text = GoalDriftReconciler.nudgeIfOwed(
            stalled(),
            objective = "ship it",
            acceptance = "no regression in unit tests\nschema migrated\nUI reviewed",
            pendingCount = 1,
        )
        requireNotNull(text)
        assertTrue(text.contains("Acceptance criteria:"))
        assertTrue(text.contains("- no regression in unit tests"))
        assertTrue(text.contains("- schema migrated"))
        assertTrue(text.contains("- UI reviewed"))
    }

    @Test
    fun `missing acceptance names the objective as the only done-check`() {
        val text = GoalDriftReconciler.nudgeIfOwed(stalled(), objective = "x", acceptance = null, pendingCount = 1)
        requireNotNull(text)
        assertTrue(text.contains("none recorded"))
    }

    @Test
    fun `acceptance bullets are bounded`() {
        val long = (1..50).joinToString("\n") { "criterion $it" }
        val text = GoalDriftReconciler.nudgeIfOwed(stalled(), objective = "x", acceptance = long, pendingCount = 1)
        requireNotNull(text)
        assertTrue(text.contains("criterion 8"))
        assertTrue(!text.contains("criterion 9"))
    }

    // -- threshold boundary pins the knob ------------------------------------

    @Test
    fun `threshold is eight turns`() {
        assertEquals(8, GoalDriftReconciler.STALL_TURNS_BEFORE_NUDGE)
    }

    @Test
    fun `turns one to seven stay silent and turn eight speaks`() {
        var state = GoalDriftStore.DriftState(lastBoardSha = "fp-1", stallTurns = 0)
        var spokenAtTurn: Int? = null
        repeat(10) { index ->
            state = GoalDriftReconciler.next(state, goalActive = true, fingerprint = "fp-1")
            val owed = GoalDriftReconciler.nudgeIfOwed(state, objective = "x", acceptance = null, pendingCount = 2)
            if (owed != null && spokenAtTurn == null) spokenAtTurn = index + 1
        }
        assertEquals("the first nudge is owed exactly at the threshold", 8, spokenAtTurn)
        assertEquals(
            "the counter keeps counting past the threshold",
            GoalDriftReconciler.STALL_TURNS_BEFORE_NUDGE + 2,
            state.stallTurns,
        )
    }
}
