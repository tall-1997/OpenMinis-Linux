package com.openminis.app.mcp.server

import android.content.Context
import android.util.Log
import com.openminis.app.mcp.server.McpServerCore.ErrorCode
import com.openminis.app.sandbox.SessionWorkspace
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Streamable-HTTP (JSON-RPC 2.0) MCP server bound to 127.0.0.1 only.
 *
 * The socket layer is a thin shell: it parses the HTTP request, extracts the
 * JSON-RPC envelope and delegates to [McpServerCore.handle]. Only POST to
 * `/mcp` or `/message` is accepted; GET returns 405 (v1 is POST-only).
 *
 * Loopback-only binding means plain HTTP is safe for local clients; an
 * optional Bearer token (never empty when auth is enabled) gates requests
 * for defense in depth (e.g. other apps on-device probing the port).
 */
class McpHttpServer(
    // Nullable for JVM unit tests; the class never dereferences it —
    // host-path work happens in McpToolDispatcher which guards on its own.
    private val context: Context?,
    private val dispatcher: McpServerCore.ToolDispatcher,
    private val authToken: () -> String?,
    /** App versionName, surfaced in MCP initialize serverInfo.version. */
    private val serverVersion: String = "0.0.0",
    private val onRequestHandled: () -> Unit = {},
    /**
     * [T-mcp-accept-recovery] Invoked on the accept thread when the loop gave
     * up: recovery attempts were exhausted and the server has stopped itself
     * (running=false, [lastError] set). Lets the manager's status surface show
     * stopped-with-error instead of a perpetual RUNNING.
     */
    private val onSelfStopped: (() -> Unit)? = null,
) {

    companion object {
        private const val TAG = "McpHttpServer"
        const val DEFAULT_PORT = 8765
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /** Max JSON-RPC body accepted; a larger Content-Length gets a 413. */
        const val MAX_BODY_BYTES = 4 * 1024 * 1024

        /** Max header block size before the connection is dropped. */
        internal const val MAX_HEADER_BYTES = 64 * 1024

        /** [T-mcp-accept-recovery] Same construction as [start] uses. */
        private const val BIND_HOST = "127.0.0.1"
        private const val BACKLOG = 10

        /**
         * [T-mcp-accept-recovery] One IOException from `accept()` used to break
         * the loop forever while `running` stayed true — a "running" server that
         * accepts nothing. The loop now re-binds up to this many times, waiting
         * this long between attempts, before giving up and stopping itself.
         */
        private const val MAX_ACCEPT_RECOVERY_ATTEMPTS = 5
        private const val ACCEPT_RECOVERY_BACKOFF_MS = 500L

        /**
         * [P2-mcp-pool] Worker count. The fixed 4 was small enough that a
         * handful of slow `tools/call` (each parks a worker inside
         * McpToolShared.runInGuest's runBlocking for the whole command) could
         * starve every other MCP method — even tools/list on another
         * connection. max(8, 2×cores) leaves headroom for the fast methods
         * while a few slow tool calls are in flight.
         */
        val workerCount: Int = maxOf(8, 2 * Runtime.getRuntime().availableProcessors())

        fun validPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var pool: ExecutorService? = null
    private var acceptThread: Thread? = null

    /**
     * [T-mcp-accept-recovery] Whether the accept loop is still alive. A server
     * that gave up recovering its listening socket sets this false (and
     * [lastError]) so status surfaces can tell "running" from "stopped itself".
     */
    val isRunning: Boolean
        get() = running.get()

    /**
     * [P2-mcp-pool] Connections accepted but not yet finished — includes both
     * running workers and queued tasks (the fixed-pool queue is unbounded, so
     * saturation shows up as growth here, not a rejection). Logged on submit
     * when everything is busy so a starving server leaves a trace.
     */
    private val inFlightConnections = AtomicLong(0)

    /** Exposed for the manager's state line. */
    @Volatile
    var lastError: String? = null
        private set

    /** True when the last start() failed because the port was already in use. */
    @Volatile
    var lastBindConflict: Boolean = false
        private set

    private val handledRequests = AtomicLong(0)

    val handledRequestCount: Long get() = handledRequests.get()

    @Synchronized
    fun start(port: Int): Boolean {
        if (running.get()) return true
        lastBindConflict = false
        return try {
            // [T-mcp-accept-recovery] The INITIAL bind must NOT set
            // SO_REUSEADDR: on Windows that option allows a silent double-bind
            // (the server "wins" the bind but another socket keeps the
            // traffic — client connects time out while status says RUNNING).
            // A collision on the initial start is a clean BindException the
            // caller retries with a different port. Only the RECOVERY path
            // needs the option (re-binding a port this server just owned).
            val socket = bindServerSocket(port, reuseAddress = false)
            serverSocket = socket
            pool = Executors.newFixedThreadPool(workerCount) { r ->
                Thread(r, "mcp-server-worker").apply { isDaemon = true }
            }
            running.set(true)
            lastError = null
            acceptThread = Thread({
                acceptLoop(socket, port)
            }, "mcp-server-accept").apply { isDaemon = true }
            acceptThread!!.start()
            Log.i(TAG, "MCP server listening on $BIND_HOST:$port")
            true
        } catch (e: Exception) {
            lastError = e.message
            lastBindConflict = e is BindException
            Log.w(TAG, "Failed to start MCP server on port $port: ${e.message}")
            running.set(false)
            runCatching { serverSocket?.close() }
            serverSocket = null
            false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { pool?.shutdownNow() }
        pool = null
        acceptThread = null
    }

    /** The one place that constructs the listening socket (start + recovery). */
    private fun bindServerSocket(port: Int, reuseAddress: Boolean): ServerSocket =
        ServerSocket().apply {
            // [T-mcp-accept-recovery] SO_REUSEADDR is only wanted on the
            // RECOVERY path (re-binding a port this server just owned, where
            // children of the dead listener may sit in TIME_WAIT). On Windows
            // the option enables silent double-bind — see the start() note.
            this.reuseAddress = reuseAddress
            try {
                bind(java.net.InetSocketAddress(InetAddress.getByName(BIND_HOST), port), BACKLOG)
            } catch (e: Exception) {
                // [T-mcp-accept-recovery] A failed bind leaves the impl fd
                // allocated — close the candidate or the recovery loop leaks
                // one fd per attempt (bounded at 5, still a leak).
                runCatching { close() }
                throw e
            }
        }

    /**
     * [T-mcp-accept-recovery] Test hook: the live listening socket, or null.
     * Closing it simulates the accept failure the recovery path exists for.
     */
    internal fun listeningSocketForTest(): ServerSocket? = serverSocket

    /**
     * [T-mcp-accept-recovery] Accept loop with bounded recovery.
     *
     * The old loop `break`ed on ANY accept failure while `running` stayed true,
     * leaving a server that reported running but accepted nothing (one
     * transient IOException from `socket.accept()` was enough — e.g. an fd
     * limit hiccup). Now: on an accept failure while running, close the dead
     * socket, wait briefly and re-bind the same port, up to
     * [MAX_ACCEPT_RECOVERY_ATTEMPTS] times. If every attempt fails, the server
     * stops itself honestly: running=false, [lastError] recorded, worker pool
     * left for [stop] to reclaim, and [onSelfStopped] fired so the manager's
     * status surface reflects the stopped state instead of a perpetual RUNNING.
     */
    private fun acceptLoop(socket: ServerSocket, port: Int) {
        var current = socket
        while (running.get()) {
            val client = try {
                current.accept()
            } catch (e: Exception) {
                if (!running.get()) break // stop() closed the socket under us — normal shutdown
                lastError = e.message
                Log.w(TAG, "accept failed: ${e.message}")
                val recovered = recoverAcceptSocket(current, port, e)
                if (recovered == null) {
                    if (!running.get()) break // stop() raced the recovery — normal shutdown
                    val giveUpMessage = "accept loop stopped after $MAX_ACCEPT_RECOVERY_ATTEMPTS " +
                        "recovery attempts: ${e.message}"
                    lastError = giveUpMessage
                    Log.e(TAG, giveUpMessage)
                    // [T-mcp-accept-recovery] Give-up publication under the same
                    // monitor stop() holds — a racing stop() closing the OLD
                    // socket must not orphan the freshly re-bound one (F5 leak).
                    synchronized(this@McpHttpServer) {
                        running.set(false)
                        runCatching { current.close() }
                        runCatching { serverSocket?.takeIf { it !== current }?.close() }
                        serverSocket = null
                    }
                    onSelfStopped?.let { runCatching { it() } }
                    break
                }
                current = recovered
                continue
            }
            // [P2-mcp-pool] Saturation trace: when every worker is busy the
            // unbounded queue grows silently; leave a warning with the depth.
            val pending = inFlightConnections.incrementAndGet()
            // [T-mcp-server-admission] Refuse instead of queueing forever. The
            // fixed pool's queue is unbounded, and a queued socket has no
            // `soTimeout` yet (that is armed inside handleConnection), so every
            // unserved connection pins an fd. Loopback has no UID isolation on
            // Android: any local app could open connections until the whole
            // process ran out of descriptors.
            if (pending > workerCount * 2) {
                inFlightConnections.decrementAndGet()
                Log.w(TAG, "refused connection: $pending in flight/queued (workers=$workerCount)")
                runCatching { client.close() }
                // Admission refused this peer, not the listener. `return` would
                // kill the accept loop while the server still reports RUNNING.
                continue
            }
            // Arm the idle timeout here as well: a socket that waits in the queue
            // must not outlive a stalled peer.
            runCatching { client.soTimeout = 30_000 }
            pool?.execute {
                try {
                    handleConnection(client)
                } finally {
                    inFlightConnections.decrementAndGet()
                }
            }
        }
        // The loop only exits when running went false. If a re-bound socket is
        // still published (stop() raced the recovery and closed the OLD socket),
        // reclaim it here so recovery can never leak a listening fd.
        if (serverSocket === current) {
            serverSocket = null
        }
        runCatching { current.close() }
    }

    /**
     * [T-mcp-accept-recovery] Close the failed listening socket and try to
     * re-bind the same host/port with a short backoff between attempts.
     * Returns the fresh socket, or null when the attempts are exhausted (or
     * the server was stopped in the meantime). On the first success the new
     * socket is published to [serverSocket] so [stop] closes the right one.
     */
    private fun recoverAcceptSocket(dead: ServerSocket, port: Int, cause: Exception): ServerSocket? {
        runCatching { dead.close() }
        repeat(MAX_ACCEPT_RECOVERY_ATTEMPTS) { attempt ->
            if (!running.get()) return null
            try {
                Thread.sleep(ACCEPT_RECOVERY_BACKOFF_MS)
                if (!running.get()) return null
                // [T-mcp-accept-recovery] Recovery RE-binds a port this server
                // just owned — children of the dead listener may sit in
                // TIME_WAIT, so SO_REUSEADDR is wanted here (start() keeps it
                // off, see the double-bind note there).
                val fresh = bindServerSocket(port, reuseAddress = true)
                // [T-mcp-accept-recovery] Publish under the instance monitor —
                // the same one [stop] holds — so a concurrent stop() can never
                // close the OLD socket while the fresh one stays leaked.
                synchronized(this@McpHttpServer) {
                    if (!running.get()) {
                        runCatching { fresh.close() }
                        return null
                    }
                    serverSocket = fresh
                }
                lastError = null
                Log.i(
                    TAG,
                    "accept recovered on attempt ${attempt + 1}/$MAX_ACCEPT_RECOVERY_ATTEMPTS: " +
                        "re-bound $BIND_HOST:$port (previous failure: ${cause.message})",
                )
                return fresh
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "accept recovery attempt ${attempt + 1}/$MAX_ACCEPT_RECOVERY_ATTEMPTS failed: ${e.message}",
                )
            }
        }
        return null
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            // Single BufferedInputStream for the whole connection: header bytes
            // and body bytes are read from the same stream, so a byte can never
            // be swallowed by a char-level decoder buffer (the old
            // BufferedReader+InputStreamReader read by *chars* while
            // Content-Length counts *bytes*, which broke multi-byte UTF-8 and
            // could read past the body into the next pipelined request).
            val input = BufferedInputStream(socket.getInputStream())
            val writer = PrintWriter(socket.getOutputStream(), false, Charsets.UTF_8)

            // ── Read request line + headers as bytes until CRLF CRLF ──
            val headerBytes = readHeaderBlock(input, writer, socket)
            if (headerBytes == null) return

            val headerText = String(headerBytes, Charsets.UTF_8)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull() ?: run {
                writer.print(McpServerCore.buildHttpResponse(McpServerCore.Response(400, "bad request")))
                writer.flush()
                socket.close()
                return
            }
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) {
                writer.print(McpServerCore.buildHttpResponse(McpServerCore.Response(400, "bad request")))
                writer.flush()
                socket.close()
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            // ── Body: Content-Length is a *byte* count; validate then read ──
            val contentLengthRaw = headers["content-length"]
            val contentLength = contentLengthRaw?.toLongOrNull()
            if (contentLength == null && contentLengthRaw != null) {
                writer.print(McpServerCore.buildHttpResponse(McpServerCore.Response(400, "bad content-length")))
                writer.flush()
                socket.close()
                return
            }
            if ((contentLength ?: 0L) > MAX_BODY_BYTES) {
                // 413 without allocating anything near the declared size.
                writer.print(McpServerCore.buildHttpResponse(
                    McpServerCore.Response(413, "body too large", "text/plain")))
                writer.flush()
                socket.close()
                return
            }
            val body = if (contentLength != null && contentLength > 0) {
                val buf = ByteArray(contentLength.toInt())
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else {
                ""
            }

            val response = route(method, path, headers, body)
            writer.print(McpServerCore.buildHttpResponse(response))
            writer.flush()
        } catch (e: Exception) {
            Log.w(TAG, "connection error: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Read the request line + headers up to and including the terminating
     * CRLF CRLF. Returns the header bytes (excluding the final blank line),
     * or null when the header block is malformed or exceeds [MAX_HEADER_BYTES]
     * (in which case a 400/431 response has already been written).
     *
     * Reads byte-by-byte through the [BufferedInputStream]: cheap because the
     * stream buffers underneath, and it guarantees no byte beyond the header
     * terminator is consumed — everything after CRLF CRLF belongs to the body.
     */
    private fun readHeaderBlock(
        input: BufferedInputStream,
        writer: PrintWriter,
        socket: Socket,
    ): ByteArray? {
        val out = ByteArrayOutputStream()
        var state = 0 // 0..3: chars of "\r\n\r\n" matched so far
        while (true) {
            val b = input.read()
            if (b < 0) {
                // EOF before end of headers.
                runCatching { socket.close() }
                return null
            }
            if (out.size() >= MAX_HEADER_BYTES) {
                writer.print(McpServerCore.buildHttpResponse(
                    McpServerCore.Response(431, "header block too large", "text/plain")))
                writer.flush()
                runCatching { socket.close() }
                return null
            }
            out.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> {
                    val bytes = out.toByteArray()
                    return bytes.copyOf(bytes.size - 4) // drop trailing CRLFCRLF
                }
                b == '\r'.code -> 1
                else -> 0
            }
        }
    }

    private fun route(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
    ): McpServerCore.Response {
        when (method) {
            "OPTIONS" -> return McpServerCore.Response(204, "", "text/plain")
            "GET" -> {
                // v1 is POST-only. SSE will come later.
                return McpServerCore.Response(405, "GET not supported; use POST /mcp or /message", "text/plain")
            }
            "POST" -> {
                if (path != "/mcp" && path != "/message") {
                    return McpServerCore.Response(404, "not found", "text/plain")
                }
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                    ?: return McpServerCore.rpcErrorNullId(ErrorCode.PARSE_ERROR, "invalid JSON body")
                val rpcMethod = parsed.optString("method", "")
                if (rpcMethod.isEmpty()) {
                    return McpServerCore.rpcErrorNullId(ErrorCode.INVALID_REQUEST, "missing 'method'")
                }
                val params = parsed.optJSONObject("params")
                val id: Any? = if (parsed.has("id") && !parsed.isNull("id")) parsed.opt("id") else null

                val response = McpServerCore.handle(
                    method = rpcMethod,
                    params = params,
                    headers = headers,
                    id = id,
                    dispatcher = dispatcher,
                    authToken = authToken(),
                    serverVersion = serverVersion,
                )
                handledRequests.incrementAndGet()
                onRequestHandled()
                return response
            }
            else -> return McpServerCore.Response(405, "method not allowed", "text/plain")
        }
    }

    // [P2-dead-code] rpcErrorResponse removed: private, zero callers — the
    // route() paths answer via McpServerCore.rpcErrorNullId / handle directly.
}

/**
 * Call once at startup: materializes the mcp-server session workspace dirs so
 * file_read + shell_exec resolve /var/minis paths without a chat session.
 */
fun ensureMcpServerWorkspace(context: Context) {
    runCatching { SessionWorkspace.ensureDirs(context.filesDir, McpToolDispatcher.SESSION_ID) }
}