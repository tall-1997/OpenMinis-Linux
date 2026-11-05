// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.openminis.app.logging.AppLogger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Network Diagnostics" (Settings > Network button and `debug.net.diagnose`):
 * checks each stage a connection goes through - policy -> local entry ->
 * tailnet / upstream proxy / direct -> target - and says, per stage, pass /
 * fail / skipped and why. The output is redacted: no entry password, proxy
 * credential, auth key, login link or token ever appears in it; proxies are
 * named, not addressed.
 *
 * Blocking (dials). Run off the main thread.
 */
object NetworkDiagnostics {

    private const val TAG = "Network"
    const val PROBE_HOST = "www.apple.com"
    private const val PROBE_PORT = 443
    /**
     * Route checks dial a host users rarely put in a bypass list, so a broken
     * proxy shows up on the scopes that use it (www.apple.com is a common
     * bypass entry and would report "direct" instead).
     */
    const val ROUTE_PROBE_HOST = "example.com"
    private const val TIMEOUT_MS = 8000

    enum class Result { PASS, FAIL, WARN, SKIP }

    data class Check(val name: String, val result: Result, val detail: String)

    data class Report(val checks: List<Check>, val at: Long = System.currentTimeMillis()) {
        val failed: Int get() = checks.count { it.result == Result.FAIL }

        fun toText(): String = buildString {
            append("Minis network diagnostics\n")
            for (c in checks) append("[${c.result}] ${c.name}: ${c.detail}\n")
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("failed", failed)
            put("checks", JSONArray().apply {
                for (c in checks) put(JSONObject().put("name", c.name).put("result", c.result.name).put("detail", c.detail))
            })
            put("text", toText())
        }
    }

    fun run(context: Context): Report {
        val checks = mutableListOf<Check>()
        val s = NetworkHub.settings.value
        val rt = NetworkRuntime.snapshot

        // 1. Module / policy
        when {
            s.forceDirect -> checks += Check("Module", Result.PASS, "off (Restore Direct Connections): every connection is direct, settings kept")
            s.isUnconfigured || (!s.needsEntry && !s.nodeWanted) -> checks += Check("Module", Result.PASS, "nothing configured: every connection is direct (unchanged behaviour)")
            else -> checks += Check("Module", Result.PASS, "on: default route=${routeLabel(s, s.defaultRoute)}, tailscale=${s.tailscaleEnabled}, proxies=${s.proxies.size}")
        }

        // 2. OS network
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val iface = net?.let { cm.getLinkProperties(it) }?.interfaceName
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            checks += Check("Device network", if (validated) Result.PASS else Result.WARN,
                "default interface ${iface ?: "?"}" + (if (validated) "" else " (Android has not validated internet access)") +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "; a VPN is active" else ""))
        } else {
            checks += Check("Device network", Result.FAIL, "no network with internet access (offline / airplane mode)")
        }

        // 3. DNS + direct path (system, not through Go)
        val t0 = System.nanoTime()
        val dns = runCatching { InetAddress.getAllByName(PROBE_HOST).first() }
        if (dns.isSuccess) {
            checks += Check("DNS", Result.PASS, "$PROBE_HOST resolved in ${ms(t0)} ms")
            val t1 = System.nanoTime()
            val tcp = runCatching { Socket().use { it.connect(InetSocketAddress(dns.getOrThrow(), PROBE_PORT), TIMEOUT_MS) } }
            checks += if (tcp.isSuccess) Check("Direct connection", Result.PASS, "$PROBE_HOST:$PROBE_PORT in ${ms(t1)} ms")
            else Check("Direct connection", Result.FAIL, "$PROBE_HOST:$PROBE_PORT: ${classify(tcp.exceptionOrNull())}")
        } else {
            checks += Check("DNS", Result.FAIL, "$PROBE_HOST: ${classify(dns.exceptionOrNull())}")
            checks += Check("Direct connection", Result.SKIP, "no DNS")
        }

        // Nothing below runs Go code for an app that never turned the module on.
        if (!s.needsEntry && !s.nodeWanted) {
            checks += Check("Local entry", Result.SKIP, "not needed (no route uses it)")
            checks += Check("Tailscale", Result.SKIP, if (s.forceDirect) "off (Restore Direct Connections)" else "off")
            return finish(checks)
        }

        // 4. Local entry
        val entry = runCatching { TailscaleNode.health() }.getOrNull()
        val listening = entry?.optBoolean("alive") == true
        when {
            !listening -> checks += Check("Local entry", Result.FAIL,
                "not listening" + (NetworkHub.lastError.value?.let { " ($it)" } ?: "") + " -> clients fall back to direct")
            rt.port == null -> checks += Check("Local entry", Result.WARN, "listening, but not published to clients yet")
            else -> {
                val t = runCatching { TailscaleNode.selfTest() }
                checks += if (t.isSuccess) Check("Local entry", Result.PASS,
                    "127.0.0.1:${rt.port} (http ${rt.httpPort ?: "off"}): auth + relay ok in ${t.getOrThrow()} ms; " +
                        "${entry.optLong("connections")} connections, ${entry.optLong("failures")} failed, ${entry.optLong("active")} active")
                else Check("Local entry", Result.FAIL, "self-test: " + classifyText(t.exceptionOrNull()?.message.orEmpty()))
                val httpPort = rt.httpPort
                if (httpPort == null) checks += Check("Local entry (HTTP)", Result.WARN,
                    "HTTP front not listening: model requests, MCP sign-in and the browser go direct")
                else {
                    val h0 = System.nanoTime()
                    val h = runCatching { httpFrontSelfTest(httpPort, rt.password) }
                    checks += if (h.isSuccess) Check("Local entry (HTTP)", Result.PASS,
                        "127.0.0.1:$httpPort: refuses a client without the password (407), tunnels with it, in ${ms(h0)} ms")
                    else Check("Local entry (HTTP)", Result.FAIL, "self-test: " + classifyText(h.exceptionOrNull()?.message.orEmpty()))
                }
            }
        }

        // 5. Tailscale
        var tsRunning = false
        var magicName: String? = null
        if (s.nodeWanted) {
            val st = runCatching { TailscaleStatus.parse(TailscaleNode.status()) }.getOrDefault(TailscaleStatus())
            tsRunning = st.isLoggedIn
            checks += when {
                st.isLoggedIn -> Check("Tailscale", Result.PASS,
                    "connected to ${st.tailnet.ifEmpty { "tailnet" }} as ${st.ips.firstOrNull { !it.contains(':') } ?: "?"}; ${st.peers.count { it.online }}/${st.peers.size} devices online")
                st.needsLogin -> Check("Tailscale", Result.FAIL, "not logged in (sign in under Settings > Network); non-tailnet traffic is unaffected")
                !st.running -> Check("Tailscale", Result.FAIL, "node not running" + (NetworkHub.lastError.value?.let { " ($it)" } ?: ""))
                else -> Check("Tailscale", Result.WARN, "state ${st.backendState.ifEmpty { "starting" }}" + (st.error?.let { ": $it" } ?: ""))
            }
            if (tsRunning) {
                val peer = st.peers.firstOrNull { it.online && it.dnsName.isNotEmpty() }
                if (peer == null) {
                    checks += Check("Tailnet connection", Result.SKIP, "no other device online")
                } else {
                    val name = peer.dnsName
                    magicName = name.trimEnd('.')
                    val d = runCatching { TailscaleNode.decide("default", name) }.getOrNull()
                    checks += Check("MagicDNS", if (d?.optString("kind") == "tailscale") Result.PASS else Result.FAIL,
                        "$name -> ${d?.optString("kind") ?: "?"}")
                    val t2 = System.nanoTime()
                    val dial = runCatching { TailscaleNode.dialTest("$name:22", TIMEOUT_MS) }
                    val err = dial.exceptionOrNull()?.message.orEmpty()
                    checks += when {
                        dial.isSuccess -> Check("Tailnet connection", Result.PASS, "${peer.displayName}:22 in ${ms(t2)} ms")
                        err.contains("refused") -> Check("Tailnet connection", Result.PASS, "${peer.displayName} reachable (port 22 closed) in ${ms(t2)} ms")
                        else -> Check("Tailnet connection", Result.FAIL, "${peer.displayName}: ${classifyText(err)}")
                    }
                }
            }
        } else {
            checks += Check("Tailscale", Result.SKIP, "off")
        }

        // 6. Upstream proxies that some route uses
        val used = (listOf(s.defaultRoute) + s.scopes.values + s.providerRoutes.values)
            .mapNotNull { (NetworkRoute.parse(it) as? NetworkRoute.Proxy)?.id }.toSet()
        for (p in s.proxies) {
            if (p.id !in used) {
                checks += Check("Proxy ${p.displayName}", Result.SKIP, "not used by any route")
                continue
            }
            val r = runCatching { NetworkHub.testProxy(p, NetworkHub.proxyPassword(p.id), "$PROBE_HOST:$PROBE_PORT") }
            checks += if (r.isSuccess) Check("Proxy ${p.displayName}", Result.PASS, "${ProxyServer.kindLabel(p.kind)} -> $PROBE_HOST in ${r.getOrThrow()} ms")
            else Check("Proxy ${p.displayName}", Result.FAIL, "${ProxyServer.kindLabel(p.kind)}: ${classifyText(r.exceptionOrNull()?.message.orEmpty())}")
        }
        for (id in used - s.proxies.map { it.id }.toSet()) {
            checks += Check("Proxy (deleted)", Result.WARN, "a route names a proxy that no longer exists; it connects directly")
        }

        // 7. Where each scope goes, end to end through the entry's own logic
        if (listening) {
            for (tag in listOf("default") + NetworkScope.entries.map { it.tag }) {
                val t3 = System.nanoTime()
                val pr = runCatching { TailscaleNode.probe(tag, "$ROUTE_PROBE_HOST:$PROBE_PORT", TIMEOUT_MS) }.getOrNull()
                val kind = pr?.optString("kind").orEmpty()
                val route = pr?.optString("route").orEmpty()
                val err = pr?.optString("error").orEmpty()
                checks += if (pr != null && err.isEmpty()) Check("Route $tag", Result.PASS, "$route -> $kind, $ROUTE_PROBE_HOST in ${ms(t3)} ms")
                else Check("Route $tag", Result.FAIL, "$route -> $kind: ${classifyText(err)}")
            }
        }
        // [FEAT-261021-18] G5: a WebView without PROXY_OVERRIDE stays direct
        // whatever the Browser route says (WebViewNetworkProxy); say so here
        // too, not only under Settings > Network.
        if (rt.endpoint(NetworkScope.BROWSER.tag) != null && !WebViewNetworkProxy.isSupported()) {
            checks += Check("Browser (WebView)", Result.WARN,
                "this device's WebView cannot use a proxy: the browser connects directly, whatever its route")
        }
        // [FEAT-261021-23] The shell's UDP and DNS (proot's xnet, stage 2).
        checks += shellUdpDnsChecks(listening, magicName)
        // [FEAT-261021-18] What the shell does not route, said here so
        // nobody takes it for working.
        checks += Check("Inbound / ICMP", Result.SKIP,
            "not supported: tailnet devices cannot connect to services in the shell, and ping is not routed")
        return finish(checks)
    }

    /**
     * [FEAT-261021-23] "Shell UDP": a round trip through the entry's UDP
     * relay; "Shell DNS": a MagicDNS name (when the node has a peer) and a
     * public name looked up as the shell's intercepted DNS is answered, with
     * the shell's own nameserver. SKIP while the shell's forwarding is off -
     * its UDP and DNS are then direct, exactly as before.
     */
    private fun shellUdpDnsChecks(entryListening: Boolean, magicName: String?): List<Check> {
        val s2 = ShellTransparentNet.currentStage2()
        if (!entryListening || s2 == null) {
            val why = ShellTransparentNet.offReason() ?: "forwarding is off"
            return listOf(Check("Shell UDP / DNS", Result.SKIP, "$why: the shell's UDP and DNS go direct (unchanged behaviour)"))
        }
        val names = listOfNotNull(magicName, ROUTE_PROBE_HOST)
        val resolver = s2.resolvers.firstOrNull().orEmpty()
        val r = runCatching { TailscaleNode.shellProbe(resolver, names, TIMEOUT_MS) }
        val probe = r.getOrNull() ?: return listOf(Check("Shell UDP", Result.FAIL, classifyText(r.exceptionOrNull()?.message.orEmpty())))
        val out = mutableListOf<Check>()
        val udp = probe.optJSONObject("udp")
        val stats = runCatching { TailscaleNode.xparentStatus() }.getOrNull()
        val counts = stats?.let {
            val m = it.optJSONObject("udp")
            val rl = it.optJSONObject("relay")
            "; connected sockets: ${m?.optLong("opened") ?: 0} sessions, ${m?.optLong("sent") ?: 0} sent / ${m?.optLong("received") ?: 0} received, " +
                "${m?.optLong("unmapped") ?: 0} unmapped; sendto: ${rl?.optLong("opened") ?: 0} associations, ${rl?.optLong("sent") ?: 0} sent"
        }.orEmpty()
        out += if (udp != null && udp.has("ms")) Check("Shell UDP", Result.PASS,
            "relay round trip in ${udp.optLong("ms")} ms; udp hook ${s2.udpHook.size} prefixes$counts")
        else Check("Shell UDP", Result.FAIL, classifyText(udp?.optString("error").orEmpty()))
        if (!s2.dns) {
            out += Check("Shell DNS", Result.SKIP, "not intercepted (the node is down): the shell asks ${resolver.ifEmpty { "its resolver" }} directly, as before")
            return out
        }
        val dns = probe.optJSONArray("dns")
        if (resolver.isEmpty() || dns == null || dns.length() == 0) {
            out += Check("Shell DNS", Result.WARN, "no nameserver in the shell's resolv.conf")
            return out
        }
        val parts = (0 until dns.length()).map { dns.optJSONObject(it) }
        val failed = parts.filter { it.optString("error").isNotEmpty() || it.optInt("answers") == 0 }
        val text = parts.joinToString("; ") {
            "${it.optString("name")} via ${it.optString("via")} (${it.optString("rule")}) " +
                (it.optString("error").ifEmpty { "${it.optString("rcode")}, ${it.optInt("answers")} answers in ${it.optLong("ms")} ms" })
        }
        out += Check("Shell DNS", if (failed.isEmpty()) Result.PASS else Result.FAIL, "resolver $resolver: $text")
        return out
    }

    /**
     * [FEAT-261021-18] The entry's HTTP front checks its own password: a
     * CONNECT without it must get 407 (so another app on this device, which
     * can reach 127.0.0.1, cannot use it), and one with it must tunnel. The
     * target is a throwaway listener of this check on loopback, which every
     * route reaches directly, so an upstream proxy or tailnet problem cannot
     * fail it, and the entry's own ports see no stray connection. The probe
     * tag is "diag".
     */
    private fun httpFrontSelfTest(httpPort: Int, password: String) {
        java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { target ->
            target.soTimeout = 5000
            fun connect(auth: String?): String = Socket().use { sock ->
                sock.connect(InetSocketAddress("127.0.0.1", httpPort), 2000)
                sock.soTimeout = 5000
                val dst = "127.0.0.1:${target.localPort}"
                val req = "CONNECT $dst HTTP/1.1\r\nHost: $dst\r\n" +
                    (auth?.let { "Proxy-Authorization: $it\r\n" } ?: "") + "\r\n"
                sock.getOutputStream().apply { write(req.toByteArray()); flush() }
                sock.getInputStream().bufferedReader().readLine().orEmpty()
            }
            val anonymous = connect(null)
            require(anonymous.contains(" 407")) { "a client without the password was not refused: ${anonymous.take(60)}" }
            val ok = connect(okhttp3.Credentials.basic("diag", password))
            require(ok.contains(" 200")) { "CONNECT with the password failed: ${ok.take(60)}" }
            target.accept().close() // the tunnel really reached the target
        }
    }

    private fun finish(checks: List<Check>): Report {
        val r = Report(checks)
        AppLogger.info(TAG, "[Net][Diag] ${checks.size} checks, ${r.failed} failed: " +
            checks.joinToString("; ") { "${it.name}=${it.result}" })
        return r
    }

    private fun routeLabel(s: NetworkSettings, raw: String): String = when (val r = NetworkRoute.parse(raw)) {
        is NetworkRoute.Proxy -> "proxy:" + (s.proxies.firstOrNull { it.id == r.id }?.displayName ?: "(deleted)")
        else -> r.raw
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    private fun classify(t: Throwable?): String = classifyText((t?.javaClass?.simpleName ?: "") + ": " + (t?.message ?: ""))

    /** Short reason class + the message, as the logs classify it. */
    fun classifyText(msg: String): String {
        val m = msg.lowercase()
        val cls = when {
            "407" in m || "authentication" in m || "auth" in m && "fail" in m -> "auth-failed"
            "unknownhost" in m || "no such host" in m || "lookup" in m || "unable to resolve" in m -> "dns"
            "refused" in m -> "refused"
            "timeout" in m || "timed out" in m || "deadline" in m -> "timeout"
            "unreachable" in m || "no route" in m -> "unreachable"
            "tls" in m || "handshake" in m || "certificate" in m -> "tls"
            "proxy connect" in m || "502" in m || "403" in m -> "proxy-rejected"
            "nostate" in m || "needslogin" in m || "not started" in m -> "tailscale-not-ready"
            else -> "error"
        }
        return "$cls (${TailscaleNode.redact(msg.trim().take(160))})"
    }
}
