package com.openminis.app.mcp.client

import com.openminis.app.mcp.client.McpJsonRpc.McpError
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [T-mcp-progress-refresh] / [T-mcp-abort-cancelled] / [T-mcp-stdio-frames] /
 * [T-mcp-stdio-close-sequence] end-to-end tests for [McpStdioClient] against a
 * FAKE process target — the pi stdio-test pattern (child_process fakes). No
 * real process, no sleeps on the close path: the close sequence uses tiny
 * injected windows, the deadline machinery uses an injected clock+scheduler.
 */
class McpStdioClientHardeningTest {

    /**
     * Fake [StdioProcessTarget]: stdin is captured verbatim (the wire the
     * client writes), stdout/stderr are pipes the test feeds, and the close
     * sequence events are recorded in order.
     */
    private class FakeTarget : StdioProcessTarget {
        val events = CopyOnWriteArrayList<String>()
        @Volatile var exitOnAwait = 0
        @Volatile var lastTerminatedGraceful: Boolean? = null

        private val stdinBuf = ByteArrayOutputStream()
        private val stdinLock = Any()
        private val stdoutIn = PipedInputStream()
        private val stdoutOut = PipedOutputStream()
        private val stderrIn = PipedInputStream()
        private val stderrOut = PipedOutputStream()
        @Volatile private var exited = false
        private var awaitCalls = 0

        init {
            stdoutIn.connect(stdoutOut)
            stderrIn.connect(stderrOut)
        }

        fun writeToStdout(text: String) {
            stdoutOut.write(text.toByteArray(Charsets.UTF_8))
            stdoutOut.flush()
        }

        fun stdinText(): String = synchronized(stdinLock) { stdinBuf.toString("UTF-8") }

        override val stdin: OutputStream = object : OutputStream() {
            override fun write(b: Int) = synchronized(stdinLock) { stdinBuf.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) = synchronized(stdinLock) { stdinBuf.write(b, off, len) }
            override fun close() {
                events.add("stdin-closed")
            }
        }
        override val stdout: InputStream get() = stdoutIn
        override val stderr: InputStream get() = stderrIn

        override fun pid(): Int? = 4242
        override fun isAlive(): Boolean = !exited

        override fun awaitExit(timeoutMs: Long): Boolean {
            events.add("awaitExit($timeoutMs)")
            awaitCalls++
            return if (exitOnAwait in 1..awaitCalls) {
                exited = true
                events.add("exited")
                true
            } else {
                false
            }
        }

        override fun sigterm() {
            events.add("sigterm")
        }

        override fun sigkill() {
            events.add("sigkill")
        }

        override fun onTerminated(graceful: Boolean) {
            lastTerminatedGraceful = graceful
        }
    }

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
        /** Every scheduled task in order — retired (disarmed) ones included. */
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

    private fun client(
        target: FakeTarget,
        requestTimeoutMs: Long = 30_000L,
        maxMessageBytes: Int = McpStdioFraming.DEFAULT_MAX_MESSAGE_BYTES,
        stdinCloseGraceMs: Long = McpStdioClose.STDIN_CLOSE_GRACE_MS,
        closeTimeoutMs: Long = McpStdioClose.DEFAULT_CLOSE_TIMEOUT_MS,
        schedule: ((Long, Runnable) -> (() -> Unit))? = null,
        nowMs: (() -> Long)? = null,
    ): McpStdioClient = McpStdioClient(
        serverName = "fake",
        spawn = McpStdioClient.SpawnSpec(listOf("fake-server"), emptyMap(), emptyMap()),
        startupTimeoutMs = 10_000L,
        requestTimeoutMs = requestTimeoutMs,
        targetFactory = { target },
        stdinCloseGraceMs = stdinCloseGraceMs,
        closeTimeoutMs = closeTimeoutMs,
        maxMessageBytes = maxMessageBytes,
        schedule = schedule,
        nowMs = nowMs ?: { System.nanoTime() / 1_000_000L },
    )

    private fun awaitStdin(target: FakeTarget, needle: String) {
        for (i in 0 until 500) {
            if (target.stdinText().contains(needle)) return
            Thread.sleep(10)
        }
        fail("stdin never carried '$needle'; got: ${target.stdinText()}")
    }

    private fun awaitCondition(timeoutMs: Long, what: String, probe: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (probe()) return
            Thread.sleep(10)
        }
        fail("condition not met in ${timeoutMs}ms: $what")
    }

    private fun replyFor(id: Int, result: String): String =
        "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}\n"

    /** Completes the initialize handshake (reply id taken off the wire). */
    private fun handshake(target: FakeTarget, client: McpStdioClient) {
        val t = thread { client.ensureInitialized() }
        awaitStdin(target, "\"initialize\"")
        val requestLine = target.stdinText().lineSequence().first { it.contains("\"initialize\"") }
        val id = JSONObject(requestLine).getInt("id")
        target.writeToStdout(
            replyFor(
                id,
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}," +
                    "\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}",
            ),
        )
        t.join(5_000)
        assertFalse("handshake must complete", t.isAlive)
    }

    private fun callLineId(target: FakeTarget): Int {
        awaitStdin(target, "\"tools/call\"")
        val requestLine = target.stdinText().lineSequence().last { it.contains("\"tools/call\"") }
        return JSONObject(requestLine).getInt("id")
    }

    private fun lastCancelLine(target: FakeTarget): JSONObject {
        awaitStdin(target, "notifications/cancelled")
        val line = target.stdinText().lineSequence().last { it.contains("notifications/cancelled") }
        return JSONObject(line)
    }

    // -- [T-mcp-abort-cancelled] -------------------------------------------------

    @Test
    fun `abort fires notifications-cancelled with the request id and fails with ABORTED`() {
        val target = FakeTarget()
        val stdio = client(target)
        handshake(target, stdio)

        var trigger: (() -> Unit)? = null
        var caught: Exception? = null
        val call = thread {
            try {
                stdio.callTool(
                    "slow",
                    JSONObject(),
                    McpRequestOptions(onAbortHandle = { trigger = it }, abortReason = "user stop"),
                )
            } catch (e: Exception) {
                caught = e
            }
        }
        val requestId = callLineId(target)
        assertNotNull("the abort trigger must be handed out once queued", trigger)

        trigger!!.invoke()
        call.join(5_000)
        assertFalse(call.isAlive)

        // The server is told the request id is abandoned, with the caller's reason.
        val cancelled = lastCancelLine(target)
        assertEquals("notifications/cancelled", cancelled.getString("method"))
        assertEquals(requestId, cancelled.getJSONObject("params").getInt("requestId"))
        assertEquals("user stop", cancelled.getJSONObject("params").getString("reason"))

        val err = caught as? McpError ?: throw AssertionError("expected McpError, got $caught")
        assertEquals(McpError.ABORTED, err.code)
    }

    @Test
    fun `abort after the reply already landed sends no stale cancelled notice`() {
        val target = FakeTarget()
        val stdio = client(target)
        handshake(target, stdio)

        var trigger: (() -> Unit)? = null
        var result: JSONObject? = null
        val call = thread {
            try {
                result = stdio.callTool("quick", JSONObject(), McpRequestOptions(onAbortHandle = { trigger = it }))
            } catch (_: Exception) {
            }
        }
        val requestId = callLineId(target)
        // The server answers immediately; the caller aborts LATE.
        target.writeToStdout(replyFor(requestId, "{\"ok\":true}"))
        awaitCondition(5_000, "call completes") { result != null }
        call.join(5_000)

        val wireBeforeAbort = target.stdinText()
        trigger!!.invoke()
        call.join(5_000)
        assertEquals(
            "pi parity: a settled request must not get a cancelled notice",
            wireBeforeAbort,
            target.stdinText(),
        )
        assertTrue(result!!.getBoolean("ok"))
    }

    @Test
    fun `deadline miss sends notifications-cancelled with the pi reason`() {
        val target = FakeTarget()
        // Real scheduler: the poll window itself (250ms) bounds the wait.
        val stdio = client(target, requestTimeoutMs = 250L)
        handshake(target, stdio)

        var caught: Exception? = null
        val call = thread {
            try {
                stdio.callTool("stuck", JSONObject())
            } catch (e: Exception) {
                caught = e
            }
        }
        val requestId = callLineId(target)
        call.join(10_000)
        assertFalse(call.isAlive)

        val err = caught as? McpError ?: throw AssertionError("expected McpError, got $caught")
        assertEquals(McpError.TIMEOUT, err.code)
        val cancelled = lastCancelLine(target)
        assertEquals(requestId, cancelled.getJSONObject("params").getInt("requestId"))
        assertEquals("Request timed out", cancelled.getJSONObject("params").getString("reason"))
    }

    // -- [T-mcp-progress-refresh] -------------------------------------------------

    @Test
    fun `progress notification re-arms the deadline and carries the token`() {
        val clock = FakeClock()
        val sched = FakeScheduler()
        val target = FakeTarget()
        val stdio = client(target, requestTimeoutMs = 2_000L, schedule = sched.install(), nowMs = clock::time)
        handshake(target, stdio)
        // The handshake's initialize request armed its own deadline timer; the
        // tools/call adds exactly ONE arm and each progress re-arm adds ONE
        // more (pi armTimeout: armed at registration, re-armed per progress).
        val armsBeforeCall = sched.scheduled

        val progressValues = CopyOnWriteArrayList<Double>()
        var caught: Exception? = null
        val call = thread {
            try {
                stdio.callTool(
                    "long",
                    JSONObject(),
                    McpRequestOptions(timeoutMs = 2_000L, onProgress = { progressValues.add(it.progress) }),
                )
            } catch (e: Exception) {
                caught = e
            }
        }
        val requestId = callLineId(target)
        // The request advertises its progress token via _meta (pi requestInternal).
        val requestLine = target.stdinText().lineSequence().last { it.contains("\"tools/call\"") }
        assertEquals(
            requestId,
            JSONObject(requestLine).getJSONObject("params").getJSONObject("_meta").getInt("progressToken"),
        )

        // A progress notification arrives 20s (fake clock) into the call.
        clock.advance(20_000L)
        target.writeToStdout(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"," +
                "\"params\":{\"progressToken\":$requestId,\"progress\":50.0,\"total\":100.0}}\n",
        )
        awaitCondition(5_000, "progress callback fires") { progressValues.isNotEmpty() }
        assertEquals(50.0, progressValues.single(), 0.0)

        // The deadline was re-armed: a fresh timer lives, the stale one fired
        // later is superseded by its arm generation (pi armTimeout per notify).
        assertEquals(armsBeforeCall + 2, sched.scheduled)
        assertEquals(1, sched.live.size)
        sched.tasks[armsBeforeCall].run()
        assertTrue("stale firing must not settle", call.isAlive)

        // Past the refreshed deadline (re-armed at 20s for 2s → 22s) the fresh
        // timer settles with TIMEOUT.
        clock.advance(2_100L)
        sched.tasks[armsBeforeCall + 1].run()
        call.join(5_000)
        assertFalse(call.isAlive)
        assertEquals(McpError.TIMEOUT, (caught as McpError).code)
    }

    @Test
    fun `no progress callback - no progress token on the wire`() {
        val target = FakeTarget()
        val stdio = client(target)
        handshake(target, stdio)
        var result: JSONObject? = null
        val call = thread { runCatching { result = stdio.callTool("x", JSONObject()) } }
        val requestId = callLineId(target)
        val requestLine = target.stdinText().lineSequence().last { it.contains("\"tools/call\"") }
        val params = JSONObject(requestLine).getJSONObject("params")
        assertFalse("no _meta.progressToken without onProgress", params.has("_meta"))
        target.writeToStdout(replyFor(requestId, "{\"ok\":true}"))
        awaitCondition(5_000, "call completes") { result != null }
        call.join(5_000)
        assertTrue(result!!.getBoolean("ok"))
    }

    @Test
    fun `progress refresh keeps the call alive past its original poll window`() {
        val clock = FakeClock()
        val sched = FakeScheduler()
        val target = FakeTarget()
        val stdio = client(target, requestTimeoutMs = 200L, schedule = sched.install(), nowMs = clock::time)
        handshake(target, stdio)
        // Count only the call's arms: the handshake's initialize deadline arm
        // precedes them (one at add, one per progress re-arm — pi armTimeout).
        val armsBeforeCall = sched.scheduled

        val progressValues = CopyOnWriteArrayList<Double>()
        var result: JSONObject? = null
        val call = thread {
            try {
                result = stdio.callTool(
                    "long",
                    JSONObject(),
                    McpRequestOptions(timeoutMs = 200L, onProgress = { progressValues.add(it.progress) }),
                )
            } catch (_: Exception) {
            }
        }
        val requestId = callLineId(target)

        // Progress 100ms (fake) in: the deadline is re-armed to 100+200 = 300.
        clock.advance(100L)
        target.writeToStdout(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"," +
                "\"params\":{\"progressToken\":$requestId,\"progress\":1.0}}\n",
        )
        awaitCondition(5_000, "progress callback fires") { progressValues.isNotEmpty() }

        // Outlive the ORIGINAL 200ms window in REAL time without firing any
        // timer and without advancing the fake clock past the refreshed
        // deadline (300): the poll loop must consult the entry's deadline, not
        // the initial timeout. The old fixed-window behavior failed here.
        Thread.sleep(350)
        target.writeToStdout(replyFor(requestId, "{\"late\":true}"))
        awaitCondition(5_000, "refreshed call survives the original window") { result != null }
        call.join(5_000)
        assertTrue("the refreshed deadline must govern, not the initial window", result!!.getBoolean("late"))
        assertEquals("one arm at add, one re-arm at progress", armsBeforeCall + 2, sched.scheduled)
    }

    @Test
    fun `non positive timeout waits without a deadline until the reply`() {
        val target = FakeTarget()
        val stdio = client(target)
        handshake(target, stdio)
        var result: JSONObject? = null
        val call = thread {
            try {
                result = stdio.callTool("x", JSONObject(), McpRequestOptions(timeoutMs = 0L))
            } catch (_: Exception) {
            }
        }
        val requestId = callLineId(target)
        // Well past any accidental immediate-timeout: a non-positive timeout
        // means NO deadline (pi armTimeout), so the call is still waiting.
        Thread.sleep(150)
        assertNull(result)
        target.writeToStdout(replyFor(requestId, "{\"ok\":true}"))
        awaitCondition(5_000, "deadline-less call completes") { result != null }
        call.join(5_000)
        assertTrue(result!!.getBoolean("ok"))
    }

    // -- [T-mcp-stdio-frames] ------------------------------------------------------

    @Test
    fun `oversized frame is dropped and framing continues`() {
        val target = FakeTarget()
        // Cap sits between the fixture's legitimate traffic (the initialize
        // reply is ~130 bytes) and the rogue frame below — a cap under the
        // handshake reply would drop the reply itself and wedge the handshake.
        val stdio = client(target, maxMessageBytes = 150)
        handshake(target, stdio)

        var result: JSONObject? = null
        val call = thread { runCatching { result = stdio.callTool("x", JSONObject()) } }
        val requestId = callLineId(target)

        // A server gone rogue streams an oversized line first — 200 bytes, past
        // the 150-byte cap — and only then the real reply.
        target.writeToStdout(CharArray(200) { 'x' }.concatToString() + "\n")
        target.writeToStdout(replyFor(requestId, "{\"ok\":true}"))
        awaitCondition(5_000, "reply survives the oversized frame") { result != null }
        call.join(5_000)
        assertTrue(result!!.getBoolean("ok"))
    }

    // -- [T-mcp-stdio-close-sequence] ----------------------------------------------

    @Test
    fun `close drains stdin, then SIGTERM, then SIGKILL and reports non-graceful`() {
        val target = FakeTarget()
        val stdio = client(target, stdinCloseGraceMs = 40L, closeTimeoutMs = 80L)
        handshake(target, stdio)

        target.exitOnAwait = 0 // ignores stdin EOF AND SIGTERM
        stdio.close()
        assertEquals(
            listOf("stdin-closed", "awaitExit(40)", "sigterm", "awaitExit(80)", "sigkill"),
            target.events.toList(),
        )
        assertEquals(false, target.lastTerminatedGraceful)
    }

    @Test
    fun `close lets a well behaved server exit on stdin EOF - no signals at all`() {
        val target = FakeTarget()
        val stdio = client(target, stdinCloseGraceMs = 40L, closeTimeoutMs = 80L)
        handshake(target, stdio)

        target.exitOnAwait = 1 // exits within the grace window
        stdio.close()
        assertEquals(listOf("stdin-closed", "awaitExit(40)", "exited"), target.events.toList())
        assertEquals(true, target.lastTerminatedGraceful)
    }

    @Test
    fun `close falls back to SIGTERM when the server misses the grace window`() {
        val target = FakeTarget()
        val stdio = client(target, stdinCloseGraceMs = 40L, closeTimeoutMs = 80L)
        handshake(target, stdio)

        target.exitOnAwait = 2 // exits after SIGTERM
        stdio.close()
        assertEquals(
            listOf("stdin-closed", "awaitExit(40)", "sigterm", "awaitExit(80)", "exited"),
            target.events.toList(),
        )
        assertEquals(true, target.lastTerminatedGraceful)
    }

    @Test
    fun `close is a no-op when nothing was ever started`() {
        val target = FakeTarget()
        val stdio = client(target)
        stdio.close()
        assertEquals(0, target.events.size)
    }
}
