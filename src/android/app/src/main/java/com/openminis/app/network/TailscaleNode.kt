// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.network

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.rclone.gomobile.Gomobile
import com.openminis.rclone.gomobile.LogSink
import java.io.File
import org.json.JSONObject

/**
 * Kotlin side of the embedded Tailscale node, mirroring
 * `src/ios/Agent/Network/TailscaleNode.swift`.
 *
 * The node lives in Go (deps/rclone-mobile/tsnet) inside the same gomobile
 * library as rclone: an app can load only one Go runtime. Every entry point
 * returns the JSON envelope `{"ok":true[,"result":…]}` /
 * `{"ok":false,"error":"…"}` the iOS exports use; [call] unwraps it.
 *
 * The Go calls block (Start waits for the control server, Dial for the
 * peer), so callers run them off the main thread.
 */
object TailscaleNode {

    private const val TAG = "Network"

    class NodeException(message: String) : Exception(message)

    /**
     * Login links (login.tailscale.com/a/<token>) are bearer-like: whoever
     * opens one can claim this node, and tsnet prints the link every 5 s
     * while it waits. Same pattern as iOS.
     */
    private val loginLink = Regex("""(login\.tailscale\.com/a/)[A-Za-z0-9]+""")

    fun redact(line: String): String = loginLink.replace(line, "$1<redacted>")

    internal fun isVerbose(line: String): Boolean =
        line.contains("[v1]") || line.contains("[v2]") || line.contains("JSON]")

    @Volatile
    private var loggerInstalled = false

    /**
     * Whether this process ever started the node / the local entry. Nothing
     * stops or polls them otherwise, so an app that never turned the module
     * on never calls into Go from here (see NetworkHub).
     */
    @Volatile
    var nodeTouched = false
        private set

    @Volatile
    var entryTouched = false
        private set

    /** Routes the Go node's log lines into AppLogger as `[Net][TS]`. */
    @Synchronized
    fun installLogger() {
        if (loggerInstalled) return
        Gomobile.tsSetLogger(object : LogSink {
            override fun line(line: String) {
                // [FEAT-261021-05] A proxy / the node failing N times in a row.
                NetworkAlertCenter.ingest(line)
                // The entry's own lines already carry [Net][Entry|Policy|Proxy|Dial].
                val text = if (line.startsWith("[Net][")) redact(line) else "[Net][TS] " + redact(line)
                // tailscale's verbose levels ([v1] / [v2] and its structured
                // "[v\u0000JSON]" lines - dozens per netmap update) go to the
                // Verbose log level only; Info keeps state changes and errors.
                if (isVerbose(line)) AppLogger.debug(TAG, text) else AppLogger.info(TAG, text)
            }
        })
        loggerInstalled = true
    }

    /**
     * The node key and login state (tailscaled.state). noBackupFilesDir: kept
     * out of Android's cloud and device-transfer backups, since a node key
     * restored on another device would impersonate this one.
     */
    fun stateDirectory(context: Context): File = File(context.noBackupFilesDir, "tailscale")

    private fun call(raw: String): Any? {
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw NodeException("tsnet returned invalid JSON")
        if (obj.optBoolean("ok")) return obj.opt("result")
        throw NodeException(obj.optString("error").ifEmpty { "tsnet call failed" })
    }

    private fun callObject(raw: String): JSONObject = call(raw) as? JSONObject ?: JSONObject()

    /**
     * Starts the node. `authKey` null/empty = interactive login (the status
     * then carries `authURL`). The key is never logged.
     */
    fun start(context: Context, authKey: String?, hostname: String) {
        installLogger()
        val dir = stateDirectory(context)
        if (!dir.isDirectory && !dir.mkdirs()) throw NodeException("cannot create the Tailscale state directory")
        nodeTouched = true
        TailscalePathMonitor.start(context)
        AppLogger.info(TAG, "[Net][TS] start hostname=$hostname authKey=${if (authKey.isNullOrEmpty()) "none" else "provided"} dir=noBackupFiles/tailscale")
        val t0 = System.nanoTime()
        try {
            call(Gomobile.tsStart(authKey.orEmpty(), hostname, dir.absolutePath))
            AppLogger.info(TAG, "[Net][TS] start ok in ${"%.2f".format((System.nanoTime() - t0) / 1e9)}s")
        } catch (e: NodeException) {
            AppLogger.error(TAG, "[Net][TS] start FAILED: ${redact(e.message.orEmpty())}")
            throw e
        }
    }

    /** Accepts (true) or ignores subnet routes peers advertise. */
    fun setAcceptRoutes(on: Boolean) {
        call(Gomobile.tsSetAcceptRoutes(on))
        AppLogger.info(TAG, "[Net][TS] accept subnet routes = $on")
    }

    /** Uses the peer with Tailscale IP `ip` as exit node ("" = none). */
    fun setExitNode(ip: String) {
        call(Gomobile.tsSetExitNode(ip))
        AppLogger.info(TAG, "[Net][TS] exit node = ${ip.ifEmpty { "none" }}")
    }

    fun stop() {
        call(Gomobile.tsStop())
        AppLogger.info(TAG, "[Net][TS] stopped")
    }

    fun logout() {
        call(Gomobile.tsLogout())
        AppLogger.info(TAG, "[Net][TS] logged out")
    }

    /**
     * running, backendState, authURL, tailscaleIPs, dnsName, tailnet,
     * magicDNSSuffix, peers[], socks, error.
     */
    fun status(): JSONObject = callObject(Gomobile.tsStatus())

    /** Prefixes reachable through the tailnet now, and the MagicDNS suffix. */
    fun routes(): JSONObject = callObject(Gomobile.tsRoutes())

    /** Dials `address` ("host:port") through the tailnet; milliseconds. */
    fun dialTest(address: String, timeoutMs: Int = 8000): Long =
        callObject(Gomobile.tsDialTest(address, timeoutMs.toLong())).optLong("ms", -1)

    // Local SOCKS5 entry (nethub)

    /**
     * Applies the routing configuration (nethub.Config JSON, proxy passwords
     * included, so never logged) and makes sure the entry listens.
     */
    data class Entry(val port: Int, val httpPort: Int?, val password: String)

    fun configureEntry(config: JSONObject): Entry {
        // [FEAT-261021-05] The entry logs through the same sink as the node
        // (tsnet.Logf); with proxies but no Tailscale it was never installed,
        // so neither its [Net] lines nor the [Net][Alert] ones reached the app.
        installLogger()
        entryTouched = true
        val r = callObject(Gomobile.netConfigure(config.toString()))
        val port = r.optInt("port", 0)
        val password = r.optString("password")
        if (port <= 0 || password.isEmpty()) throw NodeException("local entry did not report its port")
        return Entry(port, r.optInt("httpPort", 0).takeIf { it > 0 }, password)
    }

    fun stopEntry() {
        call(Gomobile.netStop())
    }

    // [FEAT-261021-11] Transparent forwarding for proot's xnet extension.

    /** nethub.HookSnapshot for [tag] given the device's networks (CIDRs). */
    fun hookRoutes(tag: String, local: List<String>): JSONObject =
        callObject(Gomobile.netHookRoutes(tag, org.json.JSONArray(local).toString()))

    /**
     * [FEAT-261021-23] hookRoutes plus the stage 2 fields - udpHook,
     * resolvers, dnsIntercept - given the shell's nameservers (iOS
     * MinisNetHookRoutes2).
     */
    fun hookRoutes2(tag: String, local: List<String>, resolvers: List<String>): JSONObject =
        callObject(Gomobile.netHookRoutes2(tag, org.json.JSONArray(local).toString(), org.json.JSONArray(resolvers).toString()))

    data class XparentPorts(val port4: Int, val port6: Int, val udpPort: Int)

    /** Starts (or keeps) the listener; its ports (0 = none). */
    fun xparentStart(mapDir: String, tag: String): XparentPorts {
        installLogger()
        val r = callObject(Gomobile.netXparentStart(mapDir, tag))
        return XparentPorts(r.optInt("port4"), r.optInt("port6"), r.optInt("udpPort"))
    }

    /**
     * [FEAT-261021-23] The shell's UDP and DNS paths checked from inside the
     * app (iOS MinisNetShellProbe): {udp:{ms|error}, dns:[{name,via,rule,rcode,answers,ms,error}]}.
     */
    fun shellProbe(resolver: String, names: List<String>, timeoutMs: Int = 5000): JSONObject =
        callObject(Gomobile.netShellProbe(resolver, org.json.JSONArray(names).toString(), timeoutMs.toLong()))

    fun xparentStop() {
        runCatching { call(Gomobile.netXparentStop()) }
    }

    /** {port4, port6, connections, failures} */
    fun xparentStatus(): JSONObject = callObject(Gomobile.netXparentStatus())

    /** {listening, addr, httpAddr, connections, failures} */
    fun entryStatus(): JSONObject = callObject(Gomobile.netStatus())

    /** {alive, addr, httpAddr, active, connections, failures, goroutines, heapMB} (iOS MinisNetHealth). */
    fun health(): JSONObject = callObject(Gomobile.netHealth())

    /** Connects to the entry as a client would (auth + relay); milliseconds. Throws with stage/class. */
    fun selfTest(timeoutMs: Int = 5000): Long = callObject(Gomobile.netSelfTest(timeoutMs.toLong())).optLong("ms", -1)

    /** iOS MinisNetDiagnose's checks, from Go: {checks:[{name,status,detail,ms}], runtime}. */
    fun goDiagnose(timeoutMs: Int = 8000): JSONObject = callObject(Gomobile.netDiagnose(timeoutMs.toLong()))

    /** Where a connection from `tag` to `host` would go: {kind, route}. */
    fun decide(tag: String, host: String): JSONObject = callObject(Gomobile.netDecide(tag, host))

    /**
     * Dials `address` for `tag` exactly as an entry client would be routed.
     * Returns the raw envelope's fields so a failure keeps its kind/route.
     */
    fun probe(tag: String, address: String, timeoutMs: Int = 8000): JSONObject {
        val obj = JSONObject(Gomobile.netProbe(tag, address, timeoutMs.toLong()))
        return obj.optJSONObject("result") ?: obj
    }

    /** Connects through one upstream proxy to `target`; milliseconds. */
    fun testUpstream(upstream: JSONObject, target: String, timeoutMs: Int = 8000): Long =
        callObject(Gomobile.netTestUpstream(upstream.toString(), target, timeoutMs.toLong())).optLong("ms", -1)
}
