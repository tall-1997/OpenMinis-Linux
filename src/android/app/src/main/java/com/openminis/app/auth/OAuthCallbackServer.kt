package com.openminis.app.auth

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URI

class OAuthCallbackServer(
    private val port: Int,
    private val fallbackPorts: List<Int> = emptyList(),
    private val expectedPath: String = "/callback",
    private val onCode: (code: String, state: String?) -> Unit,
    // [T-android-mcp-oauth-timeout] Invoked when the IdP redirects back with
    // ?error=… (consent denied) so the waiter fails fast instead of hanging.
    // Optional + default null: the Claude flow keeps its existing behavior.
    private val onDenied: (() -> Unit)? = null,
) {
    companion object {
        private const val TAG = "OAuthCallbackServer"
        private const val MAX_REQUEST_LINE = 4096
        private const val MAX_RESPONSE_BYTES = 1024

        /**
         * [T-oauth-loopback-binding] RFC 8252 §7.3: a native-app loopback
         * listener must reject requests whose `Host` is not a loopback name.
         * Without it, anything on the LAN that can route to this device's
         * listening socket could probe the callback port (and, before the bind
         * fix below, actually reach it) — probing for a live OAuth listener and
         * interference with the redirect are the exact things §7.3 exists to stop.
         * PKCE + state still decide code theft; this closes the extra surface.
         *
         * Pure + internal so the unit test can pin the table without a socket.
         */
        internal fun isLoopbackHostHeader(header: String?): Boolean {
            val raw = header?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
            // Tolerate an absolute-form authority ("http://localhost:54545") and
            // strip the port. IPv6 literals keep their brackets ("[::1]:1234").
            val authority = raw.substringAfterLast("://").substringBefore('#').substringBefore('/')
            val host = if (authority.startsWith("[")) {
                authority.substringBefore(']').ifEmpty { return false } + "]"
            } else {
                authority.substringBefore(':')
            }
            if (host.isEmpty()) return false
            if (host == "localhost" || host == "::1" || host == "[::1]") return true
            // The whole 127/8 block is loopback, not just 127.0.0.1 — the xAI
            // redirect URI registers 127.0.0.1 explicitly, so that spelling has
            // to pass.
            val parts = host.split('.')
            return parts.size == 4 && parts.firstOrNull() == "127" &&
                parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }

        // ── [T-oauth-return-to-app] Which ports are waiting / already answered ──
        // [port-upstream-1.16] Ported from upstream OAuthCallbackServer so
        // OAuthRedirectActivity can tell a duplicate hand-over from a live one.

        private class Served(val at: Long, val state: String?)

        /** Ports a server is listening on right now, by instance. */
        private val listening = java.util.concurrent.ConcurrentHashMap<Int, OAuthCallbackServer>()

        /** Last callback each port answered. The state stays in memory, for equality only; never logged. */
        private val served = java.util.concurrent.ConcurrentHashMap<Int, Served>()

        /** How long an answered callback makes a matching redirect a duplicate. */
        private const val SERVED_WINDOW_MS = 10 * 60_000L

        /**
         * Pure decision core: what OAuthRedirectActivity should do with a
         * redirect the browser handed to the app. Chrome both requests
         * localhost and hands the same URL to our intent-filter (field log:
         * served at 42.000, handed over at 42.001); forwarding a callback the
         * server already answered only meets a closed port. It is still
         * forwarded when the server is waiting and has not answered it.
         */
        internal fun forwardDecision(
            listeningNow: Boolean,
            servedAt: Long?,
            servedState: String?,
            redirectState: String?,
            now: Long,
        ): Forward {
            val recent = servedAt != null && now - servedAt in 0..SERVED_WINDOW_MS
            val sameCallback = servedState == null || redirectState == null || servedState == redirectState
            return when {
                recent && sameCallback -> Forward.ALREADY_SERVED
                listeningNow -> Forward.FORWARD
                else -> Forward.NOT_LISTENING
            }
        }

        fun forwardDecision(port: Int, redirectState: String?): Forward {
            val s = served[port]
            return forwardDecision(
                listening.containsKey(port), s?.at, s?.state, redirectState,
                android.os.SystemClock.uptimeMillis(),
            )
        }
    }

    /** [T-oauth-return-to-app] What OAuthRedirectActivity does with a handed-over redirect. */
    enum class Forward { FORWARD, ALREADY_SERVED, NOT_LISTENING }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    /** The port actually bound (may differ from [port] if fallback was used). */
    var boundPort: Int = port
        private set

    /**
     * Invoked by [stop] when the server is shut down by an external
     * caller (e.g. the user dismisses Chrome Custom Tab and the auth
     * manager wants to abort the in-flight wait without waiting for the
     * 5-minute timeout). The callback is _only_ fired when stop() is
     * called by something other than the success path —
     * [onCode] callers set this to null before calling stop() so they
     * don't fire a cancel after a successful redirect.
     *
     * [T-xai-oauth-stop-resume, port iOS d1dbdd5d]
     */
    @Volatile var onExternalCancel: (() -> Unit)? = null

    /**
     * [T-oauth-bind-failed] Called when EVERY port (primary + fallbacks) failed
     * to bind. Without it the login coroutine waits on the callback forever
     * while the browser redirect lands on a dead port — resume with a failure.
     */
    @Volatile var onBindFailed: (() -> Unit)? = null

    fun start() {
        running = true
        Thread {
            try {
                val portsToTry = listOf(port) + fallbackPorts
                var bound = false
                for (p in portsToTry) {
                    try {
                        // [T-oauth-loopback-binding] Bind EXPLICITLY to 127.0.0.1.
                        // `ServerSocket(p)` binds the wildcard address (0.0.0.0),
                        // which put the OAuth callback listener on every interface:
                        // a device on the same Wi-Fi/lan could reach the port, probe
                        // it, and race the real browser for the redirect. Every other
                        // loopback server in this app already binds this way
                        // (McpHttpServer, SandboxHttpProxy) — this was the outlier.
                        serverSocket = ServerSocket(p, 10, java.net.InetAddress.getByName("127.0.0.1"))
                        boundPort = p
                        bound = true
                        break
                    } catch (e: java.net.BindException) {
                        Log.w(TAG, "Port $p in use, trying next...")
                    }
                }
                if (!bound) {
                    Log.e(TAG, "All ports unavailable: $portsToTry")
                    onBindFailed?.invoke()
                    return@Thread
                }
                Log.d(TAG, "Listening on port $boundPort")
                // A new flow on this port: the previous flow's answer is not this one's.
                served.remove(boundPort)
                listening[boundPort] = this
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    try {
                        socket.soTimeout = 5000
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val requestLine = reader.readLine() ?: continue
                        if (requestLine.length > MAX_REQUEST_LINE) { writeResponse(socket, 414, "Request URI too long"); continue }
                        val parts = requestLine.split(' ')
                        if (parts.size != 3 || parts[2] != "HTTP/1.1") { writeResponse(socket, 400, "Bad request"); continue }
                        if (parts[0] != "GET") { writeResponse(socket, 405, "Method not allowed"); continue }
                        val requestTarget = parts[1]
                        var headerBytes = 0
                        // [T-oauth-loopback-binding] Capture `Host` while draining
                        // the headers (they used to be read and thrown away, so the
                        // check below had nothing to look at).
                        var hostHeader: String? = null
                        while (true) {
                            val header = reader.readLine() ?: break
                            headerBytes += header.length
                            if (header.isEmpty() || headerBytes > MAX_REQUEST_LINE) break
                            if (hostHeader == null && header.length > 5 &&
                                header.substring(0, 5).equals("host:", ignoreCase = true)
                            ) {
                                hostHeader = header.substring(5).trim()
                            }
                        }
                        if (headerBytes > MAX_REQUEST_LINE) { writeResponse(socket, 431, "Headers too large"); continue }
                        // [T-oauth-loopback-binding] RFC 8252 §7.3 — reject a Host
                        // that is not loopback before touching the auth code, so a
                        // peer on the LAN (or a misbehaving proxy that rewrote the
                        // request) cannot consume or probe this callback.
                        if (!isLoopbackHostHeader(hostHeader)) {
                            Log.w(TAG, "Rejected callback request with non-loopback Host: '${hostHeader ?: "<absent>"}'")
                            writeResponse(socket, 400, "Bad request")
                            continue
                        }

                        // Parse only the exact loopback callback path.
                        val uri = URI("http://localhost$requestTarget")
                            if (uri.rawPath != expectedPath || uri.rawFragment != null || (uri.rawQuery?.length ?: 0) > MAX_REQUEST_LINE) {
                                writeResponse(socket, 404, "Not found")
                                continue
                            }
                            val params = uri.query?.split("&")?.associate {
                                val kv = it.split("=", limit = 2)
                                kv[0] to (if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else "")
                            } ?: emptyMap()

                            val code = params["code"]
                            val state = params["state"]

                            // [T-oauth-return-to-app] Record BEFORE the write:
                            // the browser can hand the same URL to
                            // OAuthRedirectActivity the moment it has the response.
                            served[boundPort] = Served(android.os.SystemClock.uptimeMillis(), state)
                            // Send response
                            val html = "<html><body><h1>Authorization complete</h1><p>You can close this tab.</p><script>window.close()</script></body></html>"
                            writeResponse(socket, 200, html)
                            Log.i(TAG, "OAuth callback accepted: codePresent=${!code.isNullOrEmpty()} statePresent=${!state.isNullOrEmpty()}")

                            val err = params["error"]
                            if (!code.isNullOrEmpty()) {
                                onCode(code, state)
                            } else if (!err.isNullOrEmpty()) {
                                Log.i(TAG, "OAuth callback denied: $err")
                                served[boundPort] = Served(android.os.SystemClock.uptimeMillis(), null)
                                onDenied?.invoke()
                                stop()
                                return@Thread
                            }
                        socket.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error handling connection", e)
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error", e)
            } finally {
                // An old server's late exit must not unregister its successor on the port.
                listening.remove(boundPort, this)
            }
        }.start()
    }

    private fun writeResponse(socket: java.net.Socket, status: Int, body: String) {
        val safeBody = body.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_RESPONSE_BYTES) it.copyOf(MAX_RESPONSE_BYTES) else it }
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 414 -> "URI Too Long"; 431 -> "Request Header Fields Too Large"; else -> "Error" }
        val response = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${safeBody.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().use { it.write(response.toByteArray()); it.write(safeBody) }
        socket.close()
    }

    fun stop() {
        val wasRunning = running
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        // Notify external callers (e.g. XAIOAuthManager) so they can
        // cancel an in-flight suspendCancellableCoroutine instead of
        // hanging until the next inbound connection / the 5-min
        // OAuth wait timeout fires. Consume the callback (set to
        // null before invoking) so re-entrant stop() calls don't
        // double-fire (T-xai-oauth-stop-resume).
        if (wasRunning) {
            val cancel = onExternalCancel
            onExternalCancel = null
            try { cancel?.invoke() } catch (e: Exception) {
                Log.w(TAG, "onExternalCancel threw: ${e.message}")
            }
        }
    }
}
