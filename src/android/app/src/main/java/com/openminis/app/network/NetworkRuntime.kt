// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.network

import com.openminis.app.logging.AppLogger
import java.net.URLEncoder

/**
 * The local SOCKS5 entry as one client uses it: 127.0.0.1:[port], the
 * client's policy [tag] as username, the per-process [password].
 */
data class SocksEndpoint(
    val port: Int,
    val tag: String,
    val password: String,
    /** The entry's HTTP proxy front (OkHttp, WebView); null if it is not listening. */
    val httpPort: Int? = null,
) {
    val host: String get() = "127.0.0.1"

    /** socks5h://tag:password@127.0.0.1:port (remote DNS), for ALL_PROXY etc. */
    val url: String
        get() = "socks5h://${enc(tag)}:${enc(password)}@$host:$port"

    /**
     * http://tag:password@127.0.0.1:httpPort, for HTTP(S)_PROXY: clients
     * that cannot speak SOCKS (BusyBox wget, apk, Python urllib / pip).
     */
    val httpUrl: String?
        get() = httpPort?.let { "http://${enc(tag)}:${enc(password)}@$host:$it" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    override fun toString(): String = "SocksEndpoint(127.0.0.1:$port, tag=$tag)"
}

/**
 * A thread-safe snapshot of what clients need from Settings > Network,
 * mirroring iOS `NetworkRuntime`: whether the local entry listens, its
 * credentials, and which route each policy tag resolves to. [NetworkHub]
 * publishes it after every apply; OkHttp clients, the shell, WebView and
 * rclone read it on any thread.
 *
 * The rule that keeps an unconfigured app unchanged lives in [endpoint]:
 * no entry -> null -> the caller builds exactly what it built before the
 * Network module existed.
 */
object NetworkRuntime {

    private const val TAG = "Network"

    data class Snapshot(
        val port: Int? = null,
        /** HTTP proxy front of the same entry (OkHttp, WebView). */
        val httpPort: Int? = null,
        val password: String = "",
        val tailscaleEnabled: Boolean = false,
        val defaultRoute: String = "direct",
        /** tag -> raw route ("default" = follow defaultRoute) */
        val tagRoutes: Map<String, String> = emptyMap(),
        /** Bumped on every change; clients built for an older value are rebuilt. */
        val generation: Int = 0,
        /**
         * [FIX-261025-11] The entry is wanted (a proxy / Tailscale route or
         * Tailscale on) but is not listening: it failed to configure, or died
         * and is being restarted. See [endpoint].
         */
        val entryDown: Boolean = false,
    ) {
        /** The concrete route for [tag] (never FollowDefault). */
        fun route(tag: String): NetworkRoute {
            val r = NetworkRoute.parse(tagRoutes[tag] ?: "default")
            return if (r == NetworkRoute.FollowDefault) NetworkRoute.parse(defaultRoute).let {
                if (it == NetworkRoute.FollowDefault) NetworkRoute.Direct else it
            } else r
        }

        /**
         * The entry is used when the route is not plain direct, or when
         * Tailscale is on (tailnet destinations must be split off whatever
         * the route). Otherwise - and whenever the entry is not listening -
         * null: the client stays exactly as before the Network module.
         */
        fun endpoint(tag: String): SocksEndpoint? {
            // [FIX-261025-11] Fail closed. While the entry is down, a proxy or
            // Tailscale route gets an endpoint nothing listens on, so its
            // requests fail instead of leaving from the device's own address
            // around the proxy / exit node the user chose (privacy, region).
            // A direct route still goes direct, exactly as before the module,
            // so the default setup never notices an entry problem.
            if (entryDown) {
                return if (route(tag) == NetworkRoute.Direct) null
                else SocksEndpoint(BLOCKED_PORT, tag, BLOCKED_PASSWORD, BLOCKED_PORT)
            }
            val p = port ?: return null
            if (password.isEmpty()) return null
            if (route(tag) == NetworkRoute.Direct && !tailscaleEnabled) return null
            return SocksEndpoint(p, tag, password, httpPort)
        }

        override fun toString(): String =
            "Snapshot(gen=$generation, entry=${if (entryDown) "DOWN" else port ?: "off"}, http=${httpPort ?: "off"}, tailscale=$tailscaleEnabled, default=$defaultRoute, tags=${tagRoutes.size})"
    }

    /**
     * [FIX-261025-11] Where a proxy / Tailscale route points while the entry is
     * down: loopback port 1, which nothing on Android listens on, so a
     * connection is refused at once rather than timing out.
     */
    const val BLOCKED_PORT = 1
    internal const val BLOCKED_PASSWORD = "entry-down"

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private val listeners = mutableListOf<(Snapshot) -> Unit>()

    /** Policy tag of a provider instance (same as iOS). */
    fun providerTag(instanceId: String) = "prov:$instanceId"

    /** Shortcut for clients: the entry to use for [tag], or null for direct. */
    fun endpoint(tag: String): SocksEndpoint? = snapshot.endpoint(tag)

    @Synchronized
    fun publish(next: Snapshot) {
        val cur = snapshot
        val same = next.copy(generation = cur.generation) == cur
        if (same) return
        val n = next.copy(generation = cur.generation + 1)
        snapshot = n
        AppLogger.info(TAG, "[Net][Session] runtime gen=${n.generation} entry=${if (n.entryDown) "DOWN" else n.port ?: "off"} http=${n.httpPort ?: "off"} tailscale=${n.tailscaleEnabled} default=${n.defaultRoute} tags=${n.tagRoutes.size}")
        listeners.toList().forEach { runCatching { it(n) } }
    }

    /** Called after every published change (on the publishing thread). */
    @Synchronized
    fun addListener(l: (Snapshot) -> Unit) {
        listeners += l
    }

    /** Tests only. */
    @Synchronized
    internal fun resetForTest() {
        snapshot = Snapshot()
        listeners.clear()
    }
}
