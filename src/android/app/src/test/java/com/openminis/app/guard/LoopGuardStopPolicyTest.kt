package com.openminis.app.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-loop-guard-stop-policy] The shipped behaviour is unchanged by default; the
 * policy exists so a legitimate long poll can opt out of the hard bound without
 * patching the class.
 */
class LoopGuardStopPolicyTest {

    private fun repeatCall(guard: LoopGuard, n: Int, sig: String = "tap(a)"): Signal? {
        var last: Signal? = null
        repeat(n) {
            last = guard.record(sig, ok = true, outcome = Outcome.Effective).lastOrNull()?.first
                ?: last
        }
        return last
    }

    @Test
    fun stopIsEnabledByDefault() {
        assertTrue(LoopGuard.STOP_ENABLED_DEFAULT)
        assertTrue(LoopGuard().stopEnabled)
    }

    @Test
    fun theRepeatLadderStillStopsWhenThePolicyIsOn() {
        val guard = LoopGuard()
        repeat(Signal.Repeat.stopAt - 1) { guard.record("tap(a)", ok = true, outcome = Outcome.Effective) }
        assertEquals(Signal.Repeat, guard.check("tap(a)"))
        assertEquals(Signal.Repeat, guard.stopped)
    }

    @Test
    fun aDisabledStopTurnsTheLastRungIntoOneMoreReminder() {
        val guard = LoopGuard().apply { stopEnabled = false }
        var hits: List<Pair<Signal, Int>> = emptyList()
        repeat(Signal.Repeat.stopAt) {
            hits = guard.record("tap(a)", ok = true, outcome = Outcome.Effective)
        }
        assertTrue("the model is still told about the last rung", hits.isNotEmpty())
        assertEquals(Signal.Repeat, hits.last().first)
        assertEquals(Signal.Repeat.stopAt, hits.last().second)
        assertNull("the session must not be stopped", guard.stopped)
        assertNull("and the next call is not refused", guard.check("tap(a)"))
    }

    /**
     * [T-loop-guard-all-rungs] A call that advances TWO ladders must report both.
     * The old single-hit return advanced the second signal's `warned` counter
     * while dropping its notice, so that rung could never fire again: the model
     * was told about the repeat and never about the no-effect that made it a
     * loop.
     */
    @Test
    fun aCallThatAdvancesTwoLaddersReportsBothRungs() {
        val guard = LoopGuard().apply { stopEnabled = false }
        var last: List<Pair<Signal, Int>> = emptyList()
        // Both ladders share the same rung values, so one NoEffect call at the
        // final count crosses both.
        val rungs = minOf(Signal.Repeat.stopAt, Signal.NoEffect.stopAt)
        repeat(rungs) {
            last = guard.record("tap(a)", ok = true, outcome = Outcome.NoEffect)
        }
        val signals = last.map { it.first }
        assertTrue("expected both ladders in one report, got $signals", signals.containsAll(listOf(Signal.Repeat, Signal.NoEffect)))
    }

    @Test
    fun aDisabledStopKeepsTheFailureLadderAdvisoryToo() {
        val guard = LoopGuard().apply { stopEnabled = false }
        repeat(Signal.Failure.stopAt) { guard.record(null, ok = false, outcome = Outcome.None) }
        assertNull(guard.stopped)
    }

    @Test
    fun reEnablingThePolicyRestoresTheBound() {
        val guard = LoopGuard().apply { stopEnabled = false }
        repeat(Signal.Repeat.stopAt) { guard.record("tap(a)", ok = true, outcome = Outcome.Effective) }
        assertNull(guard.stopped)

        guard.stopEnabled = true
        assertEquals(Signal.Repeat, guard.check("tap(a)"))
    }

    @Test
    fun resetClearsAnyStopAndTheWarnedRungs() {
        val guard = LoopGuard()
        repeat(Signal.Repeat.stopAt) { guard.record("tap(a)", ok = true, outcome = Outcome.Effective) }
        assertEquals(Signal.Repeat, guard.stopped)
        guard.reset()
        assertNull(guard.stopped)
        assertTrue(guard.storedSignatures().isEmpty())
    }
}
