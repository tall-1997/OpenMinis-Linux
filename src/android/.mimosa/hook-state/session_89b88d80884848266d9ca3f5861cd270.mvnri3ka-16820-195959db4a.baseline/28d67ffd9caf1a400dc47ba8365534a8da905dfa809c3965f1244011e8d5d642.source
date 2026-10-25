package com.openminis.app.sandbox

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveExecutionGateTest {
    @Test fun indefiniteWaitSurvivesOldTimeoutAndPreservesResultOwnership() = runTest {
        val gate = AdaptiveExecutionGate()
        var pressure: String? = "low memory"
        val a = async { gate.run(true, 0, { AdaptiveExecutionGate.Limits(1, 1) },
            { pressure }, {}, { testScheduler.currentTime }) { "lane-a" } }
        runCurrent()
        advanceTimeBy(360_000); runCurrent()
        assertTrue(a.isActive)
        pressure = null
        advanceTimeBy(1001); runCurrent()
        assertEquals("lane-a", a.await())
    }

    @Test fun lightToolRunsWhileOnlyHeavySlotIsOccupied() = runTest {
        val hold = CompletableDeferred<Unit>()
        val a = async { SandboxResourceGate.withCommandLock("jadx a.apk",
            limits = { 1 to 1 }) { hold.await(); "lane-a" } }
        runCurrent()
        assertEquals("lane-b", SandboxResourceGate.withCommandLock("cat b.txt",
            limits = { 1 to 1 }) { "lane-b" })
        hold.complete(Unit)
        assertEquals("lane-a", a.await())
    }

    @Test fun shrinkDrainsAndGrowthWakesQueue() = runTest {
        val gate = AdaptiveExecutionGate()
        var cap = 2
        val hold = CompletableDeferred<Unit>()
        suspend fun run(block: suspend () -> Unit) = gate.run(false, 5000,
            { AdaptiveExecutionGate.Limits(cap, 1) }, { null }, {},
            { testScheduler.currentTime }, block)
        val a = async { run { hold.await() } }
        val b = async { run { hold.await() } }
        runCurrent()
        cap = 1
        var started = false
        val c = async { run { started = true } }
        runCurrent()
        assertFalse(started)
        assertTrue(a.isActive && b.isActive)
        cap = 3
        advanceTimeBy(100); runCurrent()
        assertTrue(started)
        hold.complete(Unit)
        a.await(); b.await(); c.await()
    }

    @Test fun cancelledAndTimedOutWaitersNeverExecute() = runTest {
        val gate = AdaptiveExecutionGate()
        val hold = CompletableDeferred<Unit>()
        var started = false
        suspend fun run(ms: Long, block: suspend () -> Unit) = gate.run(false, ms,
            { AdaptiveExecutionGate.Limits(1, 1) }, { null }, {},
            { testScheduler.currentTime }, block)
        val a = async { run(5000) { hold.await() } }
        runCurrent()
        val b = async { run(5000) { started = true } }
        runCurrent(); b.cancelAndJoin()
        val c = async { runCatching { run(100) { started = true } } }
        advanceTimeBy(200); runCurrent()
        assertTrue(c.await().isFailure)
        hold.complete(Unit); a.await()
        assertFalse(started)
        run(100) { started = true }
        assertTrue(started)
    }

    @Test fun pressureBlockedHeavyDoesNotBlockLight() = runTest {
        val gate = AdaptiveExecutionGate()
        var pressure: String? = "memory pressure"
        var heavyRan = false
        val heavy = async { gate.run(true, 5000,
            { AdaptiveExecutionGate.Limits(2, 1) }, { pressure }, {},
            { testScheduler.currentTime }) { heavyRan = true } }
        runCurrent()
        assertEquals("light", gate.run(false, 100,
            { AdaptiveExecutionGate.Limits(2, 1) }, { null }, {}) { "light" })
        assertFalse(heavyRan)
        pressure = null
        advanceTimeBy(100); runCurrent(); heavy.await()
        assertTrue(heavyRan)
    }

    @Test fun heavyLimitIsAlsoBoundedByTotal() = runTest {
        val gate = AdaptiveExecutionGate()
        val hold = CompletableDeferred<Unit>()
        val first = async { gate.run(true, 5000,
            { AdaptiveExecutionGate.Limits(1, 4) }, { null }, {}) { hold.await() } }
        runCurrent()
        var started = false
        val second = async { gate.run(true, 5000,
            { AdaptiveExecutionGate.Limits(1, 4) }, { null }, {}) { started = true } }
        runCurrent(); assertFalse(started)
        hold.complete(Unit); first.await()
        advanceTimeBy(100); runCurrent(); second.await()
        assertTrue(started)
    }
}
