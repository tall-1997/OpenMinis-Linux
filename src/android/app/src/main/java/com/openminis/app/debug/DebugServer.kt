package com.openminis.app.debug

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Debug-only JSON-RPC 2.0 server on port 5322.
 * Listens on all interfaces (0.0.0.0) so it's reachable from the local network for debugging.
 * Mirrors the iOS DebugServer for parity with the debug-server CLI skill.
 *
 * IMPORTANT: Only start this in debug builds. Never start in release.
 */
class DebugServer(
    private val context: Context,
    private val port: Int = 5322,
) {
    companion object {
        private const val TAG = "DebugServer"

        /**
         * [T-debugserver-request-limits] Request-size caps, mirroring
         * McpHttpServer (MAX_BODY_BYTES = 4 MB → 413, header block = 64 KiB →
         * 431): the debug server shares its threat model — a local process (or
         * LAN client with the token) that can otherwise allocate the app to
         * death with one declared Content-Length or one endless header line.
         * A line count cap [MAX_HEADER_COUNT] bounds header-count games on top
         * of the byte budget. Like nginx's 100 it counts HEADER lines only —
         * the request line and the empty terminator are not headers, so they
         * must not eat into the budget.
         */
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_HEADER_COUNT = 100

        /** [Wave3 #13] LAN-listen opt-in prefs (debug builds only, default OFF). */
        private const val LAN_LISTEN_PREFS = "debug_server_prefs"
        private const val LAN_LISTEN_KEY = "lan_listen"

        /**
         * [T-android-debugserver-auth] Remote-connection auth decision, kept
         * pure for unit testing. Loopback connections (adb forward — the
         * developer's own machine over USB) stay token-free so the local
         * tooling keeps working unchanged; any NON-loopback (LAN) connection
         * must present the device token. Rationale: Android debug builds are
         * never distributed (release channel ships assembleRelease without
         * this server), but the dev workflow leaves the device reachable on
         * the LAN for remote-worker e2e — an unauthenticated 0.0.0.0 RPC
         * surface there can read logs/files and burn API quota. The iOS
         * protocol-v1 encrypted envelope (358e3ded) is deliberately NOT
         * ported: with no distribution surface the token gate is the
         * proportionate hardening (evaluated in the A8 batch report).
         */
        fun isAuthorized(isLoopback: Boolean, providedToken: String?, expectedToken: String): Boolean {
            if (isLoopback) return true
            if (expectedToken.isEmpty()) return false
            val provided = providedToken ?: return false
            if (provided.length != expectedToken.length) return false
            // Constant-time compare — don't leak the token via timing.
            var diff = 0
            for (i in expectedToken.indices) {
                diff = diff or (provided[i].code xor expectedToken[i].code)
            }
            return diff == 0
        }
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val rpcHandler = DebugRPCHandler(context)

    /**
     * [T-android-debugserver-auth] Per-install token required from
     * non-loopback clients. Generated once, persisted in filesDir so the
     * developer can read it with:
     *   adb shell run-as com.openminis.app cat files/debug_server_token
     * Also logged at startup (logcat is adb-only — not readable remotely).
     */
    private val authToken: String by lazy {
        val f = java.io.File(context.filesDir, "debug_server_token")
        if (f.exists()) {
            f.readText().trim().ifEmpty { generateToken(f) }
        } else {
            generateToken(f)
        }
    }

    private fun generateToken(f: java.io.File): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        runCatching { f.writeText(token) }
        return token
    }

    @Volatile
    private var stopped = false

    /**
     * [Wave3 #13] Explicit LAN-listen opt-in. Debug builds only (release never
     * starts this server at all); default OFF. Prefs file "debug_server_prefs",
     * key "lan_listen" — kept out of the token file so `adb shell` tooling
     * keeps a stable contract.
     */
    private fun lanListenEnabled(): Boolean {
        if (!com.openminis.app.BuildConfig.DEBUG) return false
        return runCatching {
            com.openminis.app.config.Prefs.file(LAN_LISTEN_PREFS)
                .bool(LAN_LISTEN_KEY, false)
        }.getOrDefault(false)
    }

    fun start() {
        if (serverSocket != null) return
        if (!scope.isActive) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(scopeJob + Dispatchers.IO)
        }
        stopped = false

        acceptJob = scope.launch {
            try {
                // [Wave3 #13] Default bind is LOOPBACK (127.0.0.1), not 0.0.0.0.
                // The old all-interfaces bind left an RPC surface that reads
                // logs/files and spends API quota exposed to the whole LAN by
                // default, gated only by the X-Minis-Token. adb forward (the
                // workflow this server actually serves) reaches a loopback
                // bind fine. LAN access is now an explicit opt-in: debug
                // builds only, prefs key debug_server_prefs/lan_listen
                // (default OFF), read right before the ServerSocket is
                // created. UI hook (owner: ui/debug or the debug panel owner):
                // a "LAN access" switch writing that prefs file+key; adb
                // equivalent:
                //   adb shell run-as com.openminis.app sh -c                 //     'printf "" > /dev/null'  # (set via the UI switch)
                val lan = lanListenEnabled()
                val ss = if (lan) {
                    ServerSocket(port, 10)
                } else {
                    ServerSocket(port, 10, java.net.InetAddress.getByName("127.0.0.1"))
                }
                serverSocket = ss
                if (lan) {
                    Log.i(TAG, "Debug server listening on port $port (all interfaces — LAN access enabled)")
                    Log.i(TAG, "Remote (non-loopback) clients must send X-Minis-Token: $authToken")
                } else {
                    Log.i(TAG, "Debug server listening on port $port (loopback only)")
                }

                while (!stopped) {
                    try {
                        val client = ss.accept()
                        launch { handleConnection(client) }
                    } catch (e: Exception) {
                        if (!stopped) Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server on port $port: ${e.message}")
            }
        }
    }

    fun stop() {
        stopped = true
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        scopeJob.cancel()
        Log.i(TAG, "Server stopped")
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                // Byte-level, so the header parser cannot pull body bytes into a
                // char decoder's buffer and so the body can be read as the exact
                // Content-Length byte count (see the body read below).
                val reader = HttpRequestReader(s.getInputStream())
                val writer = PrintWriter(s.getOutputStream(), true)

                // [Wave3 #13] CORS tightened: only the Android WebView /
                // file-scheme "null" origin (or no Origin at all) gets a grant.
                // Declared before the early-return paths below so every
                // response can honour it.
                var origin: String? = null

                // [T-debugserver-request-limits] Read HTTP request line. The
                // head budget (request line + headers together) is enforced
                // INSIDE HttpRequestReader.readLine — a single oversized line
                // must not buffer unbounded bytes before any check runs — so
                // both read sites answer 431 and close.
                val requestLine = try {
                    reader.readLine()
                } catch (e: RequestHeadTooLarge) {
                    sendHeadTooLarge(writer, origin)
                    return
                } ?: return
                val parts = requestLine.split(" ", limit = 3)
                if (parts.size < 2) {
                    sendResponse(writer, 400, rpcHandler.errorJSON(-32700, "Parse error"), origin = origin)
                    return
                }
                val method = parts[0]
                // [T-android-debugserver-skill] Path (query stripped) so the
                // unauthenticated GET skill routes can be dispatched.
                val path = parts.getOrNull(1)?.substringBefore('?') ?: "/"

                // Handle CORS preflight
                if (method == "OPTIONS") {
                    sendCorsPreflightResponse(writer, origin)
                    return
                }

                // Read headers
                var contentLength = 0
                var providedToken: String? = null
                var accept = ""
                while (true) {
                    val headerLine = try {
                        reader.readLine()
                    } catch (e: RequestHeadTooLarge) {
                        sendHeadTooLarge(writer, origin)
                        return
                    } ?: break
                    if (headerLine.isEmpty()) break
                    val lower = headerLine.lowercase()
                    if (lower.startsWith("content-length:")) {
                        contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    if (lower.startsWith("accept:")) {
                        accept = lower.substringAfter(":").trim()
                    }
                    if (lower.startsWith("origin:")) {
                        origin = headerLine.substringAfter(":").trim()
                    }
                    // [T-android-debugserver-auth] Token via X-Minis-Token or
                    // Authorization: Bearer — either spelling accepted.
                    if (lower.startsWith("x-minis-token:")) {
                        providedToken = headerLine.substringAfter(":").trim()
                    }
                    if (lower.startsWith("authorization:")) {
                        val v = headerLine.substringAfter(":").trim()
                        if (v.lowercase().startsWith("bearer ")) {
                            providedToken = v.substring(7).trim()
                        }
                    }
                }

                // [T-android-debugserver-auth] Gate BEFORE any RPC dispatch:
                // loopback (adb forward) is exempt; LAN clients need the token.
                // The exemption short-circuits BEFORE the token lazy is read:
                // loopback needs no filesystem access at all, so the hot
                // adb-forward path never touches filesDir.
                val isLoopback = s.inetAddress?.isLoopbackAddress == true
                val authorized = isLoopback || isAuthorized(false, providedToken, authToken)
                if (!authorized) {
                    Log.w(TAG, "401 unauthorized ${if (isLoopback) "loopback" else s.inetAddress?.hostAddress ?: "?"} (missing/wrong token)")
                    sendResponse(writer, 401, rpcHandler.errorJSON(-32000, "Unauthorized — send X-Minis-Token (see `adb shell run-as com.openminis.app cat files/debug_server_token`)"), origin = origin)
                    return
                }

                // [T-android-debugserver-skill] Self-serve bootstrap routes,
                // mirroring iOS: a client that only has curl can pull the manual
                // and a working reference client straight off the device.
                //
                // Deliberately placed AFTER the auth gate above: unlike iOS
                // (whose /skill is unauthenticated because every RPC is still
                // sealed by the v1 envelope), Android's RPCs are plaintext, so
                // the token remains the only barrier for LAN clients and must not
                // be bypassed. Over `adb forward` (loopback) it's token-free,
                // which is the path the tooling actually uses.
                if (method == "GET") {
                    val wantsHuman = accept.contains("text/html") ||
                        accept.contains("text/markdown") ||
                        accept.contains("text/plain")
                    when (path) {
                        "/schema" -> {
                            sendResponse(writer, 200, schemaJSON(), origin = origin)
                            return
                        }
                        "/", "/skill", "/skill/" -> {
                            // A bare `GET /` from a tool (no human Accept) keeps
                            // returning the machine schema, same contract as iOS.
                            if (path == "/" && !wantsHuman) {
                                sendResponse(writer, 200, schemaJSON(), origin = origin)
                            } else {
                                sendSkill(writer, wantsHuman)
                            }
                            return
                        }
                        "/skill/examples/python", "/skill/examples/minis_rpc_android.py" -> {
                            sendSkillAsset(writer, "examples/minis_rpc_android.py", "text/x-python; charset=utf-8")
                            return
                        }
                        "/skill/examples/curl", "/skill/examples/curl.md" -> {
                            sendSkillAsset(writer, "examples/curl.md", "text/markdown; charset=utf-8")
                            return
                        }
                    }
                }

                if (method != "POST") {
                    sendResponse(writer, 405, rpcHandler.errorJSON(-32600, "Only POST accepted"), origin = origin)
                    return
                }

                if (contentLength <= 0) {
                    sendResponse(writer, 400, rpcHandler.errorJSON(-32700, "Empty body"), origin = origin)
                    return
                }

                // [T-debugserver-request-limits] Refuse BEFORE allocating:
                // contentLength is client-controlled and ByteArray(contentLength)
                // on a declared gigabyte is an instant OOM. The cap matches
                // McpHttpServer.MAX_BODY_BYTES; the response goes out without
                // reading any of the oversized body.
                if (contentLength > MAX_BODY_BYTES) {
                    Log.w(TAG, "413 body too large: $contentLength bytes (limit $MAX_BODY_BYTES)")
                    sendResponse(writer, 413, rpcHandler.errorJSON(-32700, "body too large"), origin = origin)
                    return
                }

                // Read body.
                //
                // Content-Length counts BYTES, so the loop must too. Reading it
                // as CharArray off the decoding reader counted CHARACTERS, and
                // any multi-byte UTF-8 in the body made the two disagree: a
                // 6-byte "你好" decodes to 2 chars, so the loop kept waiting for
                // 4 more that were never coming and the request stalled until
                // one side timed out. Callers saw non-ASCII requests hang while
                // the identical \u-escaped (all-ASCII) body answered in ~25ms.
                val body = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val n = reader.read(body, totalRead, contentLength - totalRead)
                    if (n < 0) break
                    totalRead += n
                }
                val jsonBody = String(body, 0, totalRead, Charsets.UTF_8)

                val responseJSON = runBlocking {
                    rpcHandler.handle(jsonBody)
                }

                sendResponse(writer, 200, responseJSON, origin = origin)
            } catch (e: Exception) {
                Log.w(TAG, "Connection error: ${e.message}")
            }
        }
    }

    /// [T-android-debugserver-skill] Capability descriptor. Mirrors the iOS
    /// shape so a shared client can detect the platform: `auth` reports the
    /// Android scheme ("token-lan" — plaintext payloads, token only for
    /// non-loopback) rather than iOS's "v1" envelope, and there is no pair_path.
    private fun schemaJSON(): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        return """{"app":"MinisApp","platform":"android","version":"$version",""" +
            """"rpc":"jsonrpc-2.0","auth":"token-lan","transport":"plaintext",""" +
            """"rpc_path":"/","skill_path":"/skill"}"""
    }

    /// GET / and /skill. Human clients get raw markdown; tools get JSON with the
    /// manual plus every reference client inlined, so one request bootstraps.
    private fun sendSkill(writer: PrintWriter, wantsHuman: Boolean) {
        val skill = readSkillAsset("SKILL.md")
        if (skill == null) {
            sendResponse(writer, 503, """{"error":"skill assets not bundled in this build"}""")
            return
        }
        if (wantsHuman) {
            sendResponse(writer, 200, skill, "text/markdown; charset=utf-8")
            return
        }
        val py = readSkillAsset("examples/minis_rpc_android.py") ?: ""
        val curl = readSkillAsset("examples/curl.md") ?: ""
        val payload = org.json.JSONObject().apply {
            put("skill", skill)
            put("clients", org.json.JSONObject().apply {
                put("python", py)
                put("curl", curl)
            })
            put("client_paths", org.json.JSONObject().apply {
                put("python", "/skill/examples/python")
                put("curl", "/skill/examples/curl")
            })
        }
        sendResponse(writer, 200, payload.toString())
    }

    private fun sendSkillAsset(writer: PrintWriter, assetPath: String, contentType: String) {
        val body = readSkillAsset(assetPath)
        if (body == null) {
            sendResponse(writer, 503, """{"error":"asset not bundled: $assetPath"}""")
            return
        }
        sendResponse(writer, 200, body, contentType)
    }

    /// Reads from the DEBUG-only asset source set staged by
    /// scripts/gen_debug_skill_android.sh. Absent in release (where this whole
    /// server never starts) and absent if the generator didn't run.
    private fun readSkillAsset(relPath: String): String? = try {
        context.assets.open("debug-skill/$relPath").bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    /**
     * [Wave3 #13] CORS grant is conditional: only the Android WebView /
     * file-scheme cases — an absent Origin header or the literal "null" —
     * receive `Access-Control-Allow-Origin`. Arbitrary http(s) origins (any
     * web page could previously read the RPC) get NO ACAO header, so a
     * browser blocks the response even when the token gate passed.
     * Non-browser clients (curl, adb, the CLI skill) do not enforce CORS
     * and are unaffected. X-Minis-Token stays the remote-auth barrier.
     */
    private fun corsAllowOriginHeader(origin: String?): String =
        if (origin == null || origin == "null") {
            "Access-Control-Allow-Origin: null\r\n"
        } else ""

    /** [T-debugserver-request-limits] The one answer to an oversized request
     *  head: 431 with the reason logged, connection closed right after. */
    private fun sendHeadTooLarge(writer: PrintWriter, origin: String?) {
        Log.w(
            TAG,
            "431 request head rejected: request line + headers exceed " +
                "$MAX_HEADER_BYTES bytes or $MAX_HEADER_COUNT header lines",
        )
        sendResponse(writer, 431, rpcHandler.errorJSON(-32700, "request head too large"), origin = origin)
    }

    private fun sendResponse(
        writer: PrintWriter,
        statusCode: Int,
        body: String,
        contentType: String = "application/json",
        origin: String? = null,
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 $statusCode $statusText\r\n")
        writer.print("Content-Type: $contentType\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Connection: close\r\n")
        writer.print(corsAllowOriginHeader(origin))
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, X-Minis-Token, Authorization\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    /**
     * [T-debugserver-request-limits] Raised by [HttpRequestReader.readLine]
     * the moment the request-head budget trips; the caller answers 431 and
     * closes. Only the request line and header block flow through readLine,
     * so a throw can never mean "the body was too large".
     */
    private class RequestHeadTooLarge(message: String) : java.io.IOException(message)

    /**
     * Minimal byte-level HTTP request reader.
     *
     * Exists because the header block and the body need DIFFERENT treatment on
     * the same socket: headers are ASCII lines, the body is a Content-Length
     * count of BYTES. A BufferedReader cannot serve both — it decodes to
     * chars (so the body loop counted the wrong unit for any multi-byte UTF-8)
     * and it read ahead, so body bytes could already sit in its buffer and be
     * lost to a byte-level read. This reads one byte at a time for header lines
     * and hands the still-undrained stream to the body read.
     *
     * [T-debugserver-request-limits] readLine also OWNS the request-head
     * budget ([MAX_HEADER_BYTES] bytes across the request line + headers,
     * [MAX_HEADER_COUNT] header lines — the request line and the empty
     * terminator do not count against the line cap, matching nginx's
     * large_client_header_buffers semantics): it throws
     * [RequestHeadTooLarge] mid-line, so a single oversized line is cut off
     * at the cap instead of buffering unbounded bytes before any caller-side
     * check could run.
     */
    private class HttpRequestReader(private val input: java.io.InputStream) {
        private val buf = java.io.ByteArrayOutputStream(128)

        /** Bytes consumed from the request head so far (all readLine data). */
        private var headBytes = 0

        /** Header lines completed so far (request line and terminator excluded). */
        private var headerLines = 0

        /** The first [readLine] call delivers the request line, not a header. */
        private var sawRequestLine = false

        /** Reads one CRLF/LF-terminated line as ASCII; null at end of stream. */
        fun readLine(): String? {
            buf.reset()
            var sawAny = false
            while (true) {
                val b = input.read()
                if (b < 0) return if (sawAny) buf.toString("UTF-8") else null
                sawAny = true
                if (b == '\n'.code) {
                    val line = buf.toString("UTF-8")
                    // Only real header lines count against MAX_HEADER_COUNT:
                    // an empty line is the head terminator and the first line
                    // is the request line — neither is a header, and counting
                    // them would refuse two headers earlier than the cap
                    // claims. readLine is never called past the terminator
                    // (the body goes through [read]), so this stays exact.
                    if (sawRequestLine && line.isNotEmpty()) {
                        headerLines++
                        if (headerLines > MAX_HEADER_COUNT) {
                            throw RequestHeadTooLarge(
                                "request head exceeds $MAX_HEADER_COUNT header lines"
                            )
                        }
                    }
                    sawRequestLine = true
                    return line
                }
                headBytes++
                if (headBytes > MAX_HEADER_BYTES) {
                    throw RequestHeadTooLarge("request head exceeds $MAX_HEADER_BYTES bytes")
                }
                if (b != '\r'.code) buf.write(b)
            }
        }

        /** Reads exactly [len] BYTES into [dst], returning how many arrived. */
        fun read(dst: ByteArray, off: Int, len: Int): Int = input.read(dst, off, len)
    }

    private fun sendCorsPreflightResponse(writer: PrintWriter, origin: String? = null) {
        writer.print("HTTP/1.1 204 No Content\r\n")
        // [Wave3 #13] Same conditional grant as sendResponse — see the
        // KDoc there. A non-null http(s) origin is refused preflight.
        writer.print(corsAllowOriginHeader(origin))
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, X-Minis-Token, Authorization\r\n")
        writer.print("Access-Control-Max-Age: 86400\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
    }
}
