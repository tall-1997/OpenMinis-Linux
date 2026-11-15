package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the memory-first folded write buffer (pi durable session.ts:181-203,
 * persistWithinMs). Covers fold-to-latest, deadline drains, explicit
 * flush/close triggers, immediate memory visibility, persist-failure
 * retention, and post-close behavior. Short deadlines (150 ms) stand in for
 * the 1 s default so no test sleeps through the production window.
 */
class DeferredCommitBufferTest {

    private val deadlineMs = 150L

    @Test
    fun `submit makes the value visible in memory before any persist`() {
        val buffer = DeferredCommitBuffer<String, String>(deadlineMs) { _, _ -> }
        buffer.submit("k", "v1")
        assertEquals("v1", buffer.snapshot("k"))
        assertEquals(1, buffer.pendingCount())
        buffer.close()
    }

    @Test
    fun `repeated submits to one key fold into a single write of the latest value`() {
        val writes = AtomicInteger()
        val flushed = CountDownLatch(1)
        var persistedValue: String? = null
        val buffer = DeferredCommitBuffer<String, String>(deadlineMs) { _, value ->
            synchronized(writes) { persistedValue = value }
            if (writes.incrementAndGet() == 1) flushed.countDown()
        }
        buffer.submit("k", "first")
        buffer.submit("k", "second")
        buffer.submit("k", "third")
        assertTrue("deadline drain did not run", flushed.await(2, TimeUnit.SECONDS))
        buffer.close()
        assertEquals(1, writes.get())
        assertEquals("third", persistedValue)
    }

    @Test
    fun `deadline drains every pending key`() {
        val latch = CountDownLatch(2)
        val persisted = LinkedHashMap<String, String>()
        val buffer = DeferredCommitBuffer<String, String>(deadlineMs) { key, value ->
            synchronized(persisted) {
                persisted[key] = value
                latch.countDown()
            }
        }
        buffer.submit("a", "1")
        buffer.submit("b", "2")
        assertTrue("deadline drain did not run", latch.await(2, TimeUnit.SECONDS))
        buffer.close()
        assertEquals("1", persisted["a"])
        assertEquals("2", persisted["b"])
        assertEquals(0, buffer.pendingCount())
    }

    @Test
    fun `submit after a deadline drain is scheduled again by the next deadline`() {
        // P1-1 regression (adversarial review): the first deadline drain used
        // to leave the scheduled-flush field set forever, so submits arriving
        // after it stayed in memory until an explicit flush/close.
        val firstDrain = CountDownLatch(1)
        val writes = AtomicInteger()
        val persisted = LinkedHashMap<String, String>()
        val buffer = DeferredCommitBuffer<String, String>(deadlineMs) { key, value ->
            synchronized(persisted) {
                persisted[key] = value
                if (writes.incrementAndGet() == 1) firstDrain.countDown()
            }
        }
        buffer.submit("a", "first")
        assertTrue("first deadline drain did not run", firstDrain.await(2, TimeUnit.SECONDS))

        // A fresh submit after the drain must reach storage on its own deadline.
        val secondDrain = CountDownLatch(1)
        buffer.submit("b", "second")
        buffer.submit("b", "second-again")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (secondDrain.count > 0 && System.nanoTime() < deadline) {
            synchronized(persisted) { if (persisted.containsKey("b")) secondDrain.countDown() }
            Thread.sleep(10)
        }
        buffer.close()
        assertTrue("second deadline drain did not run — P1-1 regression", secondDrain.count == 0L)
        assertEquals("second-again", persisted["b"])
        assertEquals(0, buffer.pendingCount())
    }

    @Test
    fun `flush writes one key immediately and leaves the other pending`() {
        val persisted = LinkedHashMap<String, String>()
        val buffer = DeferredCommitBuffer<String, String>(deadlineMs) { key, value ->
            synchronized(persisted) { persisted[key] = value }
        }
        buffer.submit("a", "1")
        buffer.submit("b", "2")
        assertTrue(buffer.flush("a"))
        assertEquals("1", persisted["a"])
        assertFalse(persisted.containsKey("b"))
        assertEquals(1, buffer.pendingCount())
        buffer.close()
        assertEquals("2", persisted["b"])
    }

    @Test
    fun `flush of an absent key is a no-op`() {
        val persisted = LinkedHashMap<String, String>()
        val buffer = DeferredCommitBuffer<String, String>(deadlineMs) { key, value ->
            synchronized(persisted) { persisted[key] = value }
        }
        assertFalse(buffer.flush("missing"))
        buffer.close()
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `close flushes remaining keys and rejects further submits`() {
        val persisted = LinkedHashMap<String, String>()
        val buffer = DeferredCommitBuffer<String, String>(deadlineMs) { key, value ->
            synchronized(persisted) { persisted[key] = value }
        }
        buffer.submit("k", "last")
        buffer.close()
        assertEquals("last", persisted["k"])
        assertEquals(0, buffer.pendingCount())
        try {
            buffer.submit("k2", "too late")
            throw AssertionError("submit after close must throw")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `close is idempotent`() {
        val buffer = DeferredCommitBuffer<String, String>(deadlineMs) { _, _ -> }
        buffer.close()
        buffer.close()
    }

    @Test
    fun `persist failure keeps the value and reports it via onError`() {
        val errors = AtomicInteger()
        val attempts = AtomicInteger()
        val buffer = DeferredCommitBuffer<String, String>(deadlineMs, persist = { _, _ ->
            // Fail only the first attempt; close's final drain would otherwise
            // report a second error and keep the value forever.
            if (attempts.incrementAndGet() == 1) throw IllegalStateException("storage down")
        }, onError = { _, _, _ -> errors.incrementAndGet() })
        buffer.submit("k", "kept")
        assertEquals("kept", buffer.snapshot("k"))
        // Wait out the deadline drain: the write fails, the value stays pending.
        Thread.sleep(deadlineMs * 4)
        assertEquals(1, errors.get())
        assertEquals("kept", buffer.snapshot("k"))
        buffer.close() // retry (attempt 2) succeeds
        assertEquals(0, buffer.pendingCount())
    }

    @Test
    fun `a failed value retries on the next explicit flush`() {
        val attempts = AtomicInteger()
        val persisted = LinkedHashMap<String, String>()
        val buffer = DeferredCommitBuffer<String, String>(deadlineMs, persist = { key, value ->
            if (attempts.incrementAndGet() == 1) throw IllegalStateException("storage down")
            synchronized(persisted) { persisted[key] = value }
        })
        buffer.submit("k", "value")
        buffer.flush("k") // attempt 1 fails, value stays pending
        assertEquals("value", buffer.snapshot("k"))
        assertTrue(buffer.flush("k")) // attempt 2 succeeds
        assertEquals("value", persisted["k"])
        assertEquals(0, buffer.pendingCount())
        buffer.close()
    }

    @Test
    fun `default deadline matches pi persistWithinMs`() {
        assertEquals(1_000L, DeferredCommitBuffer.DEFAULT_DEADLINE_MS)
    }

    @Test
    fun `snapshot of an unknown key is null`() {
        val buffer = DeferredCommitBuffer<String, String>(deadlineMs) { _, _ -> }
        assertNull(buffer.snapshot("never-submitted"))
        buffer.close()
    }
}
