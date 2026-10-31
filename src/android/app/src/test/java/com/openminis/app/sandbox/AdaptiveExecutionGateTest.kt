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
        // [T-aptgate-event] A limits() change is external state: it is noticed
        // on the next 1s pressure-probe window (the old 50ms poll tick is gone
        // by design — releases wake the queue immediately, external changes
        // ride the probe cadence).
        advanceTimeBy(1_000); runCurrent()
        assertTrue(started)
        hold.complete(Unit)
        a.await(); b.await(); c.await()
    }

    @Test fun releaseWakesWaiterImmediatelyWithoutProbeTick() = runTest {
        val gate = AdaptiveExecutionGate()
        val hold = CompletableDeferred<Unit>()
        val first = async { gate.run(false, 5000, { AdaptiveExecutionGate.Limits(1, 1) },
            { null }, {}, { testScheduler.currentTime }) { hold.await() } }
        runCurrent()
        var started = false
        val second = async { gate.run(false, 5000, { AdaptiveExecutionGate.Limits(1, 1) },
            { null }, {}, { testScheduler.currentTime }) { started = true } }
        runCurrent()
        assertFalse(started)
        // [T-aptgate-event] Release notification, not polling: the waiter
        // acquires at the moment of release — long before its 1s probe window.
        hold.complete(Unit)
        first.await()
        runCurrent()
        assertTrue(started)
        second.await()
    }

    @Test fun pressureProbesStayThrottledToOncePerSecond() = runTest {
        val gate = AdaptiveExecutionGate()
        var probes = 0
        val job = async { gate.run(false, 0, { AdaptiveExecutionGate.Limits(1, 1) },
            { probes++; "pressure" }, {}, { testScheduler.currentTime }) { } }
        advanceTimeBy(5_500); runCurrent()
        assertTrue(job.isActive)
        // Entry probe + the ~5 one-second refreshes. The old code polled the
        // same throttle from a 50ms tick (110 wake-ups for this window); the
        // event-driven loop wakes ~6 times total.
        assertTrue("probes=$probes", probes in 1..6)
        job.cancel()
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
