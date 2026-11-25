package com.openminis.app.config.fields

import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.collections.resolveEnvReference
import com.openminis.app.config.readable.ConfigReadable
import com.openminis.app.config.readable.ConfigReadableRenderer
import com.openminis.app.config.readable.ConfigValueFormatter
import com.openminis.app.config.readable.ReadableArg
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.network.NetworkHub
import com.openminis.app.network.NetworkRoute
import com.openminis.app.network.NetworkScope
import com.openminis.app.network.ProxyServer
import com.openminis.app.network.TailscaleNode
import com.openminis.app.network.TailscalePeer
import com.openminis.app.network.TailscaleStatus

/**
 * [FEAT-261021-03] minis-config for Settings > Network - port of iOS
 * NetworkConfigFields.swift (FEAT-261019-01). Everything the Network page
 * shows can be read, and everything it lets the user change can be set:
 *
 *   network.routingEnabled            master switch (false = Restore Direct Connections)
 *   network.defaultRoute              direct | tailscale | proxy:<id or name>
 *   network.routes.<scope>            shell / browser / mcp / backup, + default
 *   network.tailscale.enabled|hostname|acceptRoutes|exitNode|exitNodeDNS|exitNodeFailClosed
 *   network.tailscale.loggedIn        false = Sign Out
 *   network.tailscale.authKey         write-only: Log In with Auth Key
 *   network.tailscale.signOutAndClear write true: Sign Out and Clear
 *   network.tailscale.status          read-only: state, login link, devices
 *   network.status                    read-only: local entry and last error
 *   proxies.<id>.*                    ProxiesCollection
 *   providers.<id>.networkRoute       ProvidersCollection, uses [NetworkConfigRoute]
 *
 * Paths, values and wording match iOS so the agent's knowledge carries over.
 * iOS's network.bonjourEnabled is left out: Android has no minis-bonjour.
 * Every write goes through NetworkHub, exactly like the Settings page, so it
 * applies at once and an open page updates live.
 */
internal object NetworkConfigFields {

    fun build(envVars: EnvVarRepository): List<ConfigField> = routing() + tailscale(envVars)

    private fun routing(): List<ConfigField> = listOf(
        ClosureField(
            path = "network.routingEnabled",
            displayName = "Network Routing",
            description = "Master switch of Settings › Network. false = everything connects directly, exactly as before the " +
                "Network settings existed (\"Restore Direct Connections\"); Tailscale, proxies and routes are paused but kept. " +
                "Takes effect at once.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.SENSITIVE,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = { ConfigValue.Bool(!NetworkHub.settings.value.forceDirect) },
            writer = { v -> if ((v as ConfigValue.Bool).value) NetworkHub.resume() else NetworkHub.restoreDirect() },
        ),
        ClosureField(
            path = "network.defaultRoute",
            displayName = "Default Route",
            description = "Route for every part of the app set to follow the default: direct, tailscale, or proxy:<id> " +
                "(a proxy's name is accepted too, see `minis-config get proxies`). Addresses in the tailnet always go " +
                "through Tailscale while it is on.",
            valueSchema = ConfigSchema.Str(maxLength = 128),
            risk = ConfigRisk.SENSITIVE,
            readable = ConfigReadable(ConfigValueFormatter.Custom(NetworkConfigRoute::phrase)),
            reader = { ConfigValue.Str(NetworkHub.settings.value.defaultRoute) },
            writer = { v -> NetworkHub.update { it.copy(defaultRoute = (v as ConfigValue.Str).value) } },
            normalizer = { NetworkConfigRoute.normalize(it, proxies()) },
            validator = { NetworkConfigRoute.validate(it, allowFollow = false, proxies()) },
        ),
    ) + NetworkScope.entries.map { scope ->
        ClosureField(
            path = "network.routes.${scope.tag}",
            displayName = scope.configDisplayName,
            description = "Route for ${scope.configWhat}: default (follow network.defaultRoute), direct, tailscale, or " +
                "proxy:<id or name>.",
            valueSchema = ConfigSchema.Str(maxLength = 128),
            risk = ConfigRisk.SENSITIVE,
            readable = ConfigReadable(ConfigValueFormatter.Custom(NetworkConfigRoute::phrase)),
            reader = { ConfigValue.Str(NetworkHub.settings.value.route(scope).raw) },
            writer = { v -> NetworkHub.update { it.copy(scopes = it.scopes + (scope.tag to (v as ConfigValue.Str).value)) } },
            normalizer = { NetworkConfigRoute.normalize(it, proxies()) },
            validator = { NetworkConfigRoute.validate(it, allowFollow = true, proxies()) },
        )
    } + ReadOnlyField(
        path = "network.status",
        displayName = "Network status",
        description = "Read-only: whether the local entry (the SOCKS5 endpoint clients use) is listening, its port, " +
            "whether settings are being applied, and the last error shown on the Network page.",
        valueSchema = ConfigSchema.Json,
        reader = {
            val out = linkedMapOf<String, ConfigValue>(
                "routingEnabled" to ConfigValue.Bool(!NetworkHub.settings.value.forceDirect),
                "entryListening" to ConfigValue.Bool(NetworkHub.entryPort.value != null),
                "applying" to ConfigValue.Bool(NetworkHub.applying.value),
                "lastError" to (NetworkHub.lastError.value?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),
            )
            NetworkHub.entryPort.value?.let { out["entryPort"] = ConfigValue.Int(it) }
            ConfigValue.Obj(out)
        },
    )

    private fun tailscale(envVars: EnvVarRepository): List<ConfigField> = listOf(
        ClosureField(
            path = "network.tailscale.enabled",
            displayName = "Use Tailscale",
            description = "Run the built-in Tailscale node (inside Minis Ultra, no VPN). Needs network.routingEnabled. After " +
                "turning it on, log in with network.tailscale.authKey or open the login link in network.tailscale.status.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.SENSITIVE,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = { ConfigValue.Bool(NetworkHub.settings.value.tailscaleEnabled) },
            writer = { v -> NetworkHub.update { it.copy(tailscaleEnabled = (v as ConfigValue.Bool).value) } },
        ),
        ClosureField(
            path = "network.tailscale.hostname",
            displayName = "Device Name",
            description = "This device's name in the tailnet. Empty = automatic (minis-android). Letters, digits and '-' " +
                "only; changing it restarts the node.",
            valueSchema = ConfigSchema.Str(maxLength = 63, regex = "^[A-Za-z0-9-]*$"),
            risk = ConfigRisk.NORMAL,
            readable = ConfigReadable(ConfigValueFormatter.Text(empty = "Automatic")),
            reader = { ConfigValue.Str(NetworkHub.settings.value.hostname) },
            writer = { v -> NetworkHub.update { it.copy(hostname = (v as ConfigValue.Str).value) } },
            normalizer = { v -> if (v is ConfigValue.Str) ConfigValue.Str(v.value.trim()) else v },
        ),
        ClosureField(
            path = "network.tailscale.acceptRoutes",
            displayName = "Use Subnet Routes",
            description = "Let addresses that other tailnet devices share (such as a home network) go through Tailscale.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.NORMAL,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = { ConfigValue.Bool(NetworkHub.settings.value.acceptRoutes) },
            writer = { v -> NetworkHub.update { it.copy(acceptRoutes = (v as ConfigValue.Bool).value) } },
        ),
        // [FEAT-261021-23, iOS 45510cce2] Exit node privacy for the shell (both off by default).
        ClosureField(
            path = "network.tailscale.exitNodeDNS",
            displayName = "Exit Node DNS",
            description = "With an exit node in use, the shell's DNS lookups of non-tailnet names go through the exit node too. " +
                "Off (default): they go to this device's own DNS server, as without Tailscale - that server sees the names looked up, " +
                "but DNS that only works on this device (a VPN app's, a company's) keeps working. Tailnet names always resolve through Tailscale.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.NORMAL,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = { ConfigValue.Bool(NetworkHub.settings.value.exitNodeDNS) },
            writer = { v -> NetworkHub.update { it.copy(exitNodeDNS = (v as ConfigValue.Bool).value) } },
        ),
        ClosureField(
            path = "network.tailscale.exitNodeFailClosed",
            displayName = "Block Shell Traffic When the Exit Node Is Unreachable",
            description = "With an exit node in use, a shell connection that the app's local network entry cannot take fails instead of going out directly. " +
                "Off (default): it goes out directly, which shows the destination this device's own address. On: a stopped entry cuts the shell off until it is back.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.NORMAL,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = { ConfigValue.Bool(NetworkHub.settings.value.exitNodeFailClosed) },
            writer = { v -> NetworkHub.update { it.copy(exitNodeFailClosed = (v as ConfigValue.Bool).value) } },
        ),
        ClosureField(
            path = "network.tailscale.exitNode",
            displayName = "Exit Node",
            description = "Tailnet device that internet traffic sent over Tailscale leaves through. Empty (or none) = no " +
                "exit node. Accepts the device's Tailscale IPv4, name or MagicDNS name; it must offer itself as an exit " +
                "node (exitNodeOption in network.tailscale.status). Stored as the IPv4.",
            valueSchema = ConfigSchema.Str(maxLength = 253),
            risk = ConfigRisk.SENSITIVE,
            readable = ConfigReadable(ConfigValueFormatter.Custom(NetworkConfigTailscale::exitNodePhrase)),
            reader = { ConfigValue.Str(NetworkHub.settings.value.exitNodeIP.orEmpty()) },
            writer = { v ->
                val ip = (v as ConfigValue.Str).value
                NetworkHub.update { it.copy(exitNodeIP = ip.ifEmpty { null }) }
            },
            normalizer = { NetworkConfigTailscale.normalizeExitNode(it, NetworkConfigTailscale.status()) },
            validator = { NetworkConfigTailscale.validateExitNode(it, NetworkConfigTailscale.status()) },
        ),
        ClosureField(
            path = "network.tailscale.loggedIn",
            displayName = "Tailscale login",
            description = "Whether the node is logged in to a tailnet. Set false to Sign Out (Tailscale stays on and waits " +
                "for a new login; the device identity is kept). It cannot be set true: log in with " +
                "network.tailscale.authKey, or have the user open the login link from network.tailscale.status.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.SENSITIVE,
            revertable = false,
            readable = ConfigReadable(ConfigValueFormatter.Bool(on = "Logged in", off = "Signed out")),
            reader = { ConfigValue.Bool(NetworkConfigTailscale.status().isLoggedIn) },
            writer = { v -> if (!(v as ConfigValue.Bool).value) NetworkHub.logout() },
            validator = { v ->
                if (v == ConfigValue.Bool(true)) {
                    throw ConfigError.InvalidValue(
                        "Log in with network.tailscale.authKey, or open the login link from network.tailscale.status",
                    )
                }
            },
        ),
        ClosureField(
            path = "network.tailscale.authKey",
            displayName = "Tailscale auth key",
            description = "Write-only. Log In with Auth Key: write a tskey-auth-… key (or a `\$\$ENV_VAR` reference) to add " +
                "this device to the tailnet. Used once and not stored; turns Tailscale on if it is off. Cannot be read back.",
            valueSchema = ConfigSchema.Str(maxLength = 1000),
            risk = ConfigRisk.SENSITIVE,
            revertable = false,
            readable = ConfigReadable(ConfigValueFormatter.Secret),
            reader = { throw ConfigError.PermissionDenied("Tailscale auth keys are used once and never stored or exposed") },
            writer = { v ->
                val key = resolveEnvReference(envVars, (v as ConfigValue.Str).value.trim(), "network.tailscale.authKey")
                if (!key.startsWith("tskey-")) throw ConfigError.InvalidValue("A Tailscale auth key starts with tskey-")
                if (NetworkHub.settings.value.forceDirect) {
                    throw ConfigError.InvalidValue("Network routing is off; set network.routingEnabled true first")
                }
                NetworkHub.update { it.copy(tailscaleEnabled = true) }
                NetworkHub.login(key)
            },
        ),
        ClosureField(
            path = "network.tailscale.signOutAndClear",
            displayName = "Sign Out and Clear",
            description = "Write true to sign out, turn Tailscale off and delete the device key: turning Tailscale on " +
                "again adds this device as a new machine. Always reads false.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.DESTRUCTIVE,
            revertable = false,
            readable = ConfigReadable(ConfigValueFormatter.Bool(on = "Sign out and delete the device key", off = "No change")),
            reader = { ConfigValue.Bool(false) },
            writer = { v -> if ((v as ConfigValue.Bool).value) NetworkHub.clearTailscale() },
        ),
        ReadOnlyField(
            path = "network.tailscale.status",
            displayName = "Tailscale status",
            description = "Read-only: state (off / starting / connecting / needsLogin / connected), loginURL (the browser " +
                "login link while logged out - give it to the user to open), tailnet, this device's IPs and MagicDNS " +
                "name, and devices[] (name, ips, os, online, exitNodeOption, exitNode).",
            valueSchema = ConfigSchema.Json,
            reader = { NetworkConfigTailscale.statusValue(NetworkHub.settings.value.nodeWanted, NetworkConfigTailscale.status()) },
        ),
    )

    private fun proxies(): List<ProxyServer> = NetworkHub.settings.value.proxies

    private val NetworkScope.configDisplayName: String
        get() = when (this) {
            NetworkScope.SHELL -> "Route for Shell"
            NetworkScope.BROWSER -> "Route for Browser"
            NetworkScope.MCP -> "Route for MCP"
            NetworkScope.BACKUP -> "Route for Backup"
        }

    private val NetworkScope.configWhat: String
        get() = when (this) {
            NetworkScope.SHELL -> "the sandbox shell (curl, git, pip, … via ALL_PROXY / HTTP(S)_PROXY; open shells pick a change up from their next command)"
            NetworkScope.BROWSER -> "the in-app browser, browser_use and web previews (needs a WebView with proxy override)"
            NetworkScope.MCP -> "MCP servers"
            NetworkScope.BACKUP -> "backups and mounted network folders (rclone)"
        }
}

/**
 * Route strings as minis-config reads and writes them: the raw NetworkRoute
 * values the settings store ("default", "direct", "tailscale", "proxy:<id>").
 * Pure (the proxy list is passed in) so JVM tests cover it.
 */
internal object NetworkConfigRoute {
    /**
     * Keywords are case-insensitive; a proxy is accepted as `proxy:<id>`,
     * `proxy:<name>` or a bare id / name, and stored as `proxy:<id>`.
     * Unknown text is passed on unchanged for [validate] to name.
     */
    fun normalize(v: ConfigValue, proxies: List<ProxyServer>): ConfigValue {
        val s = (v as? ConfigValue.Str)?.value?.trim() ?: throw ConfigError.TypeMismatch("string")
        when (s.lowercase()) {
            "default", "follow", "followdefault", "follow-default", "follow default" -> return ConfigValue.Str("default")
            "direct" -> return ConfigValue.Str("direct")
            "tailscale" -> return ConfigValue.Str("tailscale")
        }
        val ref = if (s.lowercase().startsWith("proxy:")) s.substring("proxy:".length) else s
        return proxyId(ref, proxies)?.let { ConfigValue.Str("proxy:$it") } ?: ConfigValue.Str(s)
    }

    fun validate(v: ConfigValue, allowFollow: Boolean, proxies: List<ProxyServer>) {
        val raw = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
        if (raw == "direct" || raw == "tailscale" || (raw == "default" && allowFollow)) return
        if (raw.startsWith("proxy:") && proxies.any { "proxy:${it.id}" == raw }) return
        val options = listOfNotNull("default".takeIf { allowFollow }, "direct", "tailscale") +
            proxies.map { "proxy:${it.id} (${it.displayName})" }
        throw ConfigError.InvalidValue("unknown route '$raw'; use one of: ${options.joinToString(", ")}")
    }

    fun phrase(v: ConfigValue): ReadableArg {
        val raw = (v as? ConfigValue.Str)?.value ?: return ConfigReadableRenderer.genericPhrase(v)
        return when (val r = NetworkRoute.parse(raw)) {
            NetworkRoute.FollowDefault -> ReadableArg.Key("Follow Default")
            NetworkRoute.Direct -> ReadableArg.Key("Direct")
            NetworkRoute.Tailscale -> ReadableArg.Text("Tailscale")
            is NetworkRoute.Proxy -> ReadableArg.Text(
                NetworkHub.settings.value.proxies.firstOrNull { it.id == r.id }?.displayName ?: raw,
            )
        }
    }

    /** Exact id first, then a unique case-insensitive name (or host:port). */
    private fun proxyId(ref: String, proxies: List<ProxyServer>): String? {
        proxies.firstOrNull { it.id == ref }?.let { return it.id }
        val named = proxies.filter { it.displayName.equals(ref, ignoreCase = true) }
        return named.singleOrNull()?.id
    }
}

internal object NetworkConfigTailscale {
    /**
     * Fresh node status (the hub's copy is refreshed only while the Network
     * page polls). Skipped unless the node is wanted and was started in this
     * process, so a disabled Tailscale never calls into Go.
     */
    fun status(): TailscaleStatus {
        if (!NetworkHub.settings.value.nodeWanted || !TailscaleNode.nodeTouched) return TailscaleStatus()
        return runCatching { TailscaleStatus.parse(TailscaleNode.status()) }.getOrDefault(TailscaleStatus())
    }

    fun statusValue(wanted: Boolean, st: TailscaleStatus): ConfigValue {
        val state = when {
            !wanted -> "off"
            st.isLoggedIn -> "connected"
            st.needsLogin -> "needsLogin"
            st.running -> "connecting"
            else -> "starting"
        }
        val out = linkedMapOf<String, ConfigValue>(
            "state" to ConfigValue.Str(state),
            "tailnet" to ConfigValue.Str(st.tailnet),
            "ips" to ConfigValue.Arr(st.ips.map { ConfigValue.Str(it) }),
            "dnsName" to ConfigValue.Str(st.dnsName),
            "devices" to ConfigValue.Arr(
                st.peers.map { p ->
                    ConfigValue.Obj(
                        linkedMapOf(
                            "name" to ConfigValue.Str(p.displayName),
                            "dnsName" to ConfigValue.Str(p.dnsName),
                            "ips" to ConfigValue.Arr(p.ips.map { ConfigValue.Str(it) }),
                            "os" to ConfigValue.Str(p.os),
                            "online" to ConfigValue.Bool(p.online),
                            "exitNodeOption" to ConfigValue.Bool(p.exitNodeOption),
                            "exitNode" to ConfigValue.Bool(p.exitNode),
                        ),
                    )
                },
            ),
        )
        if (st.needsLogin) st.authURL?.let { out["loginURL"] = ConfigValue.Str(it) }
        st.error?.takeIf { it.isNotEmpty() }?.let { out["error"] = ConfigValue.Str(it) }
        return ConfigValue.Obj(out)
    }

    fun normalizeExitNode(v: ConfigValue, st: TailscaleStatus): ConfigValue {
        val s = (v as? ConfigValue.Str)?.value?.trim() ?: throw ConfigError.TypeMismatch("string")
        if (s.isEmpty() || s.equals("none", ignoreCase = true)) return ConfigValue.Str("")
        val match = st.peers.filter { it.exitNodeOption }.firstOrNull { p ->
            s in p.ips || p.displayName.equals(s, true) || p.hostName.equals(s, true) ||
                (p.dnsName.isNotEmpty() && p.dnsName.equals(s, true))
        }
        return ConfigValue.Str(match?.let(::ipv4) ?: s)
    }

    fun validateExitNode(v: ConfigValue, st: TailscaleStatus) {
        val ip = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
        if (ip.isEmpty()) return
        if (!st.isLoggedIn) {
            throw ConfigError.InvalidValue(
                "Tailscale is not connected; an exit node can be chosen once it is (see network.tailscale.status)",
            )
        }
        val options = st.peers.filter { it.exitNodeOption }
        if (options.any { ipv4(it) == ip }) return
        throw ConfigError.InvalidValue(
            if (options.isEmpty()) "no device in this tailnet offers itself as an exit node"
            else "'$ip' is not an exit node; choose one of: " +
                options.joinToString(", ") { "${it.displayName} (${ipv4(it) ?: "?"})" } + ", or none",
        )
    }

    fun exitNodePhrase(v: ConfigValue): ReadableArg {
        val ip = (v as? ConfigValue.Str)?.value ?: return ConfigReadableRenderer.genericPhrase(v)
        if (ip.isEmpty()) return ReadableArg.Key("None")
        val peer = NetworkHub.tailscale.value.peers.firstOrNull { ip in it.ips }
        return ReadableArg.Text(peer?.let { "${it.displayName} ($ip)" } ?: ip)
    }

    private fun ipv4(p: TailscalePeer): String? = p.ips.firstOrNull { !it.contains(':') }
}
