// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.network

import android.webkit.HttpAuthHandler
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.openminis.app.logging.AppLogger
import java.util.concurrent.Executor

/**
 * [T-net-android-phase3] The "Browser" scope for the app's WebViews, via
 * androidx.webkit ProxyController - the counterpart of iOS setting
 * WKWebsiteDataStore.proxyConfigurations.
 *
 * The override is process-wide (every WebView in the app), as iOS's shared
 * data store is. It points at the local entry's HTTP front: Chromium cannot
 * authenticate to a SOCKS proxy, but answers an HTTP proxy's 407 through
 * WebViewClient.onReceivedHttpAuthRequest, which every WebViewClient in the
 * app forwards to [answerAuth]. Loopback is never proxied.
 *
 * Zero regression: ProxyController is not touched at all unless a route
 * needs it, and the override is cleared the moment it no longer does
 * (route back to direct, entry down, Restore Direct Connections). A WebView
 * without PROXY_OVERRIDE support stays direct and Settings says so.
 */
object WebViewNetworkProxy {

    private const val TAG = "Network"
    /** realm the entry's HTTP front sends in its challenge (nethub/httpentry.go). */
    private const val REALM = "Minis"

    private val direct = Executor { it.run() }

    @Volatile
    private var applied: String? = null

    /** Credentials for the current override, read by [answerAuth]. */
    @Volatile
    private var credentials: Pair<String, String>? = null

    fun isSupported(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false)

    /** Called on every runtime publish (NetworkHub registers it). */
    fun apply(snapshot: NetworkRuntime.Snapshot) {
        val ep = snapshot.endpoint(NetworkScope.BROWSER.tag)
        val hp = ep?.httpPort
        val want = hp?.let { "127.0.0.1:$it" }
        if (want == applied) {
            credentials = if (ep != null && hp != null) ep.tag to ep.password else null
            return
        }
        if (!isSupported()) {
            if (want != null) AppLogger.warning(TAG, "[Net][Browser] this WebView has no PROXY_OVERRIDE: the browser stays direct")
            return
        }
        try {
            if (want == null) {
                credentials = null
                ProxyController.getInstance().clearProxyOverride(direct) {}
                AppLogger.info(TAG, "[Net][Browser] WebView -> direct (override cleared)")
            } else {
                credentials = ep!!.tag to ep.password
                val config = ProxyConfig.Builder()
                    .addProxyRule("http://$want")
                    .addBypassRule("localhost")
                    .addBypassRule("127.0.0.1")
                    .addBypassRule("[::1]")
                    .build()
                ProxyController.getInstance().setProxyOverride(config, direct) {}
                AppLogger.info(TAG, "[Net][Browser] WebView -> local entry $want (tag=${ep.tag})")
            }
            applied = want
        } catch (t: Throwable) {
            // Never let a WebView quirk take the app down; the browser then
            // keeps whatever it had (direct, if nothing was ever set).
            AppLogger.error(TAG, "[Net][Browser] proxy override failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * For WebViewClient.onReceivedHttpAuthRequest: answers the local entry's
     * proxy challenge and returns true; false for any other site's auth
     * request, which the caller handles as before.
     *
     * [FIX-R2-8, review §三 WebView 407 / §6.2] Upstream accepts ANY loopback
     * authority here (bare "127.0.0.1", "localhost", every "127.0.0.1:*"
     * prefix). A local app can serve its own 407 with realm "Minis" on an
     * arbitrary port and a remote page can reach it over a bypassed loopback
     * fetch (loopback is never proxied), so the prefix match handed that
     * server the entry's per-process password. Tighten the with-port case to
     * the exact authority the proxy override points at ([applied], i.e.
     * "127.0.0.1:$httpPort"); the bare-host accepts stay because Chromium
     * delivers the entry's own proxy challenge as a bare host name, which no
     * authority string can be distinguished from when the port is not
     * reported - the page-loopback guard below remains the second gate.
     */
    fun answerAuth(handler: HttpAuthHandler, host: String?, realm: String?, pageUrl: String?): Boolean {
        val c = credentials ?: return false
        if (realm != REALM) return false
        val authority = applied
        if (host != null && host != authority && host != "127.0.0.1" && host != "localhost") return false
        // Loopback pages bypass the proxy, so a challenge while one is shown
        // comes from a local server, not the entry: never hand it the password.
        val pageHost = pageUrl?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
        if (pageHost == "127.0.0.1" || pageHost == "localhost" || pageHost == "::1") return false
        handler.proceed(c.first, c.second)
        return true
    }
}
