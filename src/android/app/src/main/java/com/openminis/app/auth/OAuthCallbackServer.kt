package com.openminis.app.auth

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URI

/**
 * Loopback server for an OAuth redirect. The flow ends EXACTLY ONCE, through
 * [finish], with one of:
 *  - [onCode]: the redirect carried a `code`;
 *  - [onError]: the redirect carried `error` (e.g. `?error=access_denied`, the
 *    user pressed Deny) - the browser gets an "Authorization Failed" page;
 *  - [onExternalCancel]: [stop] ran before either of the above.
 *
 * [T-oauth-callback-double-resume] Port of iOS 401ff748c. An error redirect
 * used to be ignored (the loop went back to accept()), so a denied sign-in sat
 * in "signing in" until the user gave up; and the success path, the error path
 * and stop() each resumed the caller's continuation on their own, guarded
 * only by a check-then-resume `cont.isActive`, which two threads can both pass.
 */
class OAuthCallbackServer(
    private val port: Int,
    private val fallbackPorts: List<Int> = emptyList(),
    private val onError: (OAuthCallbackError) -> Unit,
    private val onCode: (code: String, state: String?) -> Unit,
) {
    companion object {
        private const val TAG = "OAuthCallbackServer"

        /**
         * Classify a request line: a redirect with a non-empty `code`, one with
         * `error` (+ optional `error_description`), or anything else (favicon,
         * probes), which is answered 404 and does not end the flow.
         */
        internal fun parseRequest(requestLine: String): CallbackRequest {
            val parts = requestLine.split(" ")
            if (parts.size < 2) return CallbackRequest.Other
            val params = try {
                URI("http://localhost${parts[1]}").rawQuery?.split("&")?.filter { it.isNotEmpty() }?.associate {
                    val kv = it.split("=", limit = 2)
                    decode(kv[0]) to (if (kv.size > 1) decode(kv[1]) else "")
                } ?: emptyMap()
            } catch (e: Exception) {
                return CallbackRequest.Other
            }
            val code = params["code"]
            if (!code.isNullOrEmpty()) return CallbackRequest.Code(code, params["state"])
            val error = params["error"]
            if (error != null) {
                return CallbackRequest.Error(
                    OAuthCallbackError(
                        error = error.ifEmpty { "unknown_error" },
                        description = params["error_description"]?.takeIf { it.isNotBlank() },
                    ),
                )
            }
            return CallbackRequest.Other
        }

        private fun decode(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")

        /**
         * iOS sendErrorPage: "Authorization Failed" + the `error` value, HTTP 400.
         * [T-oauth-return-to-app] With [packageName], the same "Return to
         * Minis Ultra" link as [successPage]: the app already has the answer, and
         * without it the user had to close the tab by hand (MCP A3 device
         * test, bug 2).
         */
        internal fun errorPage(error: String, packageName: String? = null): String {
            val back = packageName?.let {
                "<p>Return to Minis Ultra to continue.</p><p><a href=\"${returnToAppUrl(it)}\" style=\"display:inline-block;" +
                    "padding:12px 24px;border-radius:8px;background:#10a37f;color:#fff;text-decoration:none;" +
                    "font-size:18px\">Return to Minis Ultra</a></p>"
            }.orEmpty()
            val html = "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>" +
                "<body style=\"font-family:sans-serif;text-align:center;padding:24px\">" +
                "<h1>Authorization Failed</h1><p>${htmlEscape(error)}</p>$back</body></html>"
            return "HTTP/1.1 400 Bad Request\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$html"
        }

        private fun htmlEscape(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        /**
         * [T-oauth-return-to-app] The page served for a code. It still says
         * to return to Minis Ultra (the exchange runs in the app), and now has a
         * link that does it: an intent:// URL for MainActivity's minis://
         * filter. MainActivity is singleTask and the Custom Tab sits above it
         * in the same task, so opening it clears the tab. `window.close()`
         * cannot close a Custom Tab; it stays for plain browsers. No auto-
         * redirect - Chrome blocks scheme navigations without a gesture.
         * ASCII only: the Content-Length is computed from String.length.
         */
        internal fun successPage(packageName: String): String =
            "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>" +
                "<body style=\"font-family:sans-serif;text-align:center;padding:24px\">" +
                "<h1>Authorization received</h1><p>Return to Minis Ultra to finish signing in.</p>" +
                "<p><a href=\"${returnToAppUrl(packageName)}\" style=\"display:inline-block;padding:12px 24px;" +
                "border-radius:8px;background:#10a37f;color:#fff;text-decoration:none;font-size:18px\">Return to Minis Ultra</a></p>" +
                "<script>window.close()</script></body></html>"

        /** `minis://oauth/return` as an intent URL pinned to our package; the deep link itself is a no-op. */
        internal fun returnToAppUrl(packageName: String): String =
            "intent://oauth/return#Intent;scheme=minis;package=$packageName;end"

        // ── [T-oauth-return-to-app] Which ports are waiting / already answered ──

        private class Served(val at: Long, val state: String?)

        /**
         * Ports a server is listening on right now, by instance: an old
         * server's late exit must not unregister its successor on the port.
         */
        private val listening = java.util.concurrent.ConcurrentHashMap<Int, OAuthCallbackServer>()

        /** Last callback each port answered. The state stays in memory, for equality only; never logged. */
        private val served = java.util.concurrent.ConcurrentHashMap<Int, Served>()

        /** How long an answered callback makes a matching redirect a duplicate. */
        internal const val SERVED_WINDOW_MS = 10 * 60_000L

        /**
         * Pure: what OAuthRedirectActivity should do with a redirect the
         * browser handed to the app. Chrome both requests localhost and hands
         * the same URL to our intent-filter (field log: served at 42.000,
         * handed over at 42.001); forwarding a callback the server already
         * answered only meets a closed port. It is still forwarded when the
         * server is waiting and has not answered it - Chrome aborting before
         * its request reached the server would otherwise lose the code.
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
            return forwardDecision(listening.containsKey(port), s?.at, s?.state, redirectState, android.os.SystemClock.uptimeMillis())
        }
    }

    /** [T-oauth-return-to-app] What OAuthRedirectActivity does with a handed-over redirect. */
    enum class Forward { FORWARD, ALREADY_SERVED, NOT_LISTENING }

    internal sealed class CallbackRequest {
        data class Code(val code: String, val state: String?) : CallbackRequest()
        data class Error(val error: OAuthCallbackError) : CallbackRequest()
        object Other : CallbackRequest()
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    /** Set by the first of success / error / stop; everything later is a no-op. */
    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The one exit: true only for the first caller, which then delivers its
     * outcome. Every later caller (a late stop(), a second redirect) gets false.
     */
    private fun finish(): Boolean {
        if (!finished.compareAndSet(false, true)) return false
        running = false
        return true
    }

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
     * [T-oauth-wait-fgs] This flow's entry in [OAuthWaitKeepAlive] while it
     * waits, so the process is not frozen with the browser on top (the
     * redirect then hung until the next thaw). Released on every exit -
     * callback, error callback, stop(), a failed bind, a crashed accept loop -
     * exactly once; the keep-alive's own deadline covers a flow that never
     * reaches any of them.
     */
    private val keepAlive = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /**
     * Release [token], or whatever this server holds when null. The accept
     * thread passes its own token: after a stop() + start() on the same
     * instance, the old thread's late exit must not release the new flow's.
     */
    private fun releaseKeepAlive(reason: String, token: String? = null) {
        val id = if (token == null) keepAlive.getAndSet(null)
        else token.takeIf { keepAlive.compareAndSet(it, null) }
        id?.let { com.openminis.app.service.OAuthWaitKeepAlive.end(it, "$reason port=$boundPort") }
    }

    fun start() {
        running = true
        // Before the browser opens (every caller starts the server right
        // before launching it, from the user's tap): the app is still in the
        // foreground, where starting a foreground service is allowed.
        val token = com.openminis.app.service.OAuthWaitKeepAlive.begin("oauth-callback port=$port")
        keepAlive.getAndSet(token)
            ?.let { com.openminis.app.service.OAuthWaitKeepAlive.end(it, "restarted port=$port") }
        Thread {
            try {
                val portsToTry = listOf(port) + fallbackPorts
                var bound = false
                for (p in portsToTry) {
                    try {
                        serverSocket = ServerSocket(p)
                        boundPort = p
                        bound = true
                        break
                    } catch (e: java.net.BindException) {
                        Log.w(TAG, "Port $p in use, trying next...")
                    }
                }
                if (!bound) {
                    Log.e(TAG, "All ports unavailable: $portsToTry")
                    releaseKeepAlive("bind-failed", token)
                    return@Thread
                }
                Log.d(TAG, "Listening on port $boundPort")
                // A new flow on this port: the previous flow's answer is not this one's.
                served.remove(boundPort)
                listening[boundPort] = this
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    try {
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val requestLine = reader.readLine() ?: continue
                        Log.d(TAG, "Request: ${OAuthLogRedaction.url(requestLine)}") // [T-android-oauth-log-redact]

                        // CORS preflight for providers (e.g. xAI) that
                        // OPTIONS /callback from their authorization page
                        // before redirecting the browser. Without this we
                        // return a 404 to the preflight and the browser
                        // never follows the redirect — OAuth stalls.
                        // Read the rest of the headers to find Origin and
                        // only echo back permissive CORS for known xAI hosts.
                        if (requestLine.startsWith("OPTIONS")) {
                            var origin: String? = null
                            while (true) {
                                val h = reader.readLine() ?: break
                                if (h.isEmpty()) break
                                val lower = h.lowercase()
                                if (lower.startsWith("origin:")) {
                                    origin = h.substringAfter(":").trim()
                                }
                            }
                            val trustedHosts = listOf("auth.x.ai", "accounts.x.ai")
                            val allowOrigin = if (origin != null && trustedHosts.any { origin!!.contains(it) }) {
                                origin
                            } else {
                                "null"
                            }
                            val pre = "HTTP/1.1 204 No Content\r\n" +
                                "Access-Control-Allow-Origin: $allowOrigin\r\n" +
                                "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                                "Access-Control-Allow-Headers: *\r\n" +
                                "Access-Control-Max-Age: 600\r\n" +
                                "Connection: close\r\n\r\n"
                            socket.getOutputStream().write(pre.toByteArray())
                            socket.close()
                            continue
                        }

                        when (val req = parseRequest(requestLine)) {
                            is CallbackRequest.Code -> {
                                // [T-android-oauth-foreground-exchange] Not "complete": the app still has
                                // to exchange the code, and it can only do that once the user is
                                // back in Minis Ultra (background network is blocked). Say so, so the
                                // user returns rather than waiting on a tab that already "succeeded".
                                // ASCII only: Content-Length below is computed from String.length.
                                val html = successPage(com.openminis.app.BuildConfig.APPLICATION_ID)
                                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\nConnection: close\r\n\r\n$html"
                                // Before the write: the browser can hand the same URL to
                                // OAuthRedirectActivity the moment it has the response.
                                served[boundPort] = Served(android.os.SystemClock.uptimeMillis(), req.state)
                                socket.getOutputStream().write(response.toByteArray())
                                socket.close()
                                releaseKeepAlive("callback", token)
                                if (finish()) onCode(req.code, req.state)
                                stop()
                                return@Thread
                            }
                            is CallbackRequest.Error -> {
                                // [T-oauth-callback-double-resume] A denied / failed
                                // sign-in ends the flow now, with the provider's
                                // reason, instead of waiting for a code that is
                                // never coming.
                                Log.w(TAG, "Callback error: ${req.error.error} description=${req.error.description != null}")
                                served[boundPort] = Served(android.os.SystemClock.uptimeMillis(), null)
                                socket.getOutputStream().write(
                                    errorPage(req.error.error, com.openminis.app.BuildConfig.APPLICATION_ID).toByteArray(Charsets.UTF_8),
                                )
                                socket.close()
                                releaseKeepAlive("error-callback", token)
                                if (finish()) onError(req.error)
                                stop()
                                return@Thread
                            }
                            CallbackRequest.Other -> {
                                // Neither a code nor an error (favicon, a probe):
                                // not the redirect, so keep waiting for it.
                                socket.getOutputStream().write(
                                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                                )
                            }
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
                listening.remove(boundPort, this)
                // Nothing is listening any more, whichever way the loop ended.
                releaseKeepAlive("server-exit", token)
            }
        }.apply { name = com.openminis.app.diagnostics.ThreadNames.of("oauth-cb") }.start() // GH #453: per-name thread counts
    }

    fun stop() {
        releaseKeepAlive("stopped")
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        // Notify external callers (e.g. XAIOAuthManager) so they can
        // cancel an in-flight suspendCancellableCoroutine instead of
        // hanging until the next inbound connection / the 5-min
        // OAuth wait timeout fires (T-xai-oauth-stop-resume).
        // [T-oauth-callback-double-resume] Only when nothing has finished the
        // flow yet: after a code or an error, this stop() is the flow's own
        // cleanup, and a cancel then would resume the waiter a second time.
        if (finish()) {
            val cancel = onExternalCancel
            onExternalCancel = null
            try { cancel?.invoke() } catch (e: Exception) {
                Log.w(TAG, "onExternalCancel threw: ${e.message}")
            }
        }
    }
}

/**
 * [T-oauth-callback-double-resume] The provider redirected back with `error`
 * instead of a code: [error] is the OAuth error code (`access_denied`, ...),
 * [description] its optional human-readable `error_description`.
 */
data class OAuthCallbackError(val error: String, val description: String?) {
    /** What the user reads: the provider's own description when it sent one. */
    fun userMessage(context: android.content.Context): String = when {
        description != null -> context.getString(com.openminis.app.R.string.oauth_callback_error, description)
        error == "access_denied" -> context.getString(com.openminis.app.R.string.oauth_callback_access_denied)
        else -> context.getString(com.openminis.app.R.string.oauth_callback_error, error)
    }

    fun toException(context: android.content.Context): OAuthCallbackException =
        OAuthCallbackException(this, userMessage(context))
}

/** Thrown by a sign-in flow whose redirect carried an [OAuthCallbackError]. */
class OAuthCallbackException(val callbackError: OAuthCallbackError, message: String) : Exception(message)
