package com.openminis.app.mcp.client

import com.openminis.app.logging.AppLogger
import com.openminis.app.mcp.client.McpJsonRpc.McpError
import com.openminis.app.mcp.client.McpRequestBook.Outcome
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [T-mcp-native] STDIO transport: an MCP server process running inside the
 * PRoot guest, spoken to over line-delimited JSON-RPC on its stdin/stdout.
 * Mirrors the in-guest daemon's `MCPStdioServer` (daemon.py): direct exec of
 * `command + args` with the config `env` overlaid, `initialize` bounded by
 * the per-server startup timeout, stderr drained into a ring buffer for
 * diagnostics, one in-flight request at a time.
 *
 * The process is spawned through a caller-supplied proot command prefix
 * ([SpawnSpec]) so this class stays JVM-testable — everything below the
 * [StdioProcessTarget] seam is process-free and the tests drive a fake target
 * (pi tests its StdioTransport against child_process fakes the same way).
 *
 * Hardening ported from pi `mcp/src/client.ts` + `transports/stdio.ts`:
 *  - [T-mcp-stdio-frames] 16 MiB single-message cap on stdout frames (pi
 *    transport.ts `DEFAULT_MAX_MESSAGE_BYTES`), enforced by
 *    [McpJsonRpcLineReader] — a server that streams an unterminated line can
 *    no longer grow the reader buffer unbounded;
 *  - [T-mcp-progress-refresh] every request gets a deadline
 *    ([McpRequestBook.DEFAULT_REQUEST_TIMEOUT_MS] unless overridden per
 *    request via [McpRequestOptions.timeoutMs]) and a `notifications/progress`
 *    for the request's progress token re-arms it (pi client.ts:536-547), so
 *    chatty long calls never time out while idle ones still do;
 *  - [T-mcp-abort-cancelled] the per-request abort trigger settles the call
 *    with ABORTED and sends the server `notifications/cancelled`
 *    (`initialize` excepted — the spec forbids cancelling it);
 *  - [T-mcp-stdio-close-sequence] close = stdin closed → grace window (500ms,
 *    capped by the close timeout) → SIGTERM → close-timeout window (2s) →
 *    SIGKILL, then the tree classification hook (pi stdio.ts:10-58);
 *  - [T-mcp-list-all] `tools/list` follows `nextCursor` through all pages,
 *    bounded at 1000 pages with duplicate-cursor detection (pi client.ts
 *    listAll).
 */
class McpStdioClient(
    override val serverName: String,
    private val spawn: SpawnSpec,
    private val startupTimeoutMs: Long = 60_000L,
    /**
     * Default per-request deadline. pi client.ts defaults to 30s; the bridge
     * passes the CLI-parity 300s for slow tool executions — this default is
     * the pi-parity value for callers without an explicit override.
     */
    private val requestTimeoutMs: Long = McpRequestBook.DEFAULT_REQUEST_TIMEOUT_MS,
    private val hooks: ProcessHooks? = null,
    /** Process seam — defaults to the in-process PRoot spawn. */
    private val targetFactory: () -> StdioProcessTarget = { PRootSpawnTarget(spawn, hooks) },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Injectable timer scheduler (tests); null uses a shared daemon timer. */
    private val schedule: ((delayMs: Long, task: Runnable) -> (() -> Unit))? = null,
    private val stdinCloseGraceMs: Long = McpStdioClose.STDIN_CLOSE_GRACE_MS,
    private val closeTimeoutMs: Long = McpStdioClose.DEFAULT_CLOSE_TIMEOUT_MS,
    private val maxMessageBytes: Int = McpStdioFraming.DEFAULT_MAX_MESSAGE_BYTES,
    private val maxStderrBytes: Int = McpStdioFraming.DEFAULT_MAX_STDERR_BYTES,
) : McpClient {

    /**
     * [T-mcp-stdio-cap] Host-side lifecycle hooks for the spawned proot
     * process. Kept optional and Process-typed so the class stays
     * JVM-testable; the Android wiring ([McpNativeBridge]) registers the
     * process with [com.openminis.app.sandbox.SandboxWorkload] so the whole
     * guest tree is owned: background cgroup confinement, the global
     * process cap, and a tree-wide kill when this server stops — upstream
     * beta.2 "stopping one ends its whole process tree". Without the hook,
     * destroyForcibly() SIGKILLs only proot and the traced node/python
     * server survives as an orphan.
     */
    interface ProcessHooks {
        /** Called right after a successful spawn, before initialize. */
        fun onSpawned(process: Process)

        /**
         * Called exactly once per spawned process when it stops.
         * [graceful] = it exited (possibly only after SIGTERM — proot
         * --kill-on-exit already tore the tracees down); false = it had to be
         * SIGKILLed, the host side must kill the surviving tree itself.
         */
        fun onTerminated(process: Process, graceful: Boolean)
    }

    /**
     * Everything Android-specific needed to launch the guest process:
     * the full argv prefix ending right before the server command
     * (proot + rootfs + bind mounts), plus the environment for the host-side
     * ProcessBuilder (PROOT_LOADER etc.) and the guest-visible env for the
     * server itself.
     */
    data class SpawnSpec(
        /** e.g. [proot, -0, --kill-on-exit, -r, rootfs, -b, ..., /bin/sh, -c, 'exec "$0" "$@"', cmd, args…] */
        val argv: List<String>,
        /** Host ProcessBuilder environment (PROOT_* vars). */
        val processEnv: Map<String, String>,
        /** Guest environment for the server process (PATH, HOME, config env…). */
        val guestEnv: Map<String, String>,
    )

    private val book = McpRequestBook(nowMs, schedule)

    @Volatile private var target: StdioProcessTarget? = null
    @Volatile private var initialized = false
    @Volatile private var framing: McpJsonRpcLineReader? = null
    private var writer: java.io.BufferedWriter? = null
    private val lock = Any()

    private fun alive(): Boolean = target?.isAlive() == true

    /** [T-mcp-stdio-cap] Occupies a live-server slot (process alive). */
    val isLive: Boolean get() = alive()

    /** [T-mcp-stdio-cap] A JSON-RPC request is in flight — not evictable. */
    val isBusy: Boolean get() = book.isBusy

    private fun stderrSnapshot(): String = framing?.stderrSnapshot() ?: ""

    private fun startProcess() {
        stopProcess()
        val t = try {
            targetFactory()
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(McpError.CONNECTION_ERROR, "failed to spawn '${spawn.argv.lastOrNull()}': ${e.message}")
        }
        target = t
        writer = t.stdin.bufferedWriter(StandardCharsets.UTF_8)
        val lines = McpJsonRpcLineReader(
            maxMessageBytes = maxMessageBytes,
            maxStderrBytes = maxStderrBytes,
            onMessage = ::dispatchMessage,
            onError = { message -> AppLogger.warning("McpStdioClient", "[$serverName] $message") },
        )
        framing = lines

        // Reader thread: replies dispatch by id through the book; progress
        // notifications re-arm their request's deadline (pi handleMessage).
        Thread({
            val buf = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = t.stdout.read(buf)
                    if (n < 0) break
                    lines.readStdout(buf, n)
                }
            } catch (_: Exception) {
            } finally {
                lines.end()
                // The reader is the only writer of replies. Once it exits,
                // every in-flight call must fail now instead of each waiting
                // out its own timeout.
                book.failAll("stdio reader exited")
            }
        }, "mcp-stdio-$serverName-reader").apply { isDaemon = true; start() }

        // Stderr drainer: bounded ring for diagnostics (daemon.py parity).
        Thread({
            val buf = ByteArray(8 * 1024)
            try {
                while (true) {
                    val n = t.stderr.read(buf)
                    if (n < 0) break
                    lines.readStderr(buf, n)
                }
            } catch (_: Exception) {
            }
        }, "mcp-stdio-$serverName-stderr").apply { isDaemon = true; start() }
    }

    /**
     * Reader-thread message router (pi handleMessage): replies settle their
     * request through the book, progress notifications re-arm the deadline,
     * everything else is protocol traffic this client does not serve (no
     * server→client requests are supported, so `notifications/cancelled`
     * from the server has nothing to abort).
     */
    private fun dispatchMessage(obj: JSONObject) {
        val rawId = obj.opt("id")
        if (rawId != null && rawId != JSONObject.NULL && (obj.has("result") || obj.has("error"))) {
            // First reply wins; a duplicate for an already settled id is a
            // protocol violation and is dropped by the book.
            McpJsonRpc.idKey(rawId)?.let { book.deliver(it, obj) }
            return
        }
        when (obj.optString("method", "")) {
            "notifications/progress" -> handleProgress(obj.optJSONObject("params"))
            else -> Unit
        }
    }

    /**
     * pi handleProgress (client.ts:536-547): re-arm the request deadline and
     * invoke the caller's callback. A callback throw is logged, never allowed
     * to kill the reader.
     */
    private fun handleProgress(params: JSONObject?) {
        if (params == null) return
        val token = McpJsonRpc.idKey(params.opt("progressToken")) ?: return
        val progress = params.optDouble("progress", Double.NaN)
        if (progress.isNaN()) return
        val entry = book.progress(token) ?: return
        val total = params.optDouble("total", Double.NaN).takeUnless { it.isNaN() }
        val message = params.optString("message", "").takeIf { it.isNotEmpty() }
        val notification = McpProgress(progress, total, message, params)
        try {
            entry.onProgress?.invoke(notification)
        } catch (e: Exception) {
            AppLogger.warning("McpStdioClient", "[$serverName] progress callback failed: ${e.message}")
        }
    }

    private fun rpc(method: String, params: JSONObject?, options: McpRequestOptions?): JSONObject {
        val t = target ?: throw McpError(McpError.CONNECTION_ERROR, "stdio server not running")
        if (!t.isAlive()) {
            val err = stderrSnapshot().trim().take(2048)
            throw McpError(
                McpError.CONNECTION_ERROR,
                "stdio server exited before/while serving '$method'" +
                    if (err.isNotEmpty()) " — stderr tail: $err" else "",
            )
        }
        val id = McpJsonRpc.nextId()
        val key = id.toString()
        val timeoutMs = options?.timeoutMs ?: defaultTimeoutMs(method)
        val onProgress = options?.onProgress
        val pending = book.add(
            id = key,
            method = method,
            timeoutMs = timeoutMs,
            // [T-mcp-abort-cancelled] The spec forbids cancelling `initialize`
            // (pi client.ts:423-432 entry.cancellable).
            cancellable = method != "initialize",
            onProgress = onProgress,
            progressToken = if (onProgress != null) key else null,
            replies = LinkedBlockingQueue(),
        )
        // [T-mcp-abort-cancelled] Hand the caller the abort trigger as soon as
        // the request is queued. Firing it settles the call with ABORTED and —
        // initialize excepted, and only when the request had NOT already
        // completed (pi: the abort listener is removed on settle) — tells the
        // server the request id is abandoned.
        options?.let { opts ->
            opts.onAbortHandle?.let { register ->
                register {
                    val aborted = book.abort(key, opts.abortReason ?: "Aborted")
                    if (aborted is McpRequestBook.AbortResult.Settled && aborted.entry.cancellable) {
                        sendCancelled(id, opts.abortReason ?: "Aborted")
                    }
                }
            }
        }
        try {
            val payload = McpJsonRpc.request(
                id, method,
                if (onProgress != null) withProgressToken(params, id) else params,
            )
            synchronized(lock) {
                val w = writer ?: throw McpError(McpError.CONNECTION_ERROR, "stdin closed")
                w.write(payload.toString())
                w.newLine()
                w.flush()
            }
            val outcome = awaitOutcome(pending, key, timeoutMs, method)
            return when (outcome) {
                is Outcome.Reply -> McpJsonRpc.unwrapResult(outcome.value)
                is Outcome.Failed -> {
                    // [T-mcp-abort-cancelled] A deadline miss cancels the
                    // request on the server too (pi: timeout → cancelPending
                    // → notifications/cancelled, "Request timed out").
                    if (outcome.error.code == McpError.TIMEOUT && pending.cancellable) {
                        sendCancelled(id, McpRequestBook.TIMEOUT_REASON)
                    }
                    throw outcome.error
                }
                // The entry vanished while waiting (never happens outside a
                // close race) — fail with the same deadline error shape.
                null -> throw McpError(
                    McpError.TIMEOUT,
                    "no reply to '$method' within ${timeoutMs / 1000}s",
                )
            }
        } finally {
            book.remove(key)
        }
    }

    /**
     * Waits for the reply with the deadline as the ONLY timeout authority —
     * pi awaits a promise and lets the timer decide. A progress notification
     * re-arms the entry's deadline mid-wait, so the poll window is recomputed
     * from the entry (not the fixed initial timeout) every time it expires:
     * a chatty long call never times out, an idle one still does.
     */
    private fun awaitOutcome(
        pending: McpRequestBook.Pending,
        key: String,
        initialWindowMs: Long,
        method: String,
    ): McpRequestBook.Outcome? {
        // pi armTimeout: a non-positive timeout never arms a deadline — the
        // call waits until the reply or the connection dies (failAll wakes it).
        if (initialWindowMs <= 0) return pending.replies.take()
        var window = initialWindowMs
        while (true) {
            val got = pending.replies.poll(window, TimeUnit.MILLISECONDS)
            if (got != null) return got
            // The poll window expired — check the entry's CURRENT deadline,
            // which a progress notification may have pushed out since.
            val entry = book.get(key) ?: return null
            val remaining = entry.deadlineMs - nowMs()
            if (remaining <= 0) {
                // Same belt-and-braces as the deadline miss: settle through
                // the book (first-wins) and pick up whatever it queued —
                // including a reply that raced the expiry.
                return if (book.timeoutNow(key)) {
                    pending.replies.poll()
                } else {
                    // Settled by a racing timer between the polls — its
                    // outcome is already queued (settle offers under the same
                    // lock timeoutNow just held).
                    pending.replies.poll(50, TimeUnit.MILLISECONDS)
                }
            }
            window = remaining
        }
    }

    private fun defaultTimeoutMs(method: String): Long =
        if (method == "initialize") startupTimeoutMs else requestTimeoutMs

    /**
     * pi requestInternal (client.ts:404-409): the caller's `onProgress`
     * advertises a progress token via `_meta.progressToken` so the server can
     * target its progress notifications at this request.
     */
    private fun withProgressToken(params: JSONObject?, requestId: Int): JSONObject {
        val out = if (params != null) JSONObject(params.toString()) else JSONObject()
        val meta = out.optJSONObject("_meta") ?: JSONObject()
        meta.put("progressToken", requestId)
        out.put("_meta", meta)
        return out
    }

    /**
     * [T-mcp-abort-cancelled] Best-effort `notifications/cancelled` (pi
     * `cancelPending` sends it whenever the entry was still pending and
     * cancellable; send errors are logged, never propagated).
     */
    private fun sendCancelled(requestId: Int, reason: String) {
        val params = JSONObject().apply {
            put("requestId", requestId)
            if (reason.isNotBlank()) put("reason", reason)
        }
        runCatching { notify("notifications/cancelled", params) }
            .onFailure {
                AppLogger.warning("McpStdioClient", "[$serverName] notifications/cancelled failed: ${it.message}")
            }
    }

    private fun notify(method: String, params: JSONObject? = null) {
        val w = writer ?: return
        runCatching {
            synchronized(lock) {
                w.write(McpJsonRpc.notification(method, params).toString())
                w.newLine()
                w.flush()
            }
        }
    }

    override fun ensureInitialized() {
        if (initialized && alive()) return
        synchronized(lock) {
            if (initialized && alive()) return
            if (!alive()) startProcess()
            // initialize rides the startup timeout (defaultTimeoutMs) and is
            // never cancellable.
            val result = rpc("initialize", McpJsonRpc.initializeParams(), null)
            // Server may negotiate a different version; we accept whatever it
            // answers (daemon.py parity) and keep the constant header value.
            val proto = result.optString("protocolVersion", McpJsonRpc.PROTOCOL_VERSION)
            notify("notifications/initialized")
            initialized = true
            AppLogger.info(
                "McpStdioClient",
                "[$serverName] initialized (protocol=$proto)",
            )
        }
    }

    /**
     * [T-mcp-list-all] All pages of `tools/list`, following `nextCursor`
     * (pi listAll): capped at 1000 pages, duplicate cursors refuse.
     */
    override fun listTools(): JSONArray {
        ensureInitialized()
        return McpListPager.collect("tools/list", "tools") { cursor ->
            rpc("tools/list", cursorParams(cursor), null)
        }
    }

    override fun callTool(tool: String, arguments: JSONObject): JSONObject =
        callTool(tool, arguments, null)

    /**
     * `tools/call` with per-request options — a deadline override, a progress
     * callback (whose notifications re-arm the deadline), and an abort handle
     * that sends `notifications/cancelled` on cancellation.
     */
    fun callTool(tool: String, arguments: JSONObject, options: McpRequestOptions?): JSONObject {
        ensureInitialized()
        return rpc(
            "tools/call",
            JSONObject().apply { put("name", tool); put("arguments", arguments) },
            options,
        )
    }

    private fun cursorParams(cursor: String?): JSONObject =
        if (cursor == null) JSONObject() else JSONObject().put("cursor", cursor)

    private fun stopProcess() {
        initialized = false
        writer = null
        val t = target
        target = null
        if (t == null) return
        // [T-mcp-stdio-close-sequence] pi stdio.ts close(): close stdin FIRST
        // and give the server its grace window to exit on its own — a well
        // behaved server shuts down on EOF without ever seeing a signal. The
        // grace is capped by the close timeout (McpStdioClose.graceMs).
        runCatching { t.stdin.close() }
        val grace = minOf(stdinCloseGraceMs, closeTimeoutMs)
        if (t.awaitExit(grace)) {
            // Graceful: proot's --kill-on-exit already reaped the tracees.
            // (pi additionally SIGTERMs the group on close for children that
            // survive the parent while holding its pipes; here every guest
            // process is a proot tracee and --kill-on-exit covers them, so
            // the hook only unregisters the root.)
            t.onTerminated(graceful = true)
            return
        }
        t.sigterm()
        if (t.awaitExit(closeTimeoutMs)) {
            t.onTerminated(graceful = true)
            return
        }
        t.sigkill()
        // [T-mcp-stdio-cap] SIGKILLed proot cannot run its --kill-on-exit
        // teardown — the host hook must kill the surviving tracee tree
        // (SandboxWorkload.release kill=true → killOwned: /proc tree walk +
        // process-group Os.kill(-pid, SIGKILL), the ExecutionCoordinator
        // pattern).
        t.onTerminated(graceful = false)
    }

    override fun close() {
        synchronized(lock) {
            stopProcess()
            book.dispose()
        }
    }
}
