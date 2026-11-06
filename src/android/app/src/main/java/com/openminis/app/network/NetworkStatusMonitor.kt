// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.network

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [FEAT-261021-07, iOS a6e6c10d1] Live state behind the small pills under
 * the title on the home screen: whether Tailscale is connected and whether
 * each proxy in use answers. Users who turned Tailscale on or set a proxy
 * long ago may not remember it when connections misbehave; the pills make
 * the routing visible. With nothing configured the pills, and this
 * monitor's work, are absent.
 *
 * A proxy in use is probed (NetworkHub.testProxy) when the app comes to the
 * front, when its settings change, and every [PROBE_INTERVAL_MS] while the
 * app is in front; a settling Tailscale node is asked for its state every
 * [TICK_MS]. Nothing runs in the background.
 */
object NetworkStatusMonitor {

    private const val TAG = "Network"
    const val PROBE_INTERVAL_MS = 180_000L
    const val TICK_MS = 10_000L

    enum class ProxyHealth { CHECKING, AVAILABLE, UNAVAILABLE }

    private val _proxyHealth = MutableStateFlow<Map<String, ProxyHealth>>(emptyMap())
    val proxyHealth: StateFlow<Map<String, ProxyHealth>> = _proxyHealth.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** proxy id -> (what the probe connected through, when). [FIX-R2-17] key, not the whole row. */
    private val probedAt = HashMap<String, Pair<String, Long>>()
    private val inFlight = HashSet<String>()
    private var tickJob: Job? = null
    @Volatile
    private var inFront = false

    /** Proxies that some route actually sends traffic through. A proxy that is only saved gets no pill. */
    fun proxiesInUse(s: NetworkSettings): List<ProxyServer> {
        if (s.forceDirect) return emptyList()
        val routes = (listOf(s.defaultRoute) + s.scopes.values + s.providerRoutes.values).toSet()
        return s.proxies.filter { "proxy:${it.id}" in routes }
    }

    /** Whether the pills have anything to show (and the monitor anything to do). */
    fun wanted(s: NetworkSettings): Boolean = s.nodeWanted || proxiesInUse(s).isNotEmpty()

    /** Called by NetworkHub.init: follows every settings change. */
    fun start(settings: StateFlow<NetworkSettings>) {
        scope.launch { settings.collect { settingsChanged(it) } }
    }

    @Synchronized
    fun onForeground() {
        inFront = true
        val s = NetworkHub.settings.value
        probeStale(s)
        updateTicker(s)
    }

    @Synchronized
    fun onBackground() {
        inFront = false
        tickJob?.cancel()
        tickJob = null
    }

    @Synchronized
    private fun settingsChanged(s: NetworkSettings) {
        val ids = proxiesInUse(s).map { it.id }.toSet()
        _proxyHealth.update { m -> m.filterKeys { it in ids } }
        probedAt.keys.retainAll(ids)
        if (!inFront) return
        probeStale(s)
        updateTicker(s)
    }

    private fun updateTicker(s: NetworkSettings) {
        if (!wanted(s)) {
            tickJob?.cancel()
            tickJob = null
            return
        }
        if (tickJob != null) return
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    @Synchronized
    private fun tick() {
        val s = NetworkHub.settings.value
        // Running and NeedsLogin stay put until the user acts; the entry
        // health check refreshes them every 30 s anyway. Anything else is on
        // its way somewhere, so look more often.
        val ts = NetworkHub.tailscale.value
        if (s.nodeWanted && !ts.isLoggedIn && !ts.needsLogin) NetworkHub.refreshStatus()
        probeStale(s)
        updateTicker(s)
    }

    private fun probeStale(s: NetworkSettings) {
        val now = System.currentTimeMillis()
        for (proxy in proxiesInUse(s)) {
            if (proxy.id in inFlight) continue
            val last = probedAt[proxy.id]
            // [FIX-R2-17, review §三 网络/安全] Key the cache on what the probe
            // actually connects through (id+host+port+kind), not whole-row
            // equality: a rename or bypass edit must not throw away a fresh
            // result and re-send the up-to-8s blocking probe.
            if (last != null && last.first == probeKey(proxy) && now - last.second < PROBE_INTERVAL_MS) continue
            probe(proxy, now)
        }
    }

    /** [FIX-R2-17] The probe-relevant fingerprint of a proxy row. */
    private fun probeKey(p: ProxyServer): String = "${p.id}|${p.host}|${p.port}|${p.kind}"

    private fun probe(proxy: ProxyServer, now: Long) {
        inFlight += proxy.id
        probedAt[proxy.id] = probeKey(proxy) to now
        if (proxy.id !in _proxyHealth.value) _proxyHealth.update { it + (proxy.id to ProxyHealth.CHECKING) }
        scope.launch {
            val health = runCatching { NetworkHub.testProxy(proxy, NetworkHub.proxyPassword(proxy.id)) }.fold(
                { ms ->
                    AppLogger.info(TAG, "[Net][Badge] proxy ${proxy.id.take(8)} answers ($ms ms)")
                    ProxyHealth.AVAILABLE
                },
                { e ->
                    AppLogger.warning(TAG, "[Net][Badge] proxy ${proxy.id.take(8)} probe failed: " +
                        NetworkDiagnostics.classifyText(e.message.orEmpty()))
                    ProxyHealth.UNAVAILABLE
                },
            )
            synchronized(this@NetworkStatusMonitor) {
                inFlight -= proxy.id
                // Dropped or edited while the probe ran: its result describes
                // settings that no longer exist.
                if (proxy in proxiesInUse(NetworkHub.settings.value)) _proxyHealth.update { it + (proxy.id to health) }
            }
        }
    }

    /**
     * What a pill says, ordered by severity so several proxies show their
     * worst. Real traffic failing ([NetworkAlertCenter.failing]) beats an
     * older successful probe; the alert clears itself once the proxy recovers.
     */
    enum class ProxyLevel { AVAILABLE, CHECKING, UNAVAILABLE }

    fun proxyLevel(id: String, health: Map<String, ProxyHealth>, failing: Set<NetworkAlertEvent.Subject>): ProxyLevel = when {
        NetworkAlertEvent.Subject.Proxy(id) in failing -> ProxyLevel.UNAVAILABLE
        health[id] == ProxyHealth.AVAILABLE -> ProxyLevel.AVAILABLE
        health[id] == ProxyHealth.UNAVAILABLE -> ProxyLevel.UNAVAILABLE
        else -> ProxyLevel.CHECKING
    }

    /** The Tailscale pill's state, the same reading of the node as the status row in Settings > Network. */
    enum class TailscaleLevel { CONNECTING, CONNECTED, UNAVAILABLE, NOT_LOGGED_IN, AWAITING_APPROVAL, NOT_CONNECTED }

    fun tailscaleLevel(ts: TailscaleStatus, applying: Boolean, lastError: String?, failing: Boolean): TailscaleLevel = when {
        applying && !ts.running -> TailscaleLevel.CONNECTING
        // Real connections through the node keep failing: say so even though
        // the node itself reports Running.
        ts.isLoggedIn -> if (failing) TailscaleLevel.UNAVAILABLE else TailscaleLevel.CONNECTED
        ts.needsLogin -> TailscaleLevel.NOT_LOGGED_IN
        ts.backendState == "NeedsMachineAuth" -> TailscaleLevel.AWAITING_APPROVAL
        ts.running -> TailscaleLevel.CONNECTING
        // Enabled but not running: still starting, or failed to start (the
        // reason is in Settings > Network).
        lastError == null -> TailscaleLevel.CONNECTING
        else -> TailscaleLevel.NOT_CONNECTED
    }

    /**
     * [FIX-R2-6, review §三 失败处理] Whether the last apply failed to
     * CONFIGURE the local entry (NetworkHub.lastError's entry half - port
     * occupied, Go error). While it lasts every proxied route is down no
     * matter what its own probe last said, so the home pills color their
     * proxy state with this instead. The entry failure has no banner surface
     * of its own (NetworkAlertCenter only ingests Go-side [Net][Alert] lines),
     * so this derivation is the minimal user-visible hook; the reason text
     * stays in Settings > Network.
     */
    fun entryConfigureFailed(lastError: String?): Boolean =
        lastError != null && lastError.startsWith("Local entry: ")
}
