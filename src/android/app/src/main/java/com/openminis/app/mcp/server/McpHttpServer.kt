package com.openminis.app.mcp.server

import android.content.Context
import android.util.Log
import com.openminis.app.mcp.server.McpServerCore.ErrorCode
import com.openminis.app.sandbox.SessionWorkspace
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
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
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(socket.getOutputStream(), false, Charsets.UTF_8)

            // Read the request: request line + headers, then Content-Length body.
            val requestLine = reader.readLine() ?: run {
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
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
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
                val id = if (parsed.has("id") && !parsed.isNull("id")) parsed.optInt("id") else null

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

    private fun rpcErrorResponse(id: Int?, code: Int, message: String): McpServerCore.Response =
        McpServerCore.rpcError(id, code, message)
}

/**
 * Call once at startup: materializes the mcp-server session workspace dirs so
 * file_read + shell_exec resolve /var/minis paths without a chat session.
 */
fun ensureMcpServerWorkspace(context: Context) {
    runCatching { SessionWorkspace.ensureDirs(context.filesDir, McpToolDispatcher.SESSION_ID) }
}