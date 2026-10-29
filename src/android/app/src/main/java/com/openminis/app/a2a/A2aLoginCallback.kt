package com.openminis.app.a2a

import java.net.URI

/**
 * [T-android-a2a-login-capture] Callbacks only a shell can deliver.
 *
 * The gateway's web panel assumes the browser runs on the same machine as
 * the gateway: either the redirect lands on the gateway's own loopback
 * port, or an embedded window intercepts a custom scheme the desktop shell
 * registered. Inside this app both assumptions break for two providers, and
 * the failure is silent — the user finishes signing in, the provider shows
 * success, and the gateway waits forever because nobody handed it the code.
 * Upstream's desktop shell rewrites exactly these two shapes
 * (`is_login_callback` / `autoclaw_callback_forward` in login.rs); this is
 * the same layer for the in-app login window.
 *
 *  - `office-raccoon://auth/callback?...` — the raccoon authorization
 *    page's deep link. Nothing on the phone handles that scheme, so
 *    dispatching it to the system drops the code. The gateway serves the
 *    same query on its public `GET /api/session/login/raccoon-callback`
 *    route (state is one-time and validated there).
 *  - `http://localhost:<registered>/auth/callback-{zai,google}` — AutoClaw
 *    registers these ports with its OAuth service. On a desktop the
 *    official client may own the port; here nothing listens, so the
 *    WebView dead-ends on ERR_CONNECTION_REFUSED. The gateway serves the
 *    same paths publicly.
 *
 * Everything else is deliberately left alone: Trae/Accio/Antigravity
 * redirects point at listeners the gateway itself opened, so loading them
 * in place is exactly right, and rewriting an arbitrary loopback port would
 * steal the callback from that listener.
 */
internal object A2aLoginCallback {

    private const val RACCOON_SCHEME = "office-raccoon"
    private const val RACCOON_HOST = "auth"
    private const val RACCOON_PATH = "/callback"

    /** Upstream `providers::autoclaw::callback_server::REGISTERED_CALLBACK_PORTS` (v3.0.8). */
    internal val AUTOCLAW_PORTS = setOf(18432, 19654, 19723, 53699)

    private val AUTOCLAW_PATHS = setOf("/auth/callback-zai", "/auth/callback-google")

    /**
     * The gateway URL [url] must be navigated to instead, or null when the
     * URL is not a shell-delivered callback (load it unchanged).
     */
    fun rewrite(url: String?, gatewayPort: Int): String? {
        if (url.isNullOrBlank() || gatewayPort <= 0) return null
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        val host = parsed.host?.lowercase()
        val path = parsed.path.orEmpty()

        if (scheme == RACCOON_SCHEME) {
            if (host != RACCOON_HOST || path != RACCOON_PATH) return null
            val query = parsed.rawQuery ?: return null
            // The route refuses a callback without a task state; rewriting an
            // empty one would only land the user on the gateway's error page.
            if (!hasParam(query, "state")) return null
            return "http://127.0.0.1:$gatewayPort/api/session/login/raccoon-callback?$query"
        }

        if (scheme != "http") return null
        if (host != "localhost" && host != "127.0.0.1") return null
        if (parsed.port == gatewayPort || parsed.port !in AUTOCLAW_PORTS) return null
        if (path !in AUTOCLAW_PATHS) return null
        val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
        return "http://127.0.0.1:$gatewayPort$path$query"
    }

    private fun hasParam(query: String, name: String): Boolean =
        query.split('&').any { pair ->
            pair.substringBefore('=') == name && pair.substringAfter('=', "").isNotEmpty()
        }
}
