// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.network

import com.openminis.app.logging.AppLogger
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.WeakHashMap
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient

/**
 * [T-net-android-phase3] The one place OkHttp clients get their route from
 * Settings > Network - the counterpart of iOS `NetworkSessionFactory`.
 *
 * `client(tag, base)` returns [base] ITSELF (same object, same pool, same
 * everything) unless the route for [tag] needs the local entry and the entry
 * is listening; the unconfigured app therefore builds and sends exactly what
 * it did before. When a route applies, it returns `base.newBuilder()` with
 * one change: an HTTP proxy at the entry's HTTP front on 127.0.0.1, with the
 * tag as Basic username and the per-process password, sent preemptively on
 * CONNECT (OkHttp asks the proxy authenticator before tunnelling). Pool,
 * dispatcher, timeouts, interceptors, event listeners and TLS settings are
 * shared with [base].
 *
 * Why HTTP and not the SOCKS entry: Java takes SOCKS credentials only from a
 * process-wide java.net.Authenticator, so a per-tag username is impossible.
 * CONNECT keeps the host name, so DNS (and MagicDNS) is resolved by the route.
 *
 * Derived clients are cached per (base, tag) and rebuilt when the runtime
 * generation changes; callers simply call this on every request.
 */
object NetworkHttp {

    private const val TAG = "Network"

    /** The tag OkHttp requests carry when the caller has none. */
    const val DEFAULT_TAG = "default"

    private data class Derived(val generation: Int, val client: OkHttpClient)

    private val cache = WeakHashMap<OkHttpClient, MutableMap<String, Derived>>()

    fun client(tag: String?, base: OkHttpClient): OkHttpClient {
        val t = tag ?: DEFAULT_TAG
        val snap = NetworkRuntime.snapshot
        val ep = snap.endpoint(t) ?: return base
        val httpPort = ep.httpPort ?: return base
        synchronized(cache) {
            val perBase = cache.getOrPut(base) { HashMap() }
            perBase[t]?.let { if (it.generation == snap.generation) return it.client }
            val derived = base.newBuilder()
                .proxySelector(EntrySelector(httpPort, fallbackDirect = snap.route(t) == NetworkRoute.Direct))
                .proxyAuthenticator(entryAuthenticator(t, ep.password))
                .build()
            perBase[t] = Derived(snap.generation, derived)
            AppLogger.info(TAG, "[Net][Session] okhttp tag=$t -> local entry (gen ${snap.generation})")
            return derived
        }
    }

    /**
     * Answers OkHttp's preemptive challenge (and a real 407) with the entry
     * credentials, once: a second 407 means the password is stale (the entry
     * restarted), and retrying would loop.
     */
    internal fun entryAuthenticator(tag: String, password: String) = Authenticator { _, response ->
        if (response.request.header("Proxy-Authorization") != null) {
            AppLogger.warning(TAG, "[Net][Session] local entry rejected tag=$tag (stale password?)")
            null
        } else {
            response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(tag, password))
                .build()
        }
    }

    /**
     * The entry first, with a liveness check: OkHttp 4.12 tells a
     * ProxySelector about failures only on connections that were already
     * established, so a dead entry is noticed here instead - at most one
     * loopback connect per 2 s, only when OkHttp plans a NEW connection.
     * If the entry is gone (it died under us):
     *  - a direct route (through the entry only because Tailscale is on)
     *    goes direct, exactly the pre-module behaviour;
     *  - a proxy or Tailscale route does NOT silently go around the user's
     *    proxy / exit node: this request fails;
     * and either way the hub restarts the entry, so the next request is
     * routed again. An upstream proxy failure is a 502/407 from a live
     * entry, never turned into a direct connection here.
     */
    internal class EntrySelector(private val httpPort: Int, private val fallbackDirect: Boolean) : java.net.ProxySelector() {
        private val entry = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
        @Volatile private var checkedAt = 0L
        @Volatile private var alive = true

        private fun entryAlive(): Boolean {
            val now = System.nanoTime()
            if (now - checkedAt < 2_000_000_000L) return alive
            checkedAt = now
            alive = runCatching {
                java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", httpPort), 300) }
            }.isSuccess
            if (!alive) {
                AppLogger.warning(TAG, "[Net][Session] local entry 127.0.0.1:$httpPort is gone -> " +
                    (if (fallbackDirect) "direct route goes direct" else "proxy/tailscale route fails until it is back") + "; restarting it")
                NetworkHub.verifyEntry()
            }
            return alive
        }

        override fun select(uri: java.net.URI?): List<Proxy> = when {
            entryAlive() -> if (fallbackDirect) listOf(entry, Proxy.NO_PROXY) else listOf(entry)
            fallbackDirect -> listOf(Proxy.NO_PROXY)
            else -> listOf(entry)
        }

        override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {
            if ((sa as? InetSocketAddress)?.port == httpPort && ioe is java.net.ConnectException) {
                checkedAt = 0L
                NetworkHub.verifyEntry()
            }
        }
    }

    /**
     * [FEAT-261021-08] A client that follows [tag]'s route on EVERY request,
     * for a caller that keeps one OkHttpClient for its lifetime and cannot
     * call [client] per request (MCPOAuthController takes one at
     * construction). Each connection asks the current [NetworkRuntime]
     * snapshot; with no entry for the tag it answers exactly as [base] would
     * (base's proxy selector and authenticator, so a system proxy still
     * applies), so an unconfigured app is unchanged.
     */
    fun following(tag: String, base: OkHttpClient): OkHttpClient {
        val baseSelector = base.proxySelector
        val baseAuth = base.proxyAuthenticator
        return base.newBuilder()
            .proxySelector(FollowingSelector(tag, baseSelector))
            .proxyAuthenticator { route, response ->
                val ep = NetworkRuntime.snapshot.endpoint(tag)
                if (ep?.httpPort != null && route?.proxy?.type() == Proxy.Type.HTTP &&
                    (route.proxy.address() as? InetSocketAddress)?.port == ep.httpPort
                ) {
                    entryAuthenticator(tag, ep.password).authenticate(route, response)
                } else {
                    baseAuth.authenticate(route, response)
                }
            }
            .build()
    }

    internal class FollowingSelector(private val tag: String, private val base: java.net.ProxySelector) : java.net.ProxySelector() {
        @Volatile private var current: Pair<Int, EntrySelector>? = null

        /** The entry selector for the current generation, or null for "as base". */
        private fun entry(): EntrySelector? {
            val snap = NetworkRuntime.snapshot
            val ep = snap.endpoint(tag) ?: return null
            val httpPort = ep.httpPort ?: return null
            current?.let { (gen, sel) -> if (gen == snap.generation) return sel }
            return EntrySelector(httpPort, fallbackDirect = snap.route(tag) == NetworkRoute.Direct)
                .also { current = snap.generation to it }
        }

        override fun select(uri: java.net.URI?): List<Proxy> = entry()?.select(uri) ?: base.select(uri)

        override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {
            entry()?.connectFailed(uri, sa, ioe) ?: base.connectFailed(uri, sa, ioe)
        }
    }

    /** Tests only. */
    internal fun clearCacheForTest() = synchronized(cache) { cache.clear() }
}

/** A provider that knows its instance's policy tag (prov:<instanceId>). */
interface NetworkTagged {
    var netTag: String?
}
