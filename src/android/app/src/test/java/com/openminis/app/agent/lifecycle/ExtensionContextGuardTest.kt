package com.openminis.app.agent.lifecycle

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ext-ctx-guard] pi coding-agent stale-ctx semantics (extensions/
 * loader.ts:164-244), pinned on the ported primitive:
 *
 *  1. after [ExtensionContextGuard.GuardedContext.invalidate], every guarded
 *     action throws the self-explanatory [IllegalStateException] — never a
 *     deep crash on a dead reference;
 *  2. invalidation auto-unsubscribes tracked listener callbacks (the
 *     demonstration consumer is LifecycleHooks.registerSessionListener — the
 *     hook dispatcher owns its listener list for the whole process lifetime);
 *  3. invalidation is idempotent (first message wins) and unsubscribe handles
 *     are idempotent, so manual-unsubscribe-then-invalidate never double-runs;
 *  4. error isolation of the dispatcher's fan-out: a throwing listener cannot
 *     break delivery to the others or the caller, also through the guarded
 *     registration path.
 *
 * Pure JVM. LifecycleHooks is a process-wide singleton, so every test cleans
 * up via guard invalidation / explicit unregistration in [tearDown].
 */
class ExtensionContextGuardTest {

    private val guards = mutableListOf<ExtensionContextGuard.GuardedContext>()
    private val rawListeners = mutableListOf<LifecycleHooks.LifecycleListener>()

    private fun newGuard(): ExtensionContextGuard.GuardedContext =
        ExtensionContextGuard.create().also { guards += it }

    private fun registerRaw(listener: LifecycleHooks.LifecycleListener) {
        LifecycleHooks.registerListener(listener)
        rawListeners += listener
    }

    @After
    fun tearDown() {
        guards.forEach { it.invalidate() } // auto-unsubscribes guarded registrations
        rawListeners.forEach { LifecycleHooks.unregisterListener(it) }
        guards.clear()
        rawListeners.clear()
    }

    // -- 1. Stale ctx fails with the self-explanatory error --

    @Test
    fun `checkValid passes while active`() {
        val guard = newGuard()
        assertFalse(guard.isInvalidated)
        guard.checkValid() // must not throw
        assertEquals("value", guard.use { "value" })
    }

    @Test
    fun `invalidate makes every guarded action throw the self-explanatory error`() {
        val guard = newGuard()
        guard.invalidate()

        assertTrue(guard.isInvalidated)
        val thrown = assertThrows(IllegalStateException::class.java) { guard.checkValid() }
        assertEquals(
            "context invalidated — capture a fresh reference from the current session",
            thrown.message,
        )
        // The guarded sugar fails the same way — every action path is covered.
        assertThrows(IllegalStateException::class.java) { guard.use { "never runs" } }
    }

    @Test
    fun `a context-specific invalidate message overrides the default`() {
        val guard = newGuard()
        guard.invalidate("session 42 was deleted")
        val thrown = assertThrows(IllegalStateException::class.java) { guard.checkValid() }
        assertEquals("session 42 was deleted", thrown.message)
    }

    // -- 2. Invalidation auto-unsubscribes --

    @Test
    fun `invalidate runs the tracked unsubscribe exactly once`() {
        val guard = newGuard()
        var unsubscribeRuns = 0
        val handle = guard.trackUnsubscribe { unsubscribeRuns++ }

        handle() // manual unsubscribe first
        assertEquals(1, unsubscribeRuns)
        guard.invalidate() // then invalidation — must not double-run
        assertEquals(1, unsubscribeRuns)
    }

    @Test
    fun `invalidate without a prior manual unsubscribe runs it on invalidation`() {
        val guard = newGuard()
        var unsubscribeRuns = 0
        guard.trackUnsubscribe { unsubscribeRuns++ }
        assertEquals(0, unsubscribeRuns)

        guard.invalidate()
        assertEquals(1, unsubscribeRuns)
    }

    @Test
    fun `invalidate is idempotent and the first message wins`() {
        val guard = newGuard()
        var unsubscribeRuns = 0
        guard.trackUnsubscribe { unsubscribeRuns++ }

        guard.invalidate("first")
        guard.invalidate("second") // no-op
        assertEquals(1, unsubscribeRuns)
        val thrown = assertThrows(IllegalStateException::class.java) { guard.checkValid() }
        assertEquals("first", thrown.message)
    }

    @Test
    fun `guarded session listener stops receiving hook events after invalidation`() {
        val recorder = RecordingListener()
        val guard = LifecycleHooks.registerSessionListener(recorder)
        guards += guard // tearDown invalidates for cleanup

        LifecycleHooks.fire(LifecycleHooks.LifecycleEvent.SESSION_START, "s1", "before")
        assertEquals(1, recorder.events.size)

        guard.invalidate()
        LifecycleHooks.fire(LifecycleHooks.LifecycleEvent.SESSION_START, "s1", "after")

        // The auto-unsubscribe removed it from the dispatcher: no second delivery.
        assertEquals(1, recorder.events.size)
    }

    // -- 3. Dispatcher fan-out stays error-isolated through the guarded path --

    @Test
    fun `a throwing session listener cannot break delivery to the others or the caller`() {
        val bad = object : LifecycleHooks.LifecycleListener {
            override fun onEvent(
                event: LifecycleHooks.LifecycleEvent,
                sessionId: String,
                detail: String,
            ) {
                throw IllegalStateException("listener bug")
            }
        }
        val good = RecordingListener()
        val badGuard = LifecycleHooks.registerSessionListener(bad)
        val goodGuard = LifecycleHooks.registerSessionListener(good)
        guards += badGuard
        guards += goodGuard

        // Must not throw: agent turns call fire() inline.
        LifecycleHooks.fire(LifecycleHooks.LifecycleEvent.RESPONSE, "s2", "tail")

        assertEquals(1, good.events.size)
        assertEquals(
            HookDelivery(LifecycleHooks.LifecycleEvent.RESPONSE, "s2", "tail"),
            good.events.single(),
        )
    }

    // -- 4. The guard itself stays inert during normal delivery --

    @Test
    fun `invalidation does not touch other guards sharing the dispatcher`() {
        val kept = RecordingListener()
        val dropped = RecordingListener()
        val keptGuard = LifecycleHooks.registerSessionListener(kept)
        val droppedGuard = LifecycleHooks.registerSessionListener(dropped)
        guards += keptGuard
        guards += droppedGuard

        droppedGuard.invalidate()
        LifecycleHooks.fire(LifecycleHooks.LifecycleEvent.SESSION_END, "s3", "closed")

        assertEquals(0, dropped.events.size)
        assertEquals(1, kept.events.size)
    }

    // -- 5. Race closure: registration landing during the one-shot drain --

    @Test
    fun `trackUnsubscribe after invalidate runs immediately and tracks nothing`() {
        // Pinned behavior (P2-8): a registration made AFTER invalidation
        // unsubscribes immediately instead of queueing into a dead queue.
        val guard = newGuard()
        guard.invalidate()
        var runs = 0
        val handle = guard.trackUnsubscribe { runs++ }
        assertEquals(1, runs)
        // The returned handle is inert: the lambda already ran.
        handle()
        assertEquals(1, runs)
    }

    @Test
    fun `racing invalidate and trackUnsubscribe run the unsubscribe exactly once`() {
        // review P2-3 stress probe: 1000 fresh guards, one thread invalidating
        // and one thread registering concurrently from behind a barrier. Every
        // registration must end up UNSUBSCRIBED exactly once — either by the
        // drain or by the post-add re-read (whichever wins the race). A lost
        // registration — the check-then-add window staying open — would leave
        // the lambda pinned in the dead queue and the count would under-fire.
        val runs = java.util.concurrent.atomic.AtomicInteger(0)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val barrier = java.util.concurrent.CyclicBarrier(2)
            repeat(1000) {
                val guard = ExtensionContextGuard.create()
                guards += guard
                val invalidator = pool.submit {
                    barrier.await()
                    guard.invalidate()
                }
                val registrar = pool.submit {
                    barrier.await()
                    guard.trackUnsubscribe { runs.incrementAndGet() }
                }
                invalidator.get()
                registrar.get()
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals("every raced registration must unsubscribe exactly once", 1000, runs.get())
    }
}

/** One delivered hook event, compared by value. */
private data class HookDelivery(
    val event: LifecycleHooks.LifecycleEvent,
    val sessionId: String,
    val detail: String,
)

/** Records every delivery so assertions can compare the full payload. */
private class RecordingListener : LifecycleHooks.LifecycleListener {
    val events = mutableListOf<HookDelivery>()

    override fun onEvent(
        event: LifecycleHooks.LifecycleEvent,
        sessionId: String,
        detail: String,
    ) {
        events += HookDelivery(event, sessionId, detail)
    }
}
