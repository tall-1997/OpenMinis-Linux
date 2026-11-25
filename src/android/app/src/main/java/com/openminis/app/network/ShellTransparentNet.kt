package com.openminis.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import com.openminis.app.logging.AppLogger
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [FEAT-261021-11] Transparent forwarding of the sandbox shell's TCP
 * connections - the Android side of iOS's ShellTransparentNet (iSH xnet,
 * FEAT-261020-07).
 *
 * Programs that ignore proxy variables (ssh, nc, database clients, static
 * binaries) connect directly. proot's xnet extension (deps/proot
 * src/extension/xnet, `--xnet=<routes file>`) hands their connect() to Go's
 * transparent listener (nethub/xmap.go) when the destination is in the
 * routes file's hook prefixes; Go routes it for the "shell" tag with the
 * same Decide as everything else.
 *
 * This object keeps the routes file in step with Settings > Network:
 *  - the prefixes come from Go (NetHookRoutes, the same state Decide reads):
 *    the tailnet's prefixes while Tailscale is usable, for every route;
 *    0/0 for an exit node or an upstream proxy route; the device's current
 *    networks and a proxy's address bypass list exempt;
 *  - while forwarding has nothing to do - Restore Direct Connections, no
 *    entry, a direct shell route with no usable tailnet, nothing hooked -
 *    the file is DELETED and the listener stopped, so proot touches nothing
 *    and every connection is direct exactly as before;
 *  - proot itself falls back to direct when the listener does not answer.
 *
 * Synced on every runtime publish, after every apply, on a network change
 * and with the 30 s health check; a sync only writes when the text changed.
 * Nothing runs for an app that never turned the Network module on.
 */
object ShellTransparentNet {

    private const val TAG = "Network"
    const val SHELL_TAG = "shell"

    /** The routes file proot reads (`--xnet=`), set by [init]; null before. */
    @Volatile
    var routesPath: String? = null
        private set

    private lateinit var routesFile: File
    private lateinit var mapDir: File
    /** The sandbox's /etc/resolv.conf (RootfsManager.rootfsDir): the shell's nameservers. */
    private lateinit var guestResolvConf: File
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "xnet-sync").apply { isDaemon = true } }

    @Volatile
    private var lastText: String? = null
    @Volatile
    private var listening = false
    @Volatile
    private var lastOffReason: String? = null

    /** [FEAT-261021-23] The stage 2 part of the routes file now, null while forwarding is off. */
    @Volatile
    private var stage2Now: Stage2? = null

    fun currentStage2(): Stage2? = stage2Now?.takeIf { routesPath != null && routesFile.exists() }

    fun offReason(): String? = lastOffReason

    /** Called from NetworkHub.init: removes what an earlier process left behind. */
    fun init(context: Context) {
        if (routesPath != null) return
        val dir = File(context.filesDir, "net").apply { mkdirs() }
        routesFile = File(dir, "xnet.routes")
        mapDir = File(dir, "xnet-map")
        guestResolvConf = File(context.filesDir, "ubuntu-rootfs/etc/resolv.conf")
        routesPath = routesFile.absolutePath
        // A file from an earlier process names a listener that is gone.
        if (routesFile.delete()) AppLogger.info(TAG, "[Net][Xparent] removed the routes file of an earlier process")
        mapDir.listFiles()?.forEach { it.delete() }
        NetworkRuntime.addListener { sync("runtime gen=${it.generation}") }
        runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = sync("network change")
                    override fun onLost(network: Network) = sync("network lost")
                })
        }
    }

    /** Recompute the routes file (on a background thread; callers just call it). */
    fun sync(reason: String) {
        if (routesPath == null) return
        worker.execute { runCatching { syncNow(reason) }.onFailure { off("sync failed: ${it.message}") } }
    }

    /** Tests and the debug RPC: wait for queued syncs. */
    fun awaitIdle(timeoutMs: Long = 5000) {
        if (routesPath == null) return
        val f = worker.submit { }
        runCatching { f.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun syncNow(reason: String) {
        val snap = NetworkRuntime.snapshot
        val settings = NetworkHub.settings.value
        val decision = wanted(settings.forceDirect, snap.port != null, snap.route(SHELL_TAG), snap.tailscaleEnabled)
        if (decision != null) {
            off(decision)
            return
        }
        // [FEAT-261021-23] The shell's nameservers go with the snapshot: their
        // port 53 is DNS - answered by the app while the node is up (MagicDNS
        // names resolve), never sent through an exit node as an ordinary
        // destination (that resolver only exists here).
        val resolvers = guestResolvers()
        val hook = TailscaleNode.hookRoutes2(SHELL_TAG, localPrefixes(), resolvers)
        fun list(key: String) = hook.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val hooks = list("hook")
        val exempt = list("exempt")
        if (hooks.isEmpty()) {
            off("nothing to hook (route=${hook.optString("route")}, tailnet=${hook.optBoolean("tailnet")})")
            return
        }
        mapDir.mkdirs()
        val ports = TailscaleNode.xparentStart(mapDir.absolutePath, SHELL_TAG)
        listening = true
        // Q3 (iOS): fail closed only where going out directly would bypass an
        // exit node, and only when the user asked for it.
        val failClosed = settings.exitNodeFailClosed && hook.optString("route") == "tailscale" && hook.optBoolean("everything")
        val stage2 = if (ports.udpPort > 0 && snap.port != null && snap.password.isNotEmpty()) Stage2(
            udpPort = ports.udpPort, socksPort = snap.port, user = SHELL_TAG, password = snap.password,
            udpHook = list("udpHook"), resolvers = list("resolvers"), dns = hook.optBoolean("dnsIntercept"),
            failClosed = failClosed,
        ) else null
        val text = routesText(ports.port4, ports.port6, mapDir.absolutePath, hooks, exempt, stage2)
        if (text == lastText && routesFile.exists()) return
        val tmp = File(routesFile.parentFile, routesFile.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(routesFile)) {
            tmp.delete()
            off("could not write the routes file")
            return
        }
        lastText = text
        lastOffReason = null
        stage2Now = stage2
        AppLogger.info(TAG, "[Net][Xparent] routes: ${hooks.size} hook, ${exempt.size} exempt, " +
            "route=${hook.optString("route")} everything=${hook.optBoolean("everything")}" +
            (stage2?.let { " udp=${it.udpHook.size} resolvers=${it.resolvers.joinToString(",").ifEmpty { "-" }} " +
                "dns=${if (it.dns) "intercept" else "direct"} failClosed=${it.failClosed}" } ?: " udp=off") + " ($reason)")
    }

    /**
     * Why forwarding must be off, or null when Go should be asked for the
     * prefixes. Pure, for the unit test.
     */
    internal fun wanted(forceDirect: Boolean, entryUp: Boolean, shellRoute: NetworkRoute, tailnetUsable: Boolean): String? = when {
        forceDirect -> "Restore Direct Connections"
        !entryUp -> "no local entry"
        shellRoute == NetworkRoute.Direct && !tailnetUsable -> "shell direct and no usable tailnet"
        else -> null
    }

    /** Delete the file first (proot stops at once), then stop the listener. */
    private fun off(reason: String) {
        val removed = routesFile.exists() && routesFile.delete()
        lastText = null
        stage2Now = null
        if (listening) {
            TailscaleNode.xparentStop()
            listening = false
        }
        if (removed || reason != lastOffReason) {
            if (removed || lastOffReason != null) AppLogger.info(TAG, "[Net][Xparent] off: $reason - shell connections go direct")
            lastOffReason = reason
        }
    }

    /**
     * [FEAT-261021-23] Stage 2 of the routes file: UDP and DNS. The password
     * is the entry's own (the shell gets it in its proxy variables anyway);
     * the file is in the app's private storage, outside the guest's rootfs.
     */
    data class Stage2(
        val udpPort: Int,
        val socksPort: Int,
        val user: String,
        val password: String,
        val udpHook: List<String>,
        val resolvers: List<String>,
        val dns: Boolean,
        val failClosed: Boolean,
    )

    /** The routes file proot's xnet extension reads. Pure, for the unit test. */
    internal fun routesText(
        port4: Int, port6: Int, mapDir: String, hook: List<String>, exempt: List<String>, stage2: Stage2? = null,
    ): String =
        buildString {
            append("# MinisApp Settings > Network -> proot xnet (FEAT-261021-11)\n")
            append("port4 ").append(port4).append('\n')
            if (port6 > 0) append("port6 ").append(port6).append('\n')
            append("map ").append(mapDir).append('\n')
            hook.forEach { append("hook ").append(it).append('\n') }
            exempt.forEach { append("exempt ").append(it).append('\n') }
            if (stage2 != null) {
                append("udpport ").append(stage2.udpPort).append('\n')
                append("socks ").append(stage2.socksPort).append('\n')
                append("user ").append(stage2.user).append('\n')
                append("pass ").append(stage2.password).append('\n')
                stage2.udpHook.forEach { append("udphook ").append(it).append('\n') }
                stage2.resolvers.forEach { append("resolver ").append(it).append('\n') }
                append("dns ").append(if (stage2.dns) 1 else 0).append('\n')
                if (stage2.failClosed) append("failclosed 1\n")
            }
        }

    /**
     * The nameservers of the sandbox's /etc/resolv.conf (ContainerDns' auto
     * or custom list; never changed here). Pure parsing in [parseResolvers].
     */
    private fun guestResolvers(): List<String> =
        runCatching { parseResolvers(guestResolvConf.readText()) }.getOrDefault(emptyList())

    internal fun parseResolvers(text: String): List<String> = text.lineSequence()
        .map { it.substringBefore('#').trim().split(Regex("\\s+")) }
        .filter { it.size >= 2 && it[0] == "nameserver" }
        .map { it[1].substringBefore('%') }
        .distinct()
        .toList()

    /** The device's current networks as masked CIDRs (loopback and point-to-point links left out). */
    private fun localPrefixes(): List<String> {
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        val out = LinkedHashSet<String>()
        for (ni in all) {
            if (runCatching { !ni.isUp || ni.isLoopback || ni.isPointToPoint }.getOrDefault(true)) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address ?: continue
                if (a.isLinkLocalAddress || a.isLoopbackAddress) continue
                maskedPrefix(a, ia.networkPrefixLength.toInt())?.let { out += it }
            }
        }
        return out.toList()
    }

    /** "10.1.1.0/24" for 10.1.1.37 with length 24. Pure, for the unit test. */
    internal fun maskedPrefix(a: InetAddress, length: Int): String? {
        val bytes = a.address
        val max = bytes.size * 8
        if (length !in 1..max) return null
        for (i in bytes.indices) {
            val keep = (length - i * 8).coerceIn(0, 8)
            bytes[i] = (bytes[i].toInt() and (0xff shl (8 - keep)) and 0xff).toByte()
        }
        val host = when (a) {
            is Inet4Address, is Inet6Address -> InetAddress.getByAddress(bytes).hostAddress?.substringBefore('%')
            else -> null
        } ?: return null
        return "$host/$length"
    }

    /**
     * The proot option for every shell builder (agent fresh / pooled shells,
     * the terminal, PRootKernel): `--xnet=<routes file>` only while the
     * routes file exists - forwarding is on - and the binary knows the
     * option. Otherwise nothing, so with forwarding off a shell's proot
     * command is exactly what it was before this feature (no syscall is
     * even watched). A terminal or pooled shell started while forwarding was
     * off keeps going direct until it is restarted; agent commands spawn a
     * process each and always follow.
     */
    fun prootArgs(proot: File): List<String> {
        val routes = routesPath ?: return emptyList()
        if (!File(routes).exists()) return emptyList()
        return if (prootSupportsXnet(proot)) listOf("--xnet=$routes") else emptyList()
    }

    @Volatile
    private var prootSupport: Boolean? = null

    /**
     * Whether the proot binary knows `--xnet` (a build with an older proot
     * would reject the option and every shell command with it). Asked once.
     */
    fun prootSupportsXnet(proot: File): Boolean {
        prootSupport?.let { return it }
        val ok = runCatching {
            val p = ProcessBuilder(proot.absolutePath, "--help").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor(3, TimeUnit.SECONDS)
            text.contains("--xnet")
        }.getOrDefault(false)
        prootSupport = ok
        AppLogger.info(TAG, "[Net][Xparent] proot ${if (ok) "supports" else "does NOT support"} --xnet")
        return ok
    }
}
