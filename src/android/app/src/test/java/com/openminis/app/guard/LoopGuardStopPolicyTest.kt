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
            last = guard.record(sig, ok = true, outcome = Outcome.Effective)?.first
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
        var hit: Pair<Signal, Int>? = null
        repeat(Signal.Repeat.stopAt) {
            hit = guard.record("tap(a)", ok = true, outcome = Outcome.Effective)
        }
        assertNotNull("the model is still told about the last rung", hit)
        assertEquals(Signal.Repeat, hit!!.first)
        assertEquals(Signal.Repeat.stopAt, hit!!.second)
        assertNull("the session must not be stopped", guard.stopped)
        assertNull("and the next call is not refused", guard.check("tap(a)"))
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
