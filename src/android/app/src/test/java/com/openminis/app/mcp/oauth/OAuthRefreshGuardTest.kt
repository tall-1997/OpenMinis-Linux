package com.openminis.app.mcp.oauth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM tests for the pi resolve.ts refresh invariants ([OAuthRefreshGuard]):
 * single-flight under the lock, lock-internal re-check, an un-cancellable
 * refresh segment (cancel only skips the WAIT), bounded lock wait, and no lock
 * leak on failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OAuthRefreshGuardTest {

    @Test
    fun `concurrent callers refresh exactly once`() = runTest {
        val guard = OAuthRefreshGuard(lockWaitTimeoutMs = 10_000, refreshTimeoutMs = 10_000)
        val refreshCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val results = (1..10).map { idx ->
            async {
                guard.refreshOnce(
                    stillNeeded = { refreshCount.get() == 0 },
                    refresh = {
                        refreshCount.incrementAndGet()
                        gate.await()
                        "token-$idx"
                    },
                )
            }
        }
        // Let the winner enter the refresh, then release it: every queued
        // caller must observe the re-check and skip its own round-trip.
        while (refreshCount.get() == 0) yield()
        gate.complete(Unit)

        val values = results.map { it.await() }
        assertEquals("one network refresh despite ten callers", 1, refreshCount.get())
        assertEquals(
            "exactly one caller carries the fresh value, the rest adopt the store",
            1,
            values.count { it != null },
        )
    }

    @Test
    fun `re-check under the lock skips a refresh another caller already did`() = runTest {
        val guard = OAuthRefreshGuard(lockWaitTimeoutMs = 5_000, refreshTimeoutMs = 5_000)
        val state = AtomicInteger(0) // 0 = expired, 1 = fresh (the "store")
        var networkCalls = 0

        val first = guard.refreshOnce(
            stillNeeded = { state.get() == 0 },
            refresh = { networkCalls++; state.set(1); "fresh-token" },
        )
        assertEquals("fresh-token", first)

        val second = guard.refreshOnce(
            stillNeeded = { state.get() == 0 },
            refresh = { networkCalls++; state.set(1); "should-not-happen" },
        )
        assertNull("the second caller must adopt the stored token", second)
        assertEquals(1, networkCalls)
    }

    @Test
    fun `cancelling the caller does not abort a started refresh`() = runTest {
        val guard = OAuthRefreshGuard(lockWaitTimeoutMs = 5_000, refreshTimeoutMs = 10_000)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var persisted = false
        val result = CompletableDeferred<String?>()

        val job = launch {
            val value = guard.refreshOnce(
                stillNeeded = { true },
                refresh = {
                    started.complete(Unit)
                    release.await()
                    // "persistence": must run even though the caller is gone.
                    persisted = true
                    "rotated-token"
                },
            )
            result.complete(value)
        }

        started.await()
        // pi invariant: cancellation only skips the WAIT; a started refresh and
        // its persistence must complete, or the rotated credential is lost.
        job.cancel()
        release.complete(Unit)

        assertEquals("rotated-token", result.await())
        assertTrue("persistence ran despite caller cancellation", persisted)
    }

    @Test
    fun `lock wait times out and falls back without running the refresh`() = runTest {
        val guard = OAuthRefreshGuard(lockWaitTimeoutMs = 250, refreshTimeoutMs = 10_000)
        val release = CompletableDeferred<Unit>()
        var holderCalls = 0
        var loserCalls = 0

        val holder = async {
            guard.refreshOnce(
                stillNeeded = { true },
                refresh = { holderCalls++; release.await(); "held" },
            )
        }
        while (holderCalls == 0) yield() // holder owns the lock now

        val start = testScheduler.currentTime
        val loser = guard.refreshOnce(
            stillNeeded = { true },
            refresh = { loserCalls++; "must-not-run" },
        )
        assertEquals("the wait is bounded by the configured budget", 250L, testScheduler.currentTime - start)
        assertNull(loser)
        assertEquals("the losing caller must not spend a refresh", 0, loserCalls)

        release.complete(Unit)
        assertEquals("held", holder.await())
    }

    @Test
    fun `a failing refresh releases the lock for the next caller`() = runTest {
        val guard = OAuthRefreshGuard(lockWaitTimeoutMs = 5_000, refreshTimeoutMs = 5_000)
        try {
            guard.refreshOnce(stillNeeded = { true }, refresh = { throw IllegalStateException("boom") })
        } catch (_: IllegalStateException) {
            // expected — pi wraps and rethrows; the MCP bridge catches inside.
        }
        val next = guard.refreshOnce(stillNeeded = { true }, refresh = { "recovered" })
        assertEquals("recovered", next)
    }

    @Test
    fun `shared guards are per credential key`() {
        val a1 = OAuthRefreshGuard.forKey("mcp-oauth:server-a")
        val a2 = OAuthRefreshGuard.forKey("mcp-oauth:server-a")
        val b = OAuthRefreshGuard.forKey("mcp-oauth:server-b")
        assertNotSame(a1, b)
        assertTrue("same credential shares one lock", a1 === a2)
    }
}
