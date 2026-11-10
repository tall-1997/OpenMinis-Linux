package com.openminis.app.provider

import java.io.BufferedReader
import java.io.IOException
import java.io.Reader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class StreamCallLifecycleTest {
    @Test
    fun `cancels socket while job still has an unfinished child`() = runBlocking {
        val parent = Job()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val child = launch(Dispatchers.IO + parent) {
            entered.countDown()
            check(released.await(5, TimeUnit.SECONDS))
        }
        val call = OkHttpClient().newCall(Request.Builder().url("https://example.com").build())
        val hook = call.cancelOnCancellation(parent)
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            parent.cancel()
            assertTrue(call.isCanceled())
            assertFalse(parent.isCompleted)
        } finally {
            released.countDown()
            child.join()
            hook?.dispose()
        }
    }

    @Test
    fun `normal completion does not cancel socket`() {
        val job = Job()
        val call = OkHttpClient().newCall(Request.Builder().url("https://example.com").build())
        val hook = call.cancelOnCancellation(job)
        job.complete()
        assertFalse(call.isCanceled())
        hook?.dispose()
    }

    @Test
    fun `tail deadline unblocks reader and preserves trailing usage`() = runBlocking {
        val released = CountDownLatch(1)
        val source = object : Reader() {
            private val data = "data: terminal\n\ndata: usage\n\n"
            private var offset = 0
            override fun read(dst: CharArray, start: Int, count: Int): Int {
                if (offset < data.length) {
                    val n = minOf(count, data.length - offset)
                    data.toCharArray(dst, start, offset, offset + n)
                    offset += n
                    return n
                }
                check(released.await(3, TimeUnit.SECONDS)) { "tail timer failed to unblock read" }
                throw IOException("deliberate tail deadline")
            }
            override fun close() { released.countDown() }
        }
        val drain = StreamTailDrain(this, { released.countDown() }, graceMs = 40)
        try {
            val events = withContext(Dispatchers.IO) {
                ssePayloads(BufferedReader(source), drain).onEach {
                    if (it == "terminal") drain.terminal()
                }.toList()
            }
            assertEquals(listOf("terminal", "usage"), events)
            assertTrue(drain.isExpired)
        } finally {
            drain.close()
            source.close()
        }
    }

    @Test
    fun `io failure before terminal is never swallowed`() {
        val reader = BufferedReader(object : Reader() {
            override fun read(dst: CharArray, start: Int, count: Int): Int = throw IOException("network failed")
            override fun close() = Unit
        })
        assertThrows(IOException::class.java) { ssePayloads(reader).toList() }
    }

    @Test
    fun `closing drain cancels pending timer`() = runBlocking {
        val fired = CountDownLatch(1)
        val drain = StreamTailDrain(this, { fired.countDown() }, graceMs = 100)
        drain.terminal()
        drain.close()
        withContext(Dispatchers.IO) {
            assertFalse(fired.await(200, TimeUnit.MILLISECONDS))
        }
    }
}
