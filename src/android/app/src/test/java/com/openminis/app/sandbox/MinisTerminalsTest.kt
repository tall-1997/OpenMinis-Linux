package com.openminis.app.sandbox

import android.content.Context
import com.openminis.app.sandbox.MinisTerminals.TerminalHandle
import com.openminis.app.sandbox.MinisTerminals.TState
import com.openminis.app.tools.TerminalTools
import com.openminis.app.tools.ToolErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-agent-terminal] JVM tests for the agent terminal bookkeeping:
 * per-owner isolation, per-session cap with LRU eviction, the global cap,
 * delta reads, exit handling, and the terminal tool's JSON routing.
 *
 * Real PTY behaviour (Termux emulator, transcript rendering, TUI alternate
 * screens) cannot run on the JVM — it is exercised on-device through the user
 * terminal screen, which shares the exact same TerminalSession machinery. Here
 * a [FakeHandle] stands in for the PTY, which is precisely what the
 * [TerminalHandle] interface exists for.
 */
class MinisTerminalsTest {

    /** Scriptable PTY stand-in: records writes, replays a canned snapshot. */
    private class FakeHandle : TerminalHandle {
        val stateFlow = MutableStateFlow(TState.BOOTING)
        override val state: MutableStateFlow<TState> get() = stateFlow

        var started = false
        var startOwner: String? = null
        var startCols = 0
        var startRows = 0
        var stopCount = 0
        val texts = mutableListOf<String>()
        val rawBytes = mutableListOf<ByteArray>()
        var snapshot = ""
        override var alternateScreenActive: Boolean = false

        override fun start(ownerSessionId: String, cols: Int, rows: Int) {
            started = true
            startOwner = ownerSessionId
            startCols = cols
            startRows = rows
        }

        override fun sendText(text: String) {
            texts.add(text)
        }

        override fun sendRawBytes(bytes: ByteArray) {
            rawBytes.add(bytes)
        }

        override fun snapshotText(): String = snapshot

        override fun stop() {
            stopCount++
            stateFlow.value = TState.EXITED
        }

        fun setRunning() {
            stateFlow.value = TState.RUNNING
        }
    }

    private class FakeFactory : MinisTerminals.TerminalFactory {
        val created = mutableListOf<FakeHandle>()
        override fun create(context: Context?): TerminalHandle =
            FakeHandle().also { created.add(it) }
    }

    private lateinit var factory: FakeFactory

    @Before
    fun setUp() {
        MinisTerminals.internalResetForTest()
        factory = FakeFactory()
        MinisTerminals.factoryOverride = factory
        MinisTerminals.testMainDispatcher = Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        MinisTerminals.internalResetForTest()
    }

    // ── open / list / isolation ───────────────────────────────────────────

    @Test
    fun `open starts the handle and registers with monotonic ids`() = runBlocking {
        val first = MinisTerminals.open("chat-1", null)
        val second = MinisTerminals.open("chat-1", null)
        assertNull(first.error)
        assertEquals("t1", first.terminalId)
        assertEquals("t2", second.terminalId)

        val handle = factory.created[0]
        assertTrue(handle.started)
        assertEquals("chat-1", handle.startOwner)
        assertEquals(80, handle.startCols)
        assertEquals(24, handle.startRows)

        val listed = MinisTerminals.list("chat-1")
        assertEquals(2, listed.size)
        assertEquals(listOf("t1", "t2"), listed.map { it.id })
        assertEquals(TState.BOOTING, listed[0].status)
    }

    @Test
    fun `terminal tables are isolated per owner session`() = runBlocking {
        MinisTerminals.open("chat-1", null)
        MinisTerminals.open("chat-2", null)
        assertEquals(1, MinisTerminals.list("chat-1").size)
        assertEquals(1, MinisTerminals.list("chat-2").size)

        // Sub-agent sessions resolve to the same owner table as their chat.
        MinisTerminals.open("subagent:chat-1:worker", null)
        assertEquals(2, MinisTerminals.list("chat-1").size)
        assertEquals(1, MinisTerminals.list("chat-2").size)

        // Ids are scoped: chat-2 has its own t1 but no t2 — reading chat-1's
        // t2 must not resolve against chat-1's table.
        val cross = MinisTerminals.read("chat-2", "t2", 0)
        assertNotNull(cross.error)
    }

    // ── caps and eviction ─────────────────────────────────────────────────

    @Test
    fun `open evicts the least recently used live terminal at the per-session cap`() = runBlocking {
        repeat(3) { MinisTerminals.open("chat-1", null) }
        val byId = MinisTerminals.internalOwnerTableForTest("chat-1").associateBy { it.id }
        assertEquals(3, byId.size)
        byId.getValue("t1").lastActivityMs.set(100L)
        byId.getValue("t2").lastActivityMs.set(200L)
        byId.getValue("t3").lastActivityMs.set(300L)

        val fourth = MinisTerminals.open("chat-1", null)
        assertEquals("t4", fourth.terminalId)
        assertEquals("t1", fourth.evicted)
        assertTrue(factory.created[0].stopCount > 0)

        assertEquals(listOf("t2", "t3", "t4"), MinisTerminals.list("chat-1").map { it.id })
    }

    @Test
    fun `exited terminals are pruned on open and do not block the cap`() = runBlocking {
        MinisTerminals.open("chat-1", null)
        val handle = factory.created[0]
        handle.setRunning()
        handle.snapshot = "final output\n"
        handle.stateFlow.value = TState.EXITED

        // list keeps the record within retention so status stays observable
        val listed = MinisTerminals.list("chat-1")
        assertEquals(1, listed.size)
        assertEquals(TState.EXITED, listed[0].status)

        // open prunes it eagerly (final output was already delivered by read)
        val next = MinisTerminals.open("chat-1", null)
        assertEquals("t2", next.terminalId)
        assertEquals(listOf("t2"), MinisTerminals.list("chat-1").map { it.id })
    }

    @Test
    fun `global terminal cap rejects without evicting other sessions`() = runBlocking {
        repeat(4) { i -> repeat(3) { MinisTerminals.open("chat-$i", null) } }
        assertEquals(12, factory.created.size)

        val over = MinisTerminals.open("chat-9", null)
        assertNotNull(over.error)
        // The 13th handle was created then released; nobody else was evicted.
        assertTrue(factory.created[12].stopCount > 0)
        for (i in 0..3) assertEquals(3, MinisTerminals.list("chat-$i").size)
    }

    // ── read / delta semantics ────────────────────────────────────────────

    @Test
    fun `read returns the first snapshot then only new deltas`() = runBlocking {
        MinisTerminals.open("chat-1", null)
        val handle = factory.created[0]
        handle.setRunning()

        handle.snapshot = "hello\n"
        assertEquals("hello\n", MinisTerminals.read("chat-1", "t1", 0).output)

        handle.snapshot = "hello\nworld\n"
        assertEquals("world\n", MinisTerminals.read("chat-1", "t1", 0).output)

        // Nothing new: empty output while the terminal stays alive.
        val idle = MinisTerminals.read("chat-1", "t1", 0)
        assertEquals("", idle.output)
        assertTrue(idle.moreExpected)
    }

    @Test
    fun `read reports exit, releases the handle once, and keeps final output`() = runBlocking {
        MinisTerminals.open("chat-1", null)
        val handle = factory.created[0]
        handle.setRunning()
        handle.snapshot = "done\n"
        handle.stateFlow.value = TState.EXITED

        val out = MinisTerminals.read("chat-1", "t1", 0)
        assertEquals(TState.EXITED, out.status)
        assertEquals("done\n", out.output)
        assertFalse(out.moreExpected)
        assertEquals(1, handle.stopCount)

        // A second read still finds the record and does not stop it again.
        assertEquals(1, MinisTerminals.read("chat-1", "t1", 0).let { handle.stopCount })
    }

    @Test
    fun `read resyncs fully when the terminal buffer mode flips`() = runBlocking {
        MinisTerminals.open("chat-1", null)
        val handle = factory.created[0]
        handle.setRunning()
        handle.snapshot = "stream text\n"
        assertEquals("stream text\n", MinisTerminals.read("chat-1", "t1", 0).output)

        // A full-screen TUI app takes over: mode flip forces a full resync of
        // the visible screen instead of diffing across two different buffers.
        handle.alternateScreenActive = true
        handle.snapshot = "ALT SCREEN PAGE"
        val out = MinisTerminals.read("chat-1", "t1", 0)
        assertEquals("ALT SCREEN PAGE", out.output)
    }

    // ── send / signal / close ─────────────────────────────────────────────

    @Test
    fun `send requires a running terminal and appends a carriage return`() = runBlocking {
        MinisTerminals.open("chat-1", null)
        val handle = factory.created[0]

        val booting = MinisTerminals.send("chat-1", "t1", "ls", pressEnter = true)
        assertFalse(booting.ok)

        handle.setRunning()
        val ok = MinisTerminals.send("chat-1", "t1", "ls -la", pressEnter = true)
        assertTrue(ok.ok)
        assertEquals("ls -la", handle.texts.last())
        assertEquals(0x0D.toByte(), handle.rawBytes.last()[0])

        MinisTerminals.send("chat-1", "t1", "partial", pressEnter = false)
        assertEquals("partial", handle.texts.last())
        assertEquals(1, handle.rawBytes.size)
    }

    @Test
    fun `signal maps names to control bytes and requires a running terminal`() = runBlocking {
        assertEquals(0x03, MinisTerminals.signalByte("ctrl_c"))
        assertEquals(0x04, MinisTerminals.signalByte("ctrl_d"))
        assertEquals(0x1A, MinisTerminals.signalByte("ctrl_z"))
        assertNull(MinisTerminals.signalByte("ctrl_alt_delete"))

        MinisTerminals.open("chat-1", null)
        val handle = factory.created[0]
        handle.setRunning()
        assertTrue(MinisTerminals.signal("chat-1", "t1", "ctrl_c").ok)
        assertEquals(0x03.toByte(), handle.rawBytes.last()[0])

        val unknown = MinisTerminals.signal("chat-1", "t1", "sigkill")
        assertFalse(unknown.ok)
    }

    @Test
    fun `close stops the handle and removes the record`() = runBlocking {
        MinisTerminals.open("chat-1", null)
        val handle = factory.created[0]
        handle.setRunning()

        assertTrue(MinisTerminals.close("chat-1", "t1").ok)
        assertTrue(handle.stopCount > 0)
        assertTrue(MinisTerminals.list("chat-1").isEmpty())

        assertFalse(MinisTerminals.close("chat-1", "t1").ok)
        assertNotNull(MinisTerminals.read("chat-1", "t1", 0).error)
    }

    // ── initial command ───────────────────────────────────────────────────

    @Test
    fun `initial command is typed once the shell runs, with a carriage return`() = runBlocking {
        MinisTerminals.open("chat-1", null)
        val handle = factory.created[0]
        handle.setRunning()
        val record = MinisTerminals.internalOwnerTableForTest("chat-1").first()

        MinisTerminals.sendInitialCommand(record, "ls -la", settleMs = 1, bootTimeoutMs = 2_000)
        assertEquals(listOf("ls -la"), handle.texts)
        assertEquals(0x0D.toByte(), handle.rawBytes.single()[0])
    }

    @Test
    fun `initial command gives up when the shell never comes up`() = runBlocking {
        MinisTerminals.open("chat-1", null)
        val handle = factory.created[0]
        val record = MinisTerminals.internalOwnerTableForTest("chat-1").first()

        MinisTerminals.sendInitialCommand(record, "ls", settleMs = 1, bootTimeoutMs = 30)
        assertTrue(handle.texts.isEmpty())
        assertTrue(handle.rawBytes.isEmpty())
    }

    // ── TerminalDelta (pure diff logic) ───────────────────────────────────

    @Test
    fun `delta is append-only between consecutive snapshots`() {
        val (snap1, d1) = TerminalDelta.next("", "hello", MinisTerminals.READ_MAX_CHARS)
        assertEquals("hello", snap1)
        assertEquals("hello", d1)

        val (snap2, d2) = TerminalDelta.next(snap1, "hello world", MinisTerminals.READ_MAX_CHARS)
        assertEquals("hello world", snap2)
        assertEquals(" world", d2)

        val (_, d3) = TerminalDelta.next(snap2, "hello world", MinisTerminals.READ_MAX_CHARS)
        assertEquals("", d3)
    }

    @Test
    fun `delta captures in-place line rewrites as the changed tail`() {
        // The emulator folds CR overwrites, so a progress bar shows up as a
        // rewritten row: only the rewritten tail is new.
        val (_, delta) = TerminalDelta.next("downloading 50%", "downloading 51%", MinisTerminals.READ_MAX_CHARS)
        assertEquals("1%", delta)
    }

    @Test
    fun `delta resyncs when the transcript shrinks or resets`() {
        // clear / scrollback overflow / any head rewrite collapses to the
        // divergence point and emits the changed tail.
        val (_, cleared) = TerminalDelta.next("lots of old scrollback text", "$ ", MinisTerminals.READ_MAX_CHARS)
        assertEquals("$ ", cleared)
    }

    @Test
    fun `delta caps oversized bursts keeping the newest tail`() {
        val capped = TerminalDelta.capTail("x".repeat(100), 10)
        assertTrue(capped.startsWith("[... 90 characters truncated ...]"))
        assertTrue(capped.endsWith("x".repeat(10)))
    }

    // ── TerminalTools routing ─────────────────────────────────────────────

    @Test
    fun `terminal tool routes list, open and read with JSON shapes`() = runBlocking {
        val empty = TerminalTools.route("""{"action":"list"}""", "chat-1", null)
        assertTrue(empty.success)
        assertTrue(JSONObject(empty.output).getInt("count") == 0)

        val opened = TerminalTools.route(
            """{"action":"open","command":"python3","cols":100,"rows":30}""",
            "chat-1", null,
        )
        assertTrue(opened.success)
        val openedJson = JSONObject(opened.output)
        assertEquals("t1", openedJson.getString("terminal_id"))
        assertTrue(openedJson.getString("note").contains("read"))
        assertEquals(100, factory.created[0].startCols)
        assertEquals(30, factory.created[0].startRows)

        val listed = TerminalTools.route("""{"action":"list"}""", "chat-1", null)
        assertEquals(1, JSONObject(listed.output).getInt("count"))

        val booting = TerminalTools.route("""{"action":"read","terminal_id":"t1","wait_ms":0}""", "chat-1", null)
        assertTrue(booting.success)
        assertTrue(JSONObject(booting.output).getString("note").contains("booting"))

        val missing = TerminalTools.route("""{"action":"read","terminal_id":"t9"}""", "chat-1", null)
        assertFalse(missing.success)
        assertEquals(ToolErrorCode.NOT_FOUND, missing.errorCode)

        val badAction = TerminalTools.route("""{"action":"detonate"}""", "chat-1", null)
        assertFalse(badAction.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, badAction.errorCode)
    }

    @Test
    fun `terminal tool open respects the per-session cap via the manager`() = runBlocking {
        repeat(4) { TerminalTools.route("""{"action":"open"}""", "chat-1", null) }
        val listed = MinisTerminals.list("chat-1")
        assertEquals(3, listed.size)
        assertEquals(listOf("t2", "t3", "t4"), listed.map { it.id })
    }
}
