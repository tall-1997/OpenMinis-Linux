package com.openminis.app.mcp.client

import com.openminis.app.mcp.client.McpJsonRpc.McpError
import com.openminis.app.mcp.client.McpRequestBook.AbortResult
import com.openminis.app.mcp.client.McpRequestBook.Outcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-progress-refresh] / [T-mcp-abort-cancelled] contract tests for the
 * request book — the port of pi client.ts's pending-map machinery. Pure JVM
 * with an injected clock and scheduler, so deadline refresh, timeout firing,
 * abort classification and first-reply-wins delivery are all deterministic.
 */
class McpRequestBookTest {

    private class FakeClock {
        var now = 0L
        fun time(): Long = now
        fun advance(ms: Long) {
            now += ms
        }
    }

    private class FakeScheduler {
        class Live(val delayMs: Long, val task: Runnable)

        val live = mutableListOf<Live>()
        /** Every scheduled task, in scheduling order — retired ones included. */
        val tasks = mutableListOf<Runnable>()
        var scheduled = 0

        fun install(): (Long, Runnable) -> (() -> Unit) = { delay, task ->
            scheduled++
            tasks.add(task)
            val entry = Live(delay, task)
            live.add(entry)
            val cancel: () -> Unit = { live.remove(entry) }
            cancel
        }
    }

    private fun book(clock: FakeClock, sched: FakeScheduler): McpRequestBook =
        McpRequestBook(clock::time, sched.install())

    private fun awaitReply(queue: java.util.concurrent.LinkedBlockingQueue<Outcome>): Outcome =
        queue.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: error("no outcome delivered")

    @Test
    fun `add arms one timer with the request timeout`() {
        val clock = FakeClock()
        val sched = FakeScheduler()
        val b = book(clock, sched)
        b.add(id = "1", method = "tools/call", timeoutMs = 30_000L, cancellable = true)
        assertEquals(1, sched.scheduled)
        assertEquals(30_000L, sched.live.single().delayMs)
    }

    @Test
    fun `non positive timeout arms no timer but is still force expirable`() {
        val sched = FakeScheduler()
        val b = book(FakeClock(), sched)
        val pending = b.add(id = "1", method = "ping", timeoutMs = 0L, cancellable = true)
        assertEquals("pi: a non-positive timeout never arms a timer", 0, sched.scheduled)
        // pi armTimeout (client.ts:553-559): with no deadline armed, nothing
        // settles the request on its own — only a reply, an abort, or the
        // caller's belt-and-braces force-expire (the poll-loop timeoutNow).
        // Exercise that force-expire path here.
        assertTrue(b.timeoutNow("1"))
        val failed = awaitReply(pending.replies)
        assertTrue(failed is Outcome.Failed)
        assertEquals(McpError.TIMEOUT, (failed as Outcome.Failed).error.code)
        assertFalse("already settled", b.timeoutNow("1"))
    }

    @Test
    fun `progress notification re-arms the deadline and supersedes the old timer`() {
        val clock = FakeClock()
        val sched = FakeScheduler()
        val b = book(clock, sched)
        val pending = b.add(
            id = "2", method = "tools/call", timeoutMs = 30_000L, cancellable = true,
            progressToken = "2",
        )

        // 20s in, a progress notification arrives: the deadline is re-armed —
        // pi handleProgress (client.ts:536-541) — and only the fresh timer lives.
        clock.advance(20_000L)
        val refreshed = b.progress("2")
        assertTrue(refreshed === pending)
        assertEquals(2, sched.scheduled)
        assertEquals("the stale timer must be disarmed", 1, sched.live.size)

        // The stale firing is a no-op: its arm generation was superseded.
        sched.tasks[0].run()
        assertTrue(pending.replies.isEmpty())

        // Past the refreshed deadline the fresh firing settles with TIMEOUT.
        clock.advance(31_000L)
        sched.tasks[1].run()
        val outcome = awaitReply(pending.replies)
        assertTrue(outcome is Outcome.Failed)
        val error = (outcome as Outcome.Failed).error
        assertEquals(McpError.TIMEOUT, error.code)
        // McpError messages carry the repo's `[$code] ` prefix (McpJsonRpc.kt).
        assertEquals("no reply to 'tools/call' within 30s", error.message?.removePrefix("[TIMEOUT] "))
        // A settled request never changes outcome — even the server's reply.
        assertFalse(b.deliver("2", JSONObject().put("late", true)))
    }

    @Test
    fun `idle request times out when the clock passes the deadline`() {
        val clock = FakeClock()
        val sched = FakeScheduler()
        val b = book(clock, sched)
        val pending = b.add(id = "1", method = "tools/list", timeoutMs = 30_000L, cancellable = true)
        clock.advance(30_001L)
        sched.tasks.single().run()
        val outcome = awaitReply(pending.replies)
        assertEquals(McpError.TIMEOUT, (outcome as Outcome.Failed).error.code)
    }

    @Test
    fun `timer firing before the deadline is ignored`() {
        val clock = FakeClock()
        val sched = FakeScheduler()
        val b = book(clock, sched)
        val pending = b.add(id = "1", method = "tools/list", timeoutMs = 30_000L, cancellable = true)
        clock.advance(5_000L)
        sched.tasks.single().run()
        assertTrue(pending.replies.isEmpty())
    }

    @Test
    fun `first reply wins - a duplicate for the same id is dropped`() {
        val b = book(FakeClock(), FakeScheduler())
        val pending = b.add(id = "7", method = "tools/list", timeoutMs = 30_000L, cancellable = true)
        assertTrue(b.deliver("7", JSONObject().put("first", true)))
        assertFalse(b.deliver("7", JSONObject().put("second", true)))
        assertEquals(1, pending.replies.size)
        val outcome = awaitReply(pending.replies)
        assertTrue((outcome as Outcome.Reply).value.has("first"))
    }

    @Test
    fun `unknown id delivery is refused`() {
        val b = book(FakeClock(), FakeScheduler())
        assertFalse(b.deliver("nope", JSONObject()))
    }

    @Test
    fun `abort settles an unsettled request with ABORTED`() {
        val b = book(FakeClock(), FakeScheduler())
        val pending = b.add(id = "3", method = "tools/call", timeoutMs = 30_000L, cancellable = true)
        val result = b.abort("3", "user stop")
        assertTrue(result is AbortResult.Settled)
        assertTrue(pending.aborted)
        val outcome = awaitReply(pending.replies)
        val error = (outcome as Outcome.Failed).error
        assertEquals(McpError.ABORTED, error.code)
        assertEquals("user stop", error.message?.removePrefix("[ABORTED] "))
        assertFalse(b.deliver("3", JSONObject()))
    }

    @Test
    fun `abort after a reply is a no-op settle - no cancelled notice is warranted`() {
        val b = book(FakeClock(), FakeScheduler())
        val pending = b.add(id = "3", method = "tools/call", timeoutMs = 30_000L, cancellable = true)
        b.deliver("3", JSONObject().put("ok", true))
        val result = b.abort("3", "late stop")
        assertTrue(result is AbortResult.AlreadySettled)
        assertTrue(pending.aborted)
        assertEquals("the reply is untouched", 1, pending.replies.size)
        assertTrue(awaitReply(pending.replies) is Outcome.Reply)
    }

    @Test
    fun `abort of an unknown id is Unknown`() {
        val b = book(FakeClock(), FakeScheduler())
        assertEquals(AbortResult.Unknown, b.abort("gone", "x"))
    }

    @Test
    fun `initialize request records not-cancellable (the spec forbids cancelling it)`() {
        val b = book(FakeClock(), FakeScheduler())
        val pending = b.add(id = "1", method = "initialize", timeoutMs = 60_000L, cancellable = false)
        assertFalse(pending.cancellable)
        // The book still fails it on deadline; the CLIENT decides not to send
        // notifications/cancelled (pi entry.cancellable → notifyServer=false).
        val result = b.abort("1", "Request timed out")
        assertTrue(result is AbortResult.Settled)
    }

    @Test
    fun `failAll fails every unsettled request with the reason`() {
        val b = book(FakeClock(), FakeScheduler())
        val p1 = b.add(id = "1", method = "tools/list", timeoutMs = 30_000L, cancellable = true)
        val p2 = b.add(id = "2", method = "tools/call", timeoutMs = 30_000L, cancellable = true)
        b.failAll("stdio reader exited")
        for (p in listOf(p1, p2)) {
            val outcome = awaitReply(p.replies)
            val error = (outcome as Outcome.Failed).error
            assertEquals(McpError.CONNECTION_ERROR, error.code)
            assertEquals("stdio reader exited", error.message?.removePrefix("[CONNECTION_ERROR] "))
        }
    }

    @Test
    fun `remove unlinks the progress token and disarms the timer`() {
        val clock = FakeClock()
        val sched = FakeScheduler()
        val b = book(clock, sched)
        b.add(id = "2", method = "tools/call", timeoutMs = 30_000L, cancellable = true, progressToken = "2")
        b.remove("2")
        assertNull(b.progress("2"))
        assertFalse(b.deliver("2", JSONObject()))
        assertEquals(0, sched.live.size)
        assertFalse(b.isBusy)
    }

    @Test
    fun `busy flag reflects in-flight requests`() {
        val b = book(FakeClock(), FakeScheduler())
        assertFalse(b.isBusy)
        b.add(id = "1", method = "tools/call", timeoutMs = 30_000L, cancellable = true)
        assertTrue(b.isBusy)
        b.remove("1")
        assertFalse(b.isBusy)
    }

    @Test
    fun `timeoutNow force-expires without a timer`() {
        val b = book(FakeClock(), FakeScheduler())
        val pending = b.add(id = "9", method = "tools/call", timeoutMs = 30_000L, cancellable = true)
        assertTrue(b.timeoutNow("9"))
        val outcome = awaitReply(pending.replies)
        assertEquals(McpError.TIMEOUT, (outcome as Outcome.Failed).error.code)
        assertFalse(b.timeoutNow("9"))
    }
}
