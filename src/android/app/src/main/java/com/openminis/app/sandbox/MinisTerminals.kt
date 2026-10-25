package com.openminis.app.sandbox

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [T-android-agent-terminal] Agent-side interactive PTY terminal manager.
 *
 * Gives agents deepseek-harness-style terminal primitives (open / send / read /
 * signal / close / list) so they can drive TUI and prompt-driven programs that
 * [PersistentShell]'s marker-framed one-shot execution cannot handle: apt
 * confirmation prompts, REPLs, password prompts, long-running servers.
 *
 * Reuse story: each terminal IS the same [TerminalSession] the user terminal
 * screen uses (Termux PTY engine + PRoot guest). That wrapper boots the kernel,
 * builds the per-session bind mounts / env, tracks the process tree in
 * [SandboxWorkload] and tears the whole tree down on stop — all inherited here
 * for free. The wrapper does not expose a byte-level output stream (its
 * [TerminalSession.outputBytes] flow is silent in the Termux path), so reading
 * is done by polling the Termux emulator's transcript buffer and diffing
 * snapshots — see [TerminalDelta] for why that yields a dsh-style "new text
 * stream" instead of TUI redraw frames.
 *
 * Ownership isolation (mirrors PersistentShell / SupervisorRunStore): the table
 * is keyed by [ExecutionCoordinator.ownerSessionId], so a sub-agent and its
 * parent chat share one terminal budget instead of each spawning their own.
 *
 * Cap + eviction policy: max [MAX_TERMINALS_PER_SESSION] LIVE terminals per
 * owner, max [MAX_TERMINALS_TOTAL] process-wide. When a per-owner open exceeds
 * the cap, the least-recently-used LIVE terminal is closed. Rationale: every
 * terminal is a PRoot process tree plus a 2000-row scrollback, so unbounded
 * accumulation is the real leak risk (an agent that forgets to close); the
 * terminal with the oldest last activity is the most likely abandoned one, and
 * an agent that keeps reading its terminals naturally keeps them alive.
 * EXITED terminals hold no process and are pruned eagerly on open (their final
 * output was already delivered by the read that observed the exit).
 */
object MinisTerminals {

    /** Live-terminal cap per owner session (see class KDoc for the policy). */
    const val MAX_TERMINALS_PER_SESSION = 3

    /** Process-wide cap — bounds total PRoot trees regardless of owner count. */
    const val MAX_TERMINALS_TOTAL = 12

    /** Upper bound for a single read wait — read must never hang the tool loop. */
    const val READ_WAIT_CAP_MS = 15_000L

    /** Per-read output cap; the TAIL is kept (newest output is what matters). */
    const val READ_MAX_CHARS = 8_000

    /** Transcript poll interval while inside a read wait window. */
    internal const val POLL_INTERVAL_MS = 150L

    /** How long an initial command waits for the shell to come up. */
    internal const val BOOT_TIMEOUT_MS = 20_000L

    /**
     * Settle delay before the initial command is typed. TerminalSession.start()
     * writes its own prelude + `cd /var/minis && clear` at +300ms after RUNNING;
     * typing earlier would queue the agent's command BEFORE the prelude and get
     * its output wiped by the `clear`.
     */
    internal const val PRELUDE_SETTLE_MS = 400L

    /** EXITED terminals older than this are pruned on list (open prunes all). */
    private const val EXITED_RETENTION_MS = 60_000L

    private const val TAG = "MinisTerminals"

    /** Agent-visible terminal lifecycle. Maps TerminalSession.State 1:1 except IDLE/BOOTING collapse. */
    enum class TState { BOOTING, RUNNING, EXITED }

    /**
     * The piece of a real PTY session the manager touches. Kept as an interface
     * so the bookkeeping (caps, eviction, delta reads, exit cleanup) is JVM
     * testable with fake handles; [TermuxTerminalHandle] is the only
     * production implementation and is never loaded by tests.
     */
    interface TerminalHandle {
        val state: StateFlow<TState>

        /** Boot the PTY. Must be called on a Looper thread (Main in production). */
        fun start(ownerSessionId: String, cols: Int, rows: Int)

        /** UTF-8 text into the PTY (no implicit newline — send CR via [sendRawBytes]). */
        fun sendText(text: String)

        /** Raw bytes into the PTY (control codes, CR). */
        fun sendRawBytes(bytes: ByteArray)

        /**
         * Plain-text snapshot: the main-buffer transcript (scrollback + screen)
         * while a normal program runs, or the visible alternate screen only
         * while a full-screen TUI app owns it. ANSI is already parsed away by
         * the emulator — this is text, not bytes.
         */
        fun snapshotText(): String

        /** True while a full-screen TUI app is active (alternate screen buffer). */
        val alternateScreenActive: Boolean get() = false

        /** Kill the process tree and release the session. Idempotent. */
        fun stop()
    }

    /** Injectable so tests replace the Termux-backed factory wholesale. */
    fun interface TerminalFactory {
        fun create(context: Context?): TerminalHandle
    }

    @Volatile
    internal var factoryOverride: TerminalFactory? = null

    /**
     * Main-thread dispatcher override. Production resolves to Dispatchers.Main
     * (Termux sessions must be constructed on a Looper thread); tests set this
     * to Unconfined so Dispatchers.Main is never touched on the JVM.
     */
    @Volatile
    internal var testMainDispatcher: CoroutineDispatcher? = null

    internal fun resolveMain(explicit: CoroutineDispatcher? = null): CoroutineDispatcher =
        explicit ?: testMainDispatcher ?: Dispatchers.Main

    /** Fire-and-forget waiter for initial commands (outlives the open() call). */
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val table = ConcurrentHashMap<String, ConcurrentHashMap<String, TerminalRecord>>()
    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    private val tableLock = ReentrantLock()

    /** One agent terminal: a handle plus the read-cursor bookkeeping around it. */
    class TerminalRecord internal constructor(
        val id: String,
        val owner: String,
        internal val handle: TerminalHandle,
        val cols: Int,
        val rows: Int,
        val createdAtMs: Long,
    ) {
        /** LRU clock for eviction — bumped on open, send, signal, and read-with-output. */
        val lastActivityMs = AtomicLong(createdAtMs)

        /** Guards [lastSnapshot] / [lastSnapshotAlt] between the poll and the diff. */
        internal val snapshotLock = Any()

        internal var lastSnapshot: String = ""

        /** null = never read; a mode flip (main <-> alt screen) forces a full resync. */
        internal var lastSnapshotAlt: Boolean? = null

        /** Set once so the post-exit resource release runs exactly one time. */
        internal var stoppedAfterExit: Boolean = false

        val status: TState get() = handle.state.value
    }

    data class OpenOutcome(
        val terminalId: String = "",
        val status: TState = TState.BOOTING,
        val evicted: String? = null,
        val error: String? = null,
    )

    data class ReadOutcome(
        val terminalId: String,
        val status: TState?,
        val output: String,
        val moreExpected: Boolean = false,
        val error: String? = null,
    )

    data class TerminalOp(val ok: Boolean, val error: String? = null)

    data class TerminalInfo(
        val id: String,
        val status: TState,
        val createdAtMs: Long,
        val lastActivityMs: Long,
        val cols: Int,
        val rows: Int,
    )

    /**
     * Open a new interactive terminal for [sessionId]'s owner. When [command]
     * is non-blank it is typed into the shell once the PTY is up (see
     * [sendInitialCommand]); the call returns immediately either way.
     */
    suspend fun open(
        sessionId: String,
        context: Context?,
        command: String? = null,
        cols: Int = 80,
        rows: Int = 24,
        main: CoroutineDispatcher? = null,
    ): OpenOutcome {
        val owner = ExecutionCoordinator.ownerSessionId(sessionId)
        val safeCols = cols.coerceIn(20, 400)
        val safeRows = rows.coerceIn(10, 200)
        // Created before the cap check so the Main-thread construction never
        // happens while the table lock is held (no suspension inside withLock).
        val handle = withContext(resolveMain(main)) {
            (factoryOverride ?: TermuxBackedFactory).create(context)
        }
        var victim: TerminalRecord? = null
        var overGlobal = false
        var record: TerminalRecord? = null
        tableLock.withLock {
            pruneExitedLocked(owner, retentionMs = 0L)
            if (totalLiveLocked() >= MAX_TERMINALS_TOTAL) {
                overGlobal = true
                return@withLock
            }
            val ownerTable = table.computeIfAbsent(owner) { ConcurrentHashMap() }
            val live = ownerTable.values.filter { it.status != TState.EXITED }
            if (live.size >= MAX_TERMINALS_PER_SESSION) {
                val lru = live.minByOrNull { it.lastActivityMs.get() }
                if (lru != null) {
                    ownerTable.remove(lru.id)
                    victim = lru
                }
            }
            val n = counters.getOrPut(owner) { AtomicInteger(0) }.incrementAndGet()
            val created = TerminalRecord("t$n", owner, handle, safeCols, safeRows, System.currentTimeMillis())
            ownerTable[created.id] = created
            record = created
        }
        if (overGlobal) {
            runCatching { handle.stop() }
            return OpenOutcome(error = "system-wide terminal limit ($MAX_TERMINALS_TOTAL) reached; close terminals in other sessions first")
        }
        victim?.let {
            runCatching { withContext(resolveMain(main)) { it.handle.stop() } }
            AppLogger.warning(TAG, "terminal ${it.id} evicted (LRU cap $MAX_TERMINALS_PER_SESSION) for $owner")
        }
        val rec = record ?: return OpenOutcome(error = "terminal bookkeeping failed")
        withContext(resolveMain(main)) { handle.start(owner, safeCols, safeRows) }
        if (!command.isNullOrBlank()) {
            commandScope.launch { sendInitialCommand(rec, command.trim(), PRELUDE_SETTLE_MS, BOOT_TIMEOUT_MS) }
        }
        return OpenOutcome(rec.id, rec.status, evicted = victim?.id)
    }

    /**
     * Type the initial command once the shell is up. The 300ms prelude written
     * by TerminalSession.start() must land first, hence the settle delay after
     * RUNNING instead of writing straight into the PTY buffer.
     */
    internal suspend fun sendInitialCommand(
        record: TerminalRecord,
        command: String,
        settleMs: Long,
        bootTimeoutMs: Long,
    ) {
        val reached = withTimeoutOrNull(bootTimeoutMs) {
            record.handle.state.first { it == TState.RUNNING || it == TState.EXITED }
        }
        if (reached != TState.RUNNING) return
        delay(settleMs)
        if (record.status != TState.RUNNING) return
        record.handle.sendText(command)
        record.handle.sendRawBytes(byteArrayOf(0x0D))
        record.lastActivityMs.set(System.currentTimeMillis())
    }

    /**
     * Collect new output since the last read, polling the transcript until
     * [waitMs] elapses or the process exits. Never throws on a quiet terminal:
     * an empty result with [ReadOutcome.moreExpected] tells the agent the
     * program is alive and may still produce output — call read again.
     */
    suspend fun read(
        sessionId: String,
        terminalId: String,
        waitMs: Long,
        main: CoroutineDispatcher? = null,
    ): ReadOutcome {
        val owner = ExecutionCoordinator.ownerSessionId(sessionId)
        val record = table[owner]?.get(terminalId)
            ?: return ReadOutcome(
                terminalId, null, "",
                error = "no such terminal: $terminalId (never opened, closed, or evicted in this session)",
            )
        val mainDisp = resolveMain(main)
        val deadline = System.currentTimeMillis() + waitMs.coerceIn(0L, READ_WAIT_CAP_MS)
        val collected = StringBuilder()
        while (true) {
            val status = record.status
            // Transcript reads share the emulator thread's data — same access
            // pattern the Termux TerminalView uses from the UI thread.
            val snap = withContext(mainDisp) { record.handle.snapshotText() }
            val alt = record.handle.alternateScreenActive
            val delta = synchronized(record.snapshotLock) {
                val modeFlipped = record.lastSnapshotAlt != null && record.lastSnapshotAlt != alt
                val (next, d) = TerminalDelta.next(
                    if (modeFlipped) "" else record.lastSnapshot,
                    snap,
                    READ_MAX_CHARS,
                )
                record.lastSnapshot = next
                record.lastSnapshotAlt = alt
                d
            }
            if (delta.isNotEmpty()) {
                collected.append(delta)
                record.lastActivityMs.set(System.currentTimeMillis())
            }
            if (status == TState.EXITED) {
                if (!record.stoppedAfterExit) {
                    // The process died on its own; release the Termux session
                    // resources now, keep the record briefly for list/read.
                    record.stoppedAfterExit = true
                    runCatching { withContext(mainDisp) { record.handle.stop() } }
                }
                return ReadOutcome(terminalId, TState.EXITED, collected.toString())
            }
            if (System.currentTimeMillis() >= deadline) {
                return ReadOutcome(terminalId, status, collected.toString(), moreExpected = true)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /** Type [text] into the terminal; [pressEnter] appends a carriage return. */
    suspend fun send(
        sessionId: String,
        terminalId: String,
        text: String,
        pressEnter: Boolean,
        main: CoroutineDispatcher? = null,
    ): TerminalOp {
        val record = resolveLive(sessionId, terminalId) ?: return TerminalOp(false, notFound(terminalId))
        if (record.status != TState.RUNNING) {
            return TerminalOp(false, "terminal $terminalId is ${record.status}, not RUNNING; read first or open a new one")
        }
        withContext(resolveMain(main)) {
            if (text.isNotEmpty()) record.handle.sendText(text)
            if (pressEnter) record.handle.sendRawBytes(byteArrayOf(0x0D))
        }
        record.lastActivityMs.set(System.currentTimeMillis())
        return TerminalOp(true)
    }

    /** Control bytes for the signal subset an agent realistically needs. */
    internal fun signalByte(name: String): Int? = when (name.trim().lowercase()) {
        "ctrl_c", "sigint", "c" -> 0x03
        "ctrl_d", "eof", "d" -> 0x04
        "ctrl_z", "sigtstp", "z" -> 0x1A
        else -> null
    }

    /** Send a control signal to the terminal's foreground program. */
    suspend fun signal(
        sessionId: String,
        terminalId: String,
        signalName: String,
        main: CoroutineDispatcher? = null,
    ): TerminalOp {
        val byte = signalByte(signalName)
            ?: return TerminalOp(false, "unknown signal '$signalName'; use ctrl_c, ctrl_d, or ctrl_z")
        val record = resolveLive(sessionId, terminalId) ?: return TerminalOp(false, notFound(terminalId))
        if (record.status != TState.RUNNING) {
            return TerminalOp(false, "terminal $terminalId is ${record.status}, not RUNNING; nothing to signal")
        }
        withContext(resolveMain(main)) { record.handle.sendRawBytes(byteArrayOf(byte.toByte())) }
        record.lastActivityMs.set(System.currentTimeMillis())
        return TerminalOp(true)
    }

    /** Kill the terminal's whole process tree and drop the record. */
    suspend fun close(
        sessionId: String,
        terminalId: String,
        main: CoroutineDispatcher? = null,
    ): TerminalOp {
        val owner = ExecutionCoordinator.ownerSessionId(sessionId)
        val record = table[owner]?.remove(terminalId) ?: return TerminalOp(false, notFound(terminalId))
        runCatching { withContext(resolveMain(main)) { record.handle.stop() } }
        return TerminalOp(true)
    }

    /** Snapshot of this session's terminals (lazy-prunes aged EXITED records). */
    fun list(sessionId: String): List<TerminalInfo> {
        val owner = ExecutionCoordinator.ownerSessionId(sessionId)
        val ownerTable = table[owner] ?: return emptyList()
        tableLock.withLock { pruneExitedLocked(owner, retentionMs = EXITED_RETENTION_MS) }
        return ownerTable.values
            // CHM iteration order is unstable; tie-break same-millisecond
            // records by the monotonic id number so list() is deterministic.
            .sortedWith(compareBy({ idNumber(it.id) }, { it.createdAtMs }))
            .map { TerminalInfo(it.id, it.status, it.createdAtMs, it.lastActivityMs.get(), it.cols, it.rows) }
    }

    private fun idNumber(id: String): Int = id.removePrefix("t").toIntOrNull() ?: Int.MAX_VALUE

    // ── internals ──────────────────────────────────────────────────────────

    private fun resolveLive(sessionId: String, terminalId: String): TerminalRecord? {
        val owner = ExecutionCoordinator.ownerSessionId(sessionId)
        val record = table[owner]?.get(terminalId) ?: return null
        if (record.status == TState.EXITED) return null
        return record
    }

    private fun notFound(terminalId: String): String =
        "no such terminal: $terminalId (never opened, closed, evicted, or exited)"

    /** Must run under [tableLock]. */
    private fun pruneExitedLocked(owner: String, retentionMs: Long) {
        val ownerTable = table[owner] ?: return
        val now = System.currentTimeMillis()
        for (r in ownerTable.values) {
            if (r.status != TState.EXITED) continue
            if (now - r.lastActivityMs.get() < retentionMs) continue
            ownerTable.remove(r.id)
            runCatching { r.handle.stop() }
        }
    }

    /** Must run under [tableLock]. */
    private fun totalLiveLocked(): Int =
        table.values.sumOf { t -> t.values.count { it.status != TState.EXITED } }

    /** Test seam: direct view of one owner's records (caps / eviction tests). */
    internal fun internalOwnerTableForTest(owner: String): Collection<TerminalRecord> =
        table[ExecutionCoordinator.ownerSessionId(owner)]?.values ?: emptyList()

    /** Test seam: wipe all bookkeeping state. */
    internal fun internalResetForTest() {
        tableLock.withLock { table.clear() }
        counters.clear()
        testMainDispatcher = null
        factoryOverride = null
    }
}

/**
 * [T-android-agent-terminal] Snapshot diff that turns transcript polling into a
 * "new text stream": everything before the last read is never re-emitted, TUI
 * redraws of unchanged screens emit nothing, and an in-place line update
 * (progress bar rewriting its row) emits just the changed tail — the Termux
 * emulator applies CR overwrites before we ever see the text, which is exactly
 * why we diff rendered transcript snapshots instead of raw PTY bytes.
 *
 * When the transcript shrinks or rewrites its head (scrollback overflow,
 * `clear`, buffer-mode flip) the longest common prefix collapses to the
 * divergence point and the caller gets the changed tail — effectively a resync.
 */
internal object TerminalDelta {

    /** Returns (newSnapshot, delta) where delta is capped to [maxChars] tail. */
    fun next(previous: String, current: String, maxChars: Int): Pair<String, String> {
        if (current == previous) return previous to ""
        val lcp = commonPrefixLength(previous, current)
        val delta = if (lcp >= current.length) "" else current.substring(lcp)
        return current to capTail(delta, maxChars)
    }

    fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    /** Keep the newest [maxChars] characters; name the elided head. */
    fun capTail(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val dropped = text.length - maxChars
        return "[... $dropped characters truncated ...]\n" + text.substring(dropped)
    }
}

/**
 * Production [MinisTerminals.TerminalFactory]: wraps the same app-level
 * [TerminalSession] the user terminal screen uses. Never class-loaded in JVM
 * tests ([MinisTerminals.factoryOverride] short-circuits before this name is
 * resolved), which is what keeps android.* / termux.* off the test classpath.
 */
private object TermuxBackedFactory : MinisTerminals.TerminalFactory {
    override fun create(context: Context?): MinisTerminals.TerminalHandle =
        TermuxTerminalHandle(TerminalSession(context!!.applicationContext))
}

/**
 * Bridges the app-level [TerminalSession] (Termux PTY + PRoot guest) to the
 * agent-side [MinisTerminals.TerminalHandle] surface. Output is read through
 * the Termux emulator, so ANSI/OSC parsing, CR overwrite folding, and
 * alternate-screen isolation all come from the emulator's screen model:
 *
 * - Main buffer active: the full transcript (scrollback + screen) — the same
 *   "new text stream" a human sees scrolling by.
 * - Alternate buffer active (vim/htop/less): only the visible screen is
 *   snapshotted, and identical redraws diff to nothing — TUI repaint frames
 *   are never exposed, matching the dsh approach. The manager detects the
 *   buffer-mode flip and forces a full resync so the two modes never blend.
 *
 * The handle construction/start must run on Main (Termux needs a Looper
 * thread); [MinisTerminals] enforces that with its main dispatcher.
 */
internal class TermuxTerminalHandle(
    private val session: TerminalSession,
) : MinisTerminals.TerminalHandle {

    private val mapScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    override val state: StateFlow<MinisTerminals.TState> = session.state
        .map {
            when (it) {
                TerminalSession.State.RUNNING -> MinisTerminals.TState.RUNNING
                TerminalSession.State.STOPPED -> MinisTerminals.TState.EXITED
                else -> MinisTerminals.TState.BOOTING
            }
        }
        .stateIn(mapScope, SharingStarted.Eagerly, MinisTerminals.TState.BOOTING)

    override fun start(ownerSessionId: String, cols: Int, rows: Int) {
        session.start(ownerSessionId, cols, rows)
    }

    override fun sendText(text: String) {
        session.sendText(text)
    }

    override fun sendRawBytes(bytes: ByteArray) {
        session.sendRawBytes(bytes)
    }

    override val alternateScreenActive: Boolean
        get() = runCatching {
            session.termuxSession?.emulator?.isAlternateBufferActive ?: false
        }.getOrDefault(false)

    override fun snapshotText(): String {
        return runCatching {
            val emulator = session.termuxSession?.emulator ?: return ""
            val buffer = emulator.screen ?: return ""
            buffer.transcriptTextWithFullLinesJoined ?: ""
        }.getOrDefault("")
    }

    override fun stop() {
        session.stop()
    }
}
