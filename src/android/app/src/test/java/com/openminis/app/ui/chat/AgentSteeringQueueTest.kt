package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [T-agent-steering] Pins for the steering queue component
 * (pi agent/src/types.ts:47-55 QueueMode + agent.ts PendingMessageQueue port).
 * The ChatViewModel-facing entry points ([steerPrompt] streaming gate,
 * [drainSteeringIntoRun] commit path) reuse the queue primitives pinned here;
 * the streaming gate itself is a three-line `_isStreaming` check documented in
 * the component KDoc.
 */
class AgentSteeringQueueTest {

    @Test
    fun `one-at-a-time drains the oldest message per call`() {
        val queue = AgentSteeringQueue() // default ONE_AT_A_TIME
        assertTrue(queue.offer("first"))
        assertTrue(queue.offer("second"))
        assertTrue(queue.offer("third"))
        assertEquals(3, queue.size)
        assertEquals("first", queue.drainOne())
        assertEquals("second", queue.drainOne())
        assertEquals("third", queue.drainOne())
        assertNull(queue.drainOne())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `drainAll takes everything in offer order`() {
        val queue = AgentSteeringQueue()
        queue.offer("a")
        queue.offer("b")
        queue.offer("c")
        assertEquals(listOf("a", "b", "c"), queue.drainAll())
        assertTrue(queue.drainAll().isEmpty())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `offers from many threads never lose a message`() {
        val queue = AgentSteeringQueue()
        val threads = 8
        val perThread = 50
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> queue.offer("t$t-m$i") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("workers finished in time", done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(threads * perThread, queue.size)
        val drained = queue.drainAll()
        assertEquals(threads * perThread, drained.size)
        assertEquals(threads * perThread, drained.toSet().size) // no loss, no duplication
    }

    @Test
    fun `empty queue drains to nothing`() {
        val queue = AgentSteeringQueue()
        assertTrue(queue.isEmpty)
        assertNull(queue.drainOne())
        assertTrue(queue.drainAll().isEmpty())
        assertTrue(queue.drainPerMode().isEmpty())
    }

    @Test
    fun `blank offers are rejected`() {
        val queue = AgentSteeringQueue()
        assertFalse(queue.offer(""))
        assertFalse(queue.offer("   "))
        assertFalse(queue.offer("\n\t"))
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `mode switch changes the per-mode drain volume`() {
        val queue = AgentSteeringQueue(QueueMode.ONE_AT_A_TIME)
        queue.offer("a")
        queue.offer("b")
        queue.offer("c")
        // ONE_AT_A_TIME: one message per boundary.
        assertEquals(listOf("a"), queue.drainPerMode())
        assertEquals(listOf("b"), queue.drainPerMode())
        // Switching to ALL mid-run (pi steeringMode setter) drains everything next.
        queue.mode = QueueMode.ALL
        assertEquals(listOf("c"), queue.drainPerMode())
        queue.offer("d")
        queue.offer("e")
        assertEquals(listOf("d", "e"), queue.drainPerMode())
        // And explicit ops are mode-independent.
        queue.offer("f")
        assertEquals("f", queue.drainOne())
        queue.offer("g")
        queue.offer("h")
        assertEquals(listOf("g", "h"), queue.drainAll())
    }

    @Test
    fun `clear empties the queue`() {
        val queue = AgentSteeringQueue()
        queue.offer("x")
        queue.clear()
        assertTrue(queue.isEmpty)
        assertNull(queue.drainOne())
    }

    // -- P1-7: the session stamp must re-stamp, never clear --

    @Test
    fun `stamp change (draft to persisted promotion) keeps queued steering messages`() {
        // The store used to clear the queue whenever the session id moved.
        // A live VM's only move is the draft→persisted promotion (the store
        // keys one VM per session and aliases the draft), so an offer made
        // in the sync `_isStreaming` window — before ensureSession promotes —
        // was destroyed between the composer and the request build.
        val store = SteeringSessionStore()
        val owner = Any()
        val draft = store.stateFor(owner, "__new__draft")
        assertTrue(draft.queue.offer("mid-run note"))
        // The promotion: same owner, different id.
        val promoted = store.stateFor(owner, "persisted-real")
        assertSame("a stamp change is a re-stamp, not a new state", draft, promoted)
        assertEquals("persisted-real", promoted.sessionId)
        assertEquals(1, promoted.queue.size)
        assertEquals("mid-run note", promoted.queue.drainOne())
    }

    @Test
    fun `a fresh owner gets a fresh queue (cross-conversation isolation is structural)`() {
        // A new conversation gets a new VM (new owner), so per-session
        // isolation does not depend on the stamp rule clearing anything.
        val store = SteeringSessionStore()
        store.stateFor(Any(), "s1").queue.offer("for a")
        val other = store.stateFor(Any(), "s1")
        assertEquals(0, other.queue.size)
    }

    // -- [T-agent-steering-exit-fallback] (P2-3) the natural-exit re-route --

    @Test
    fun `fallback flush hands every entry to the sink and clears the queue`() {
        // The VM-side extension routes the sink through enqueuePrompt
        // (skipDedupe=true) with a fake channel counting exactly what a real
        // post-run dispatch would receive.
        val queue = AgentSteeringQueue()
        queue.offer("a")
        queue.offer("b")
        queue.offer("c")
        val routed = mutableListOf<String>()
        assertTrue(queue.drainToFallback { routed += it })
        assertEquals(listOf("a", "b", "c"), routed)
        assertTrue(queue.isEmpty)
        // Empty queue: nothing moved, nothing routed.
        assertFalse(queue.drainToFallback { routed += it })
    }

    @Test
    fun `fallback flush ignores the mode and is offer-ordered`() {
        // ONE_AT_A_TIME still drains EVERYTHING at the exit: the ending run will
        // never reach another boundary, so per-mode draining would strand the
        // rest behind a boundary that never comes.
        val queue = AgentSteeringQueue(QueueMode.ONE_AT_A_TIME)
        queue.offer("x")
        queue.offer("y")
        queue.mode = QueueMode.ALL
        queue.offer("z")
        val routed = mutableListOf<String>()
        assertTrue(queue.drainToFallback { routed += it })
        assertEquals(listOf("x", "y", "z"), routed)
        assertTrue(queue.isEmpty)
    }

    // -- [T-agent-steering-store-atomic] (P2-5) stateFor first-access race --

    @Test
    fun `concurrent first accesses produce exactly one SteerState`() {
        // headless entries may call stateFor off-Main; the store must resolve a
        // racing first access to ONE state (hand-rolled get/put-if-null under
        // the lock, NOT getOrPut).
        val store = SteeringSessionStore()
        val owner = Any()
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        val seen = java.util.concurrent.ConcurrentLinkedQueue<SteerState>()
        repeat(threads) {
            pool.execute {
                start.await()
                seen.add(store.stateFor(owner, "s"))
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("workers finished in time", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(threads, seen.size)
        assertEquals(1, seen.map { System.identityHashCode(it) }.distinct().count())
        // The single state carries the queue and the re-stamped session id.
        val state = seen.first()
        assertEquals("s", state.sessionId)
        assertTrue(state.queue.isEmpty)
    }
}
