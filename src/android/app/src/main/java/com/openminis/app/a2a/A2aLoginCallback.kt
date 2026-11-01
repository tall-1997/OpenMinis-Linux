package com.openminis.app.a2a

import java.net.URI

/**
 * [T-android-a2a-login-capture] Callbacks only a shell can deliver.
 *
 * The gateway's web panel assumes the browser runs on the same machine as
 * the gateway: either the redirect lands on the gateway's own loopback
 * panel port, or an embedded window intercepts a custom scheme the desktop
 * shell registered. Inside this app both assumptions break for one
 * provider family, and the failure is silent — the user finishes signing
 * in, the provider shows success, and the gateway waits forever because
 * nobody handed it the code.
 *
 * The panel is served on its own port (the panel router, not the API
 * router — the v3.3 split ships two listeners), so every callback URL the
 * web shim builds off `window.location.origin` lands on the PANEL port.
 * That shape (GET `/api/session/login/remote-callback`) is handled by the
 * panel router itself and is deliberately left alone.
 *
 * What still needs a rewrite:
 *
 *  - `http://localhost:<registered>/auth/callback-{zai,google}` — AutoClaw
 *    registered these ports with its OAuth service in the 3.0.x lineage.
 *    On a desktop the official client may own the port; here nothing
 *    listens, so the WebView dead-ends on ERR_CONNECTION_REFUSED. The
 *    gateway serves the same paths publicly, so the load is pointed there.
 *    The 3.3 panel has moved AutoClaw to an SMS flow, but the pack an app
 *    upgrades from may still be the older lineage — the rewrite stays as a
 *    compatibility net; on packs without the route it never matches.
 *
 *  - `office-raccoon://auth/callback?...` — the raccoon authorization
 *    page's deep link. Nothing on the phone handles that scheme. The v3.3
 *    web shim intercepts this itself (it builds the callback off the panel
 *    origin and POSTs it), so there is nothing to rewrite anymore; an old
 *    pack whose shim does NOT intercept it dead-ends visibly in the login
 *    window instead of being silently pointed at a route that no longer
 *    exists.
 *
 * Everything else is deliberately left alone: Trae/Accio/Antigravity
 * redirects point at listeners the gateway itself opened, so loading them
 * in place is exactly right, and rewriting an arbitrary loopback port would
 * steal the callback from that listener.
 */
internal object A2aLoginCallback {

    /** Upstream `providers::autoclaw::callback_server::REGISTERED_CALLBACK_PORTS` (3.0.x). */
    internal val AUTOCLAW_PORTS = setOf(18432, 19654, 19723, 53699)

    private val AUTOCLAW_PATHS = setOf("/auth/callback-zai", "/auth/callback-google")

    /**
     * The panel URL [url] must be navigated to instead, or null when the
     * URL is not a shell-delivered callback (load it unchanged). [panelPort]
     * is the panel router's port — the port whose own callbacks are
     * correctly served in place and therefore never rewritten.
     */
    fun rewrite(url: String?, panelPort: Int): String? {
        if (url.isNullOrBlank() || panelPort <= 0) return null
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        val host = parsed.host?.lowercase()
        val path = parsed.path.orEmpty()

        if (scheme != "http") return null
        if (host != "localhost" && host != "127.0.0.1") return null
        if (parsed.port == panelPort || parsed.port !in AUTOCLAW_PORTS) return null
        if (path !in AUTOCLAW_PATHS) return null
        val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
        return "http://127.0.0.1:$panelPort$path$query"
    }

    /**
     * True when [url] is a callback landing on the PANEL's loopback port —
     * the moment the engine received the code, i.e. the login round has
     * nothing left to keep. Used to retire the round's throwaway browser
     * profile (and, on the fallback path, to drop the provider's storage).
     * Non-callback loopback pages (the panel itself, its login gate) are
     * excluded by the path check.
     */
    fun isCaptureCallback(url: String?, panelPort: Int): Boolean {
        if (url.isNullOrBlank() || panelPort <= 0) return false
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (!isLoopbackHost(parsed.host)) return false
        if (parsed.port != panelPort) return false
        return parsed.path.orEmpty().contains("callback", ignoreCase = true)
    }

    private fun isLoopbackHost(host: String?): Boolean {
        val value = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        return value == "127.0.0.1" || value == "localhost" || value == "::1"
    }
}
