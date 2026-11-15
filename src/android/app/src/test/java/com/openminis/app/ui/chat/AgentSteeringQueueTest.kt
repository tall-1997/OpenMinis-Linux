package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
