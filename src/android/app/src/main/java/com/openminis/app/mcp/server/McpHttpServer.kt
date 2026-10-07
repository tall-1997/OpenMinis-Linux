package com.openminis.app.mcp.server

import android.content.Context
import android.util.Log
import com.openminis.app.mcp.server.McpServerCore.ErrorCode
import com.openminis.app.sandbox.SessionWorkspace
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
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
) {

    companion object {
        private const val TAG = "McpHttpServer"
        const val DEFAULT_PORT = 8765
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /** Max JSON-RPC body accepted; a larger Content-Length gets a 413. */
        const val MAX_BODY_BYTES = 4 * 1024 * 1024

        /** Max header block size before the connection is dropped. */
        private const val MAX_HEADER_BYTES = 64 * 1024

        fun validPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var pool: ExecutorService? = null
    private var acceptThread: Thread? = null

    /** Exposed for the manager's state line. */
    @Volatile
    var lastError: String? = null
        private set

    private val handledRequests = AtomicLong(0)

    val handledRequestCount: Long get() = handledRequests.get()

    @Synchronized
    fun start(port: Int): Boolean {
        if (running.get()) return true
        return try {
            val socket = ServerSocket(port, 10, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            pool = Executors.newFixedThreadPool(4) { r ->
                Thread(r, "mcp-server-worker").apply { isDaemon = true }
            }
            running.set(true)
            lastError = null
            acceptThread = Thread({
                acceptLoop(socket)
            }, "mcp-server-accept").apply { isDaemon = true }
            acceptThread!!.start()
            Log.i(TAG, "MCP server listening on 127.0.0.1:$port")
            true
        } catch (e: Exception) {
            lastError = e.message
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

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running.get()) {
                    lastError = e.message
                    Log.w(TAG, "accept failed: ${e.message}")
                }
                break
            }
            pool?.execute { handleConnection(client) }
        }
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

    private fun rpcErrorResponse(id: Any?, code: Int, message: String): McpServerCore.Response =
        McpServerCore.rpcError(id, code, message)
}

/**
 * Call once at startup: materializes the mcp-server session workspace dirs so
 * file_read + shell_exec resolve /var/minis paths without a chat session.
 */
fun ensureMcpServerWorkspace(context: Context) {
    runCatching { SessionWorkspace.ensureDirs(context.filesDir, McpToolDispatcher.SESSION_ID) }
}