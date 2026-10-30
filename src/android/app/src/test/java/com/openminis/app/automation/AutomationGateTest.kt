package com.openminis.app.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-automation-rules] The shared fire-admission gate: 60 s per-rule
 * debounce and a rolling 24 h / 50 cap, both driven by an injected clock so
 * the windows are exact.
 */
class AutomationGateTest {

    private class FakeClock {
        var now: Long = 1_000_000L
        fun time(): Long = now
    }

    private class RunCounts {
        private val counts = HashMap<String, Int>()
        fun set(ruleId: String, n: Int) { counts[ruleId] = n }
        fun provider() = { ruleId: String, sinceMs: Long -> counts[ruleId] ?: 0 }
    }

    @Test
    fun firstFireAllowed() {
        val clock = FakeClock()
        val gate = AutomationGate(clock::time, RunCounts().provider())
        assertTrue(gate.tryAcquire("r").allowed)
    }

    @Test
    fun secondFireWithinSixtySecondsDebounced() {
        val clock = FakeClock()
        val gate = AutomationGate(clock::time, RunCounts().provider())
        assertTrue(gate.tryAcquire("r").allowed)
        clock.now += 59_999
        val d = gate.tryAcquire("r")
        assertFalse(d.allowed)
        assertEquals("debounced", d.reason)
    }

    @Test
    fun fireAfterSixtySecondsAllowed() {
        val clock = FakeClock()
        val gate = AutomationGate(clock::time, RunCounts().provider())
        assertTrue(gate.tryAcquire("r").allowed)
        clock.now += 60_000
        assertTrue(gate.tryAcquire("r").allowed)
    }

    @Test
    fun debounceIsPerRule() {
        val clock = FakeClock()
        val gate = AutomationGate(clock::time, RunCounts().provider())
        assertTrue(gate.tryAcquire("a").allowed)
        assertTrue(gate.tryAcquire("b").allowed)
    }

    @Test
    fun burstOnSameMillisClaimsOnce() {
        // A notification burst landing on two threads/two events in the same
        // millisecond: the first claims, the second is dropped.
        val clock = FakeClock()
        val gate = AutomationGate(clock::time, RunCounts().provider())
        assertTrue(gate.tryAcquire("r").allowed)
        val d = gate.tryAcquire("r")
        assertFalse(d.allowed)
        assertEquals("debounced", d.reason)
    }

    @Test
    fun rateLimitBlocksAfterFiftyActionableRuns() {
        val clock = FakeClock()
        val counts = RunCounts()
        val gate = AutomationGate(clock::time, counts.provider())
        counts.set("r", 49)
        assertTrue(gate.tryAcquire("r").allowed)
        counts.set("r", 50)
        clock.now += AutomationGate.DEBOUNCE_MS // past the debounce, into the cap
        val d = gate.tryAcquire("r")
        assertFalse(d.allowed)
        assertEquals("rate_limited", d.reason)
    }

    @Test
    fun rateLimitUnblocksWhenWindowSlides() {
        val clock = FakeClock()
        // 50 runs fired at epoch 0. The manager asks for the count since
        // (now - 24h): those runs still count while the window covers time 0,
        // and age out once it slides past — with a fixed clock the boundary
        // is exact.
        val provider = { _: String, sinceMs: Long -> if (sinceMs <= 0L) 50 else 0 }
        val gate = AutomationGate(clock::time, provider)
        assertFalse(gate.tryAcquire("r").allowed)
        clock.now += AutomationGate.WINDOW_MS + 1
        assertTrue(gate.tryAcquire("r").allowed)
    }

    @Test
    fun skippedRunsDoNotCountTowardCap() {
        val clock = FakeClock()
        // The DAO query filters `outcome NOT LIKE 'skipped%'`, so a table full
        // of skipped rows yields 0 actionable runs. Simulate that here: every
        // attempt (after its debounce window) is allowed forever.
        val gate = AutomationGate(clock::time) { _, _ -> 0 }
        repeat(10) {
            assertTrue(gate.tryAcquire("r").allowed)
            clock.now += AutomationGate.DEBOUNCE_MS
        }
    }

    @Test
    fun forgetClearsDebounceMemory() {
        val clock = FakeClock()
        val gate = AutomationGate(clock::time, RunCounts().provider())
        assertTrue(gate.tryAcquire("r").allowed)
        gate.forget("r")
        assertTrue(gate.tryAcquire("r").allowed)
    }

    @Test
    fun rateLimitedAttemptKeepsClaimSoStormYieldsOneSkippedPerDebounce() {
        val clock = FakeClock()
        val counts = RunCounts().apply { set("r", 50) }
        val gate = AutomationGate(clock::time, counts.provider())
        assertFalse(gate.tryAcquire("r").allowed)
        clock.now += 10_000
        assertEquals("debounced", gate.tryAcquire("r").reason)
        clock.now += 60_000
        assertEquals("rate_limited", gate.tryAcquire("r").reason)
    }
}
