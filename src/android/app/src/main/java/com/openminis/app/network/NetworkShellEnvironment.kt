// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.network

import com.openminis.app.logging.AppLogger

/**
 * [T-net-android-phase3] Proxy environment for programs run in the proot
 * shell, following the "Shell" scope in Settings > Network - the counterpart
 * of iOS `NetworkShellEnvironment` (same variables, same values).
 *
 * Empty when the scope's route is direct or the local entry is not
 * listening, so the shell environment is exactly as before. Otherwise
 * ALL_PROXY points at the local SOCKS5 entry with remote DNS (socks5h, so
 * MagicDNS names resolve through the route) for curl, git and Go, and
 * HTTP(S)_PROXY at the entry's HTTP proxy front for the tools that cannot
 * use SOCKS (see [ShellProxyVariables]); no_proxy keeps loopback local.
 * proot shares the host network namespace, so 127.0.0.1 inside the guest is
 * the app's own entry.
 *
 * Read when a shell process is spawned, and [FEAT-261021-10] pushed into
 * every running shell (agent shells and terminals) when the Shell route's
 * variables change - see [watchRuntime].
 */
object NetworkShellEnvironment {

    private const val TAG = "Network"
    const val NO_PROXY = ShellProxyVariables.NO_PROXY

    @Volatile
    private var lastBroadcast: Map<String, String>? = null

    /**
     * [FEAT-261021-10] Calls [onChange] after a runtime publish that changes
     * the shell's variables (a Shell route set or cleared, the entry
     * restarted on a new port, Routing turned off). Running shells then get
     * the new block exported in place - no restart, nothing they run is
     * interrupted. The first publish only records the baseline.
     */
    fun watchRuntime(onChange: (cleared: Set<String>) -> Unit) {
        NetworkRuntime.addListener {
            changedSinceLastBroadcast(proxyVariables())?.let(onChange)
        }
    }

    /**
     * null when unchanged (or the first call); otherwise the keys the last
     * block set that this one does not - e.g. ALL_PROXY when the Shell route
     * goes back to direct, which the system-proxy block does not cover. Only
     * those are cleared, so an ALL_PROXY the user set as an environment
     * variable is never touched by this module.
     */
    @Synchronized
    internal fun changedSinceLastBroadcast(vars: Map<String, String>): Set<String>? {
        val before = lastBroadcast
        lastBroadcast = vars
        if (before == null || before == vars) return null
        return before.keys - vars.keys
    }

    /** Tests only. */
    @Synchronized
    internal fun resetForTest() {
        lastBroadcast = null
        lastLogged = null
    }

    @Volatile
    private var lastLogged: String? = null

    fun proxyVariables(): Map<String, String> {
        val snap = NetworkRuntime.snapshot
        val tag = NetworkScope.SHELL.tag
        val ep = snap.endpoint(tag)
        val route = snap.route(tag)
        val shellVars = ShellProxyVariables.make(ep?.url, ep?.httpUrl, routeIsDirect = route == NetworkRoute.Direct)
        // [FEAT-261021-19, iOS 59362a754 G3] minis-mcp-cli's own route (the
        // "MCP" scope), whatever the shell's: the entry decides per the mcp tag.
        // [FIX-261025-11] While the entry is down, an MCP route through a proxy
        // / Tailscale points minis-mcp-cli at the blocked endpoint (fail
        // closed) instead of leaving MINIS_MCP_PROXY unset, which sent it out
        // directly around the user's proxy. A direct MCP route stays unset.
        val mcpBlocked = if (snap.entryDown) snap.endpoint(NetworkScope.MCP.tag) else null
        val vars = shellVars + if (mcpBlocked != null) {
            ShellProxyVariables.mcp(mcpBlocked.port, mcpBlocked.httpPort, mcpBlocked.password)
        } else {
            ShellProxyVariables.mcp(snap.port, snap.httpPort, snap.password)
        }
        // Variable names and ports only - never a URL (it carries the entry
        // password). Logged when it changes, not per spawn.
        val state = when {
            shellVars.isEmpty() -> "none (route=${route.raw}; shells connect directly)"
            snap.entryDown -> "BLOCKED (entry down; proxy variables point at a closed port) route=${route.raw}"
            ep?.httpPort != null -> "ALL_PROXY -> socks5h://shell:<password>@127.0.0.1:${ep.port}, " +
                "HTTP(S)_PROXY -> http://shell:<password>@127.0.0.1:${ep.httpPort}, NO_PROXY=$NO_PROXY route=${route.raw}"
            else -> "ALL_PROXY -> socks5h://shell:<password>@127.0.0.1:${ep?.port}, NO_PROXY=$NO_PROXY; " +
                "HTTP(S)_PROXY NOT set (the http front is not listening, so wget / apk / pip connect directly) route=${route.raw}"
        }
        val mcpState = when {
            ShellProxyVariables.MCP_PROXY_KEY !in vars -> "MINIS_MCP_PROXY none (entry off: minis-mcp-cli as before)"
            mcpBlocked != null -> "MINIS_MCP_PROXY -> BLOCKED (entry down) route=${snap.route(NetworkScope.MCP.tag).raw}"
            else -> "MINIS_MCP_PROXY -> http://mcp:<password>@127.0.0.1:${snap.httpPort} route=${snap.route(NetworkScope.MCP.tag).raw}"
        }
        if ("$state; $mcpState" != lastLogged) {
            lastLogged = "$state; $mcpState"
            AppLogger.info(TAG, "[Net][Shell] new shells: $state; $mcpState gen=${snap.generation}")
        }
        return vars
    }
}

/**
 * Which proxy variables the shell gets (iOS `ShellProxyVariables`).
 *
 * [FIX-261021-01, iOS b1b1fe2c7] Only when the user routed the shell
 * somewhere (Tailscale or a proxy). With Tailscale on, the entry also exists
 * for a DIRECT route - so OkHttp can split tailnet hosts off - but exporting
 * it to the shell then broke the common tools for every Tailscale user, so a
 * direct shell gets nothing; a tailnet host is still reachable on purpose
 * with `curl --proxy` or by routing the Shell scope.
 *
 * [iOS 62f3577a3] HTTP(S)_PROXY name the entry's HTTP front (http://), not
 * the SOCKS entry: BusyBox wget, Alpine apk (libfetch) and Python urllib /
 * pip / requests cannot use a socks5h:// HTTP(S)_PROXY, and wget's error
 * then printed the URL, entry password included, into the agent's
 * transcript. Without the front they are left unset (those tools connect
 * directly) rather than pointed at SOCKS again.
 */
object ShellProxyVariables {
    const val NO_PROXY = "localhost,127.0.0.1,::1"

    fun make(socksUrl: String?, httpUrl: String?, routeIsDirect: Boolean): Map<String, String> {
        if (routeIsDirect || socksUrl.isNullOrEmpty()) return emptyMap()
        val vars = linkedMapOf(
            "ALL_PROXY" to socksUrl, "all_proxy" to socksUrl,
            "NO_PROXY" to NO_PROXY, "no_proxy" to NO_PROXY,
        )
        if (!httpUrl.isNullOrEmpty()) {
            for (k in listOf("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy")) vars[k] = httpUrl
        }
        return vars
    }

    /**
     * [FEAT-261021-19, iOS 59362a754 G3] The variable only minis-mcp-cli
     * reads: the local entry's HTTP front with the `mcp` tag, set whenever
     * the front listens - also for a direct MCP route, so the entry (not the
     * shell's proxy variables or its transparent forwarding) applies the MCP
     * scope. Nothing while the entry is off: the cli behaves as before.
     */
    const val MCP_PROXY_KEY = "MINIS_MCP_PROXY"

    fun mcp(port: Int?, httpPort: Int?, password: String): Map<String, String> {
        if (port == null || httpPort == null || password.isEmpty()) return emptyMap()
        val url = SocksEndpoint(port, NetworkScope.MCP.tag, password, httpPort).httpUrl ?: return emptyMap()
        return mapOf(MCP_PROXY_KEY to url)
    }
}
