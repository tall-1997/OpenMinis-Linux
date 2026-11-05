// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.debug

import android.content.Context
import com.openminis.app.backup.remote.RcloneBridge
import com.openminis.app.network.NetworkDiagnostics
import com.openminis.app.network.NetworkHub
import com.openminis.app.network.NetworkRuntime
import com.openminis.app.network.NetworkScope
import com.openminis.app.network.NetworkSecrets
import com.openminis.app.network.NetworkSettings
import com.openminis.app.network.NetworkSettingsStore
import com.openminis.app.network.TailscaleNode
import com.openminis.app.network.TailscalePathMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-net-android-phase0] Debug drivers for the embedded Tailscale node, the
 * same `debug.net.ts*` set as iOS `DebugRPCTailscale.swift`. Every call goes
 * through [TailscaleNode], so the RPC exercises exactly what Settings does.
 *
 * The auth key is never echoed or logged; the status returns the login URL
 * (the caller needs it to sign in) but logs only ever see it redacted.
 */
internal object NetDebugMethods {

    val names: Set<String> = setOf(
        "debug.net.tsStart", "debug.net.tsStatus", "debug.net.tsRoutes", "debug.net.tsStop",
        "debug.net.tsLogout", "debug.net.tsDialTest", "debug.net.tsInterfaces", "debug.net.rcloneInfo",
        "debug.net.settings", "debug.net.probe", "debug.net.decide", "debug.net.entryStatus",
        "debug.net.diagnose", "debug.net.restoreDirect", "debug.net.resume", "debug.net.httpGet", "debug.net.rcloneProbe", "debug.net.browserOpen", "debug.net.killEntry",
        "debug.net.xparent", "debug.net.bringForward",
    )

    suspend fun dispatch(context: Context, method: String, params: JSONObject): Any = withContext(Dispatchers.IO) {
        try {
            when (method) {
                "debug.net.tsStart" -> {
                    val t0 = System.nanoTime()
                    TailscaleNode.start(
                        context,
                        authKey = params.optString("authKey").ifEmpty { null },
                        hostname = params.optString("hostname").ifEmpty { "minis-android" },
                    )
                    TailscaleNode.setAcceptRoutes(params.optBoolean("acceptRoutes", false))
                    JSONObject().put("started", true).put("ms", (System.nanoTime() - t0) / 1_000_000)
                        .put("status", TailscaleNode.status())
                }
                "debug.net.tsStatus" -> TailscaleNode.status().apply {
                    val dir = TailscaleNode.stateDirectory(context)
                    put("stateDir", "noBackupFiles/tailscale")
                    put("stateFiles", org.json.JSONArray(dir.list()?.sorted().orEmpty()))
                }
                "debug.net.tsRoutes" -> TailscaleNode.routes()
                "debug.net.tsStop" -> { TailscaleNode.stop(); JSONObject().put("stopped", true) }
                "debug.net.tsLogout" -> { TailscaleNode.logout(); JSONObject().put("loggedOut", true) }
                "debug.net.tsDialTest" -> {
                    val address = params.optString("address").ifEmpty { throw RPCException(-32602, "address (host:port) is required") }
                    val ms = TailscaleNode.dialTest(address, params.optInt("timeoutMs", 8000))
                    JSONObject().put("address", address).put("ms", ms)
                }
                "debug.net.tsInterfaces" -> JSONObject().put("interfaces", TailscalePathMonitor.interfacesJson())
                // rclone shares the Go runtime with the node: confirms it still answers.
                "debug.net.rcloneInfo" -> {
                    RcloneBridge.initializeIfNeeded()
                    JSONObject().put("version", RcloneBridge.smokeTest())
                        .put("backends", org.json.JSONArray(RcloneBridge.supportedBackends()))
                }
                // [T-net-android-phase2] Settings > Network through NetworkHub, as the UI does.
                "debug.net.settings" -> settings(context, params)
                "debug.net.probe" -> TailscaleNode.probe(
                    params.optString("tag").ifEmpty { "default" },
                    params.optString("address").ifEmpty { throw RPCException(-32602, "address (host:port) is required") },
                    params.optInt("timeoutMs", 8000),
                )
                "debug.net.decide" -> TailscaleNode.decide(
                    params.optString("tag").ifEmpty { "default" },
                    params.optString("host").ifEmpty { throw RPCException(-32602, "host is required") },
                )
                // [FEAT-261021-11] Shell transparent forwarding: the routes file
                // proot reads (no secret in it) and the Go listener's counters.
                "debug.net.xparent" -> JSONObject().apply {
                    // Fault injection: the listener dies behind the app's back
                    // while the routes file stays (proot must go direct).
                    if (params.optBoolean("stopListener")) TailscaleNode.xparentStop()
                    com.openminis.app.network.ShellTransparentNet.awaitIdle()
                    val path = com.openminis.app.network.ShellTransparentNet.routesPath
                    val f = path?.let { java.io.File(it) }
                    put("routesPath", path ?: JSONObject.NULL)
                    // [FEAT-261021-23] The file carries the entry password (stage 2): never echoed.
                    put("routes", if (f?.exists() == true) f.readText().replace(Regex("(?m)^pass .*$"), "pass <redacted>") else JSONObject.NULL)
                    put("listener", if (TailscaleNode.entryTouched) TailscaleNode.xparentStatus() else JSONObject().put("port4", 0))
                }
                "debug.net.entryStatus" -> JSONObject().apply {
                    put("entry", if (TailscaleNode.entryTouched) TailscaleNode.entryStatus() else JSONObject().put("listening", false).put("touched", false))
                    put("runtime", NetworkRuntime.snapshot.toString())
                    put("endpoints", JSONObject().apply {
                        for (tag in listOf("default") + NetworkScope.entries.map { it.tag }) {
                            put(tag, NetworkRuntime.endpoint(tag)?.let { "127.0.0.1:${it.port}" } ?: JSONObject.NULL)
                        }
                    })
                }
                // engine=go: the Go checks iOS's diagnose screen shows (same JSON as MinisNetDiagnose).
                "debug.net.diagnose" -> if (params.optString("engine") == "go") TailscaleNode.goDiagnose(params.optInt("timeoutMs", 8000))
                else NetworkDiagnostics.run(context).toJson()
                "debug.net.httpGet" -> httpGet(params)
                // Opens url in a new agent browser tab (the browser tool's own
                // new_tab path), then reports the page title / URL it reached.
                "debug.net.browserOpen" -> {
                    val url = params.optString("url").ifEmpty { throw RPCException(-32602, "url is required") }
                    val app = context.applicationContext as? com.openminis.app.MinisApp ?: throw RPCException(-32000, "MinisApp not initialized")
                    // [port-upstream-1.16] Adaptation: the local BrowserTabPool
                    // has no `owner` parameter (tab-ownership tracking is not
                    // ported); the call is the plain execute(input) it offers.
                    val r = app.sharedBrowserTabPool.execute(
                        com.openminis.app.browser.BrowserActionInput(action = com.openminis.app.browser.BrowserAction.NEW_TAB, url = url),
                    )
                    kotlinx.coroutines.delay(params.optLong("waitMs", 4000))
                    val tab = app.sharedBrowserTabPool.tabs.value.lastOrNull()
                    JSONObject().put("result", r.text.take(200)).put("success", r.success)
                        .put("url", tab?.manager?.currentURL?.value ?: JSONObject.NULL)
                        .put("title", tab?.manager?.pageTitle?.value ?: JSONObject.NULL)
                        .put("browserEndpoint", NetworkRuntime.endpoint(NetworkScope.BROWSER.tag)?.httpPort?.let { "127.0.0.1:$it" } ?: JSONObject.NULL)
                }
                // Fault injection: stop the Go entry behind the hub's back, as if
                // it had died. Clients still hold the old port until they notice.
                "debug.net.killEntry" -> { TailscaleNode.stopEntry(); JSONObject().put("killed", true).put("runtime", NetworkRuntime.snapshot.toString()) }
                // One rclone HTTP request (on-the-fly webdav remote, no config
                // written) to see where the Backup route sends it.
                "debug.net.rcloneProbe" -> {
                    // [FEAT-261021-19] or `fs`: any on-the-fly remote (":sftp,host=…:") for SFTP / FTP.
                    val fs = params.optString("fs").ifEmpty {
                        val url = params.optString("url").ifEmpty { throw RPCException(-32602, "url or fs is required") }
                        ":webdav,url='$url':"
                    }
                    RcloneBridge.initializeIfNeeded()
                    RcloneBridge.applyNetworkProxy()
                    val t0 = System.nanoTime()
                    val r = runCatching { RcloneBridge.rpc("operations/list", mapOf("fs" to fs, "remote" to "")) }
                    JSONObject().put("ms", (System.nanoTime() - t0) / 1_000_000)
                        .put("ok", r.isSuccess).put("error", r.exceptionOrNull()?.message ?: JSONObject.NULL)
                        .put("names", org.json.JSONArray((0 until (r.getOrNull()?.optJSONArray("list")?.length() ?: 0)).map { r.getOrNull()!!.optJSONArray("list")!!.optJSONObject(it)?.optString("Name") }))
                        .put("backupEndpoint", NetworkRuntime.endpoint(NetworkScope.BACKUP.tag)?.let { "127.0.0.1:${it.port}" } ?: JSONObject.NULL)
                }
                // [FEAT-261021-18] G5: what the Tailscale sign-in does once the
                // node is Running - bring MainActivity back over the Custom Tab -
                // called from this process after a delay, while the app is in
                // the background. Whether Android allows it is the question.
                "debug.net.bringForward" -> {
                    val delayMs = params.optLong("delayMs", 5000).coerceIn(0, 60_000)
                    kotlinx.coroutines.delay(delayMs)
                    NetworkHub.bringAppForward()
                    JSONObject().put("called", true).put("delayMs", delayMs)
                }
                "debug.net.restoreDirect" -> { NetworkHub.restoreDirect(); NetworkHub.awaitIdle(); state() }
                "debug.net.resume" -> { NetworkHub.resume(); NetworkHub.awaitIdle(); state() }
                else -> throw RPCException(-32601, "Method not found: $method")
            }
        } catch (e: TailscaleNode.NodeException) {
            throw RPCException(-32000, TailscaleNode.redact(e.message.orEmpty()))
        }
    }

    private val probeClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** One OkHttp GET through NetworkHttp.client(tag, …), exactly as a routed client sends it. */
    private fun httpGet(params: JSONObject): JSONObject {
        val tag = params.optString("tag").ifEmpty { "default" }
        val url = params.optString("url").ifEmpty { throw RPCException(-32602, "url is required") }
        val client = com.openminis.app.network.NetworkHttp.client(tag, probeClient)
        val routed = client !== probeClient
        val t0 = System.nanoTime()
        return try {
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                JSONObject().put("tag", tag).put("viaEntry", routed).put("code", r.code)
                    .put("ms", (System.nanoTime() - t0) / 1_000_000)
                    .put("proxy", client.proxy?.toString() ?: "none")
                    .put("bodyStart", r.body?.string().orEmpty().take(80))
            }
        } catch (e: Exception) {
            JSONObject().put("tag", tag).put("viaEntry", routed).put("error", e.toString())
                .put("ms", (System.nanoTime() - t0) / 1_000_000)
        }
    }

    private fun state(): JSONObject = JSONObject().apply {
        put("settings", JSONObject(NetworkSettingsStore.encode(NetworkHub.settings.value)))
        put("entryPort", NetworkHub.entryPort.value ?: JSONObject.NULL)
        put("lastError", NetworkHub.lastError.value ?: JSONObject.NULL)
        put("tailscale", NetworkHub.tailscale.value.backendState)
        put("runtime", NetworkRuntime.snapshot.toString())
    }

    /**
     * No params = read. `settings` (partial NetworkSettings JSON) = merge and
     * apply. `proxyId` + `proxyPassword` = store a password (never echoed).
     */
    private suspend fun settings(context: Context, params: JSONObject): JSONObject {
        val id = params.optString("proxyId")
        if (id.isNotEmpty() && params.has("proxyPassword")) {
            NetworkSecrets.setPassword(context, id, params.optString("proxyPassword"))
        }
        val patch = params.optJSONObject("settings")
        if (patch != null) {
            val merged = JSONObject(NetworkSettingsStore.encode(NetworkHub.settings.value))
            for (k in patch.keys()) merged.put(k, patch.get(k))
            val next = runCatching { NetworkSettingsStore.json.decodeFromString(NetworkSettings.serializer(), merged.toString()) }
                .getOrElse { throw RPCException(-32602, "settings did not decode as NetworkSettings: ${it.message}") }
            NetworkHub.update { next }
            NetworkHub.awaitIdle()
        } else if (id.isNotEmpty()) {
            NetworkHub.apply()
            NetworkHub.awaitIdle()
        }
        return state()
    }

    private fun spec(name: String, description: String, params: List<DebugMethodRegistry.ParamSpec>, returns: String, example: JSONObject) =
        DebugMethodRegistry.MethodSpec(name, description, params, returns, example)

    private fun p(name: String, type: String, required: Boolean, description: String, default: Any? = null) =
        DebugMethodRegistry.ParamSpec(name, type, required, default, description)

    val specs: List<DebugMethodRegistry.MethodSpec> by lazy {
        listOf(
            spec("debug.net.tsStart", "Start the embedded Tailscale node (state in noBackupFiles/tailscale). Without authKey it waits for a browser login: tsStatus then carries authURL.",
                listOf(
                    p("authKey", "string", false, "Tailscale auth key (used once, never logged)."),
                    p("hostname", "string", false, "Node name in the tailnet.", "minis-android"),
                    p("acceptRoutes", "bool", false, "Accept subnet routes peers advertise.", false),
                ),
                "{started, ms, status}", JSONObject().put("hostname", "minis-android")),
            spec("debug.net.tsStatus", "Node status: running, backendState, authURL, tailscaleIPs, dnsName, tailnet, peers, plus the state files present.",
                emptyList(), "{running, backendState, authURL?, tailscaleIPs, dnsName, tailnet, magicDNSSuffix, peers, stateDir, stateFiles}", JSONObject()),
            spec("debug.net.tsRoutes", "Prefixes reachable through the tailnet now (peers, accepted subnet routes, 0/0 with an exit node) and the MagicDNS suffix.",
                emptyList(), "{prefixes, magicDNSSuffix, exitNode, acceptRoutes}", JSONObject()),
            spec("debug.net.tsStop", "Stop the node (the login is kept).", emptyList(), "{stopped}", JSONObject()),
            spec("debug.net.tsLogout", "Sign the node out of the tailnet and stop it.", emptyList(), "{loggedOut}", JSONObject()),
            spec("debug.net.tsDialTest", "Dial host:port through the tailnet and report the time taken.",
                listOf(
                    p("address", "string", true, "host:port (Tailscale IP or MagicDNS name)."),
                    p("timeoutMs", "int", false, "Dial timeout.", 8000),
                ),
                "{address, ms}", JSONObject().put("address", "100.64.0.1:22")),
            spec("debug.net.tsInterfaces", "The interface list the app feeds the node (java.net.NetworkInterface), as pushed on each network change.",
                emptyList(), "{interfaces}", JSONObject()),
            spec("debug.net.settings", "Read Settings > Network, or merge a partial NetworkSettings JSON and apply it exactly as the UI would. proxyId + proxyPassword stores a proxy password in the encrypted store (never echoed).",
                listOf(
                    p("settings", "object", false, "Partial NetworkSettings (tailscaleEnabled, hostname, acceptRoutes, exitNodeIP, defaultRoute, scopes, proxies, providerRoutes, forceDirect)."),
                    p("proxyId", "string", false, "Proxy whose password to set."),
                    p("proxyPassword", "string", false, "The password (empty removes it)."),
                ),
                "{settings, entryPort, lastError, tailscale, runtime}", JSONObject().put("settings", JSONObject().put("defaultRoute", "direct"))),
            spec("debug.net.probe", "Dial host:port for a policy tag exactly as a client of the local entry would be routed, then close.",
                listOf(p("tag", "string", false, "default | shell | browser | mcp | backup | prov:<id>", "default"),
                    p("address", "string", true, "host:port"), p("timeoutMs", "int", false, "Timeout.", 8000)),
                "{kind, route, ms} or {ok:false, error, kind, route}", JSONObject().put("tag", "default").put("address", "example.com:443")),
            spec("debug.net.decide", "Where a connection from tag to host would go, without dialing.",
                listOf(p("tag", "string", false, "Policy tag.", "default"), p("host", "string", true, "Host name or IP.")),
                "{kind, route}", JSONObject().put("host", "example.com")),
            spec("debug.net.xparent", "Shell transparent forwarding (proot --xnet): the routes file (null = forwarding off, every connection direct) and the Go listener's ports and counters.",
                listOf(p("stopListener", "bool", false, "Fault injection: stop the Go listener only, keeping the routes file.", false)),
                "{routesPath, routes, listener:{port4, port6, connections, failures}}", JSONObject()),
            spec("debug.net.entryStatus", "Local entry status, the published runtime snapshot and the endpoint each scope would get (null = direct).",
                emptyList(), "{entry, runtime, endpoints}", JSONObject()),
            spec("debug.net.diagnose", "Network Diagnostics: each stage (device network, DNS, direct, entry, Tailscale, tailnet, proxies, routes) with PASS/FAIL/WARN/SKIP and a reason. Redacted.",
                listOf(p("engine", "string", false, "\"go\" = the Go checks, same JSON as iOS MinisNetDiagnose.", "app"),
                    p("timeoutMs", "int", false, "Per-check timeout for engine=go.", 8000)),
                "{failed, checks:[{name,result,detail}], text} | engine=go: {checks:[{name,status,detail,ms}], runtime}", JSONObject()),
            spec("debug.net.restoreDirect", "Restore Direct Connections: the module turns off at once (settings kept).", emptyList(), "{settings, entryPort, …}", JSONObject()),
            spec("debug.net.resume", "Turn the network settings back on after restoreDirect.", emptyList(), "{settings, entryPort, …}", JSONObject()),
            spec("debug.net.httpGet", "One OkHttp GET through the Settings > Network client for a tag (NetworkHttp.client): code, time, whether it went through the local entry.",
                listOf(p("tag", "string", false, "Policy tag.", "default"), p("url", "string", true, "URL to fetch.")),
                "{tag, viaEntry, code, ms, proxy, bodyStart} or {error}", JSONObject().put("tag", "mcp").put("url", "https://example.com/")),
            spec("debug.net.browserOpen", "Open url in a new agent browser tab (owner debug-net) and report the title it reached - checks the Browser route end to end.",
                listOf(p("url", "string", true, "Page URL."), p("waitMs", "int", false, "How long to wait before reading the title.", 4000)),
                "{result, url, title, browserEndpoint}", JSONObject().put("url", "https://example.org/")),
            spec("debug.net.bringForward", "After delayMs, call the same bringAppForward() the Tailscale sign-in uses once the node is Running (tests Android's background-activity-launch rules with a Custom Tab on top).",
                listOf(p("delayMs", "int", false, "Wait before the call.", 5000)), "{called, delayMs}", JSONObject().put("delayMs", 5000)),
            spec("debug.net.killEntry", "Fault injection: stop the local entry without telling the hub (as if it died). For testing fallback and recovery.",
                emptyList(), "{killed, runtime}", JSONObject()),
            spec("debug.net.rcloneProbe", "One rclone WebDAV listing of url through rclone's global HTTPProxy (the Backup route), without writing any config.",
                listOf(p("url", "string", false, "https URL to list (WebDAV)."), p("fs", "string", false, "Or an on-the-fly rclone remote, e.g. :sftp,host=…,user=…,pass=<obscured>: (SFTP / FTP follow the route through socks_proxy).")),
                "{ok, error, ms, backupEndpoint}", JSONObject().put("url", "https://example.net/")),
            spec("debug.net.rcloneInfo", "rclone's core/version and linked backends (read-only; rclone shares the Go runtime with the Tailscale node).",
                emptyList(), "{version, backends}", JSONObject()),
        )
    }
}
