// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (com.openminis.app.network domain), kept verbatim unless marked otherwise.
package com.openminis.app.network

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.EncryptedPrefsFactory
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Where connections of one kind go. Raw values are what the Go routing core
 * (deps/rclone-mobile/nethub) reads, the same strings as iOS `NetworkRoute`:
 * "direct", "tailscale", "proxy:<id>", and "default" for a scope that
 * follows the default route.
 */
sealed class NetworkRoute {
    object FollowDefault : NetworkRoute()
    object Direct : NetworkRoute()
    object Tailscale : NetworkRoute()
    data class Proxy(val id: String) : NetworkRoute()

    val raw: String
        get() = when (this) {
            FollowDefault -> "default"
            Direct -> "direct"
            Tailscale -> "tailscale"
            is Proxy -> "proxy:$id"
        }

    companion object {
        fun parse(raw: String?): NetworkRoute = when {
            raw == "direct" -> Direct
            raw == "tailscale" -> Tailscale
            raw != null && raw.startsWith("proxy:") -> Proxy(raw.removePrefix("proxy:"))
            else -> FollowDefault
        }
    }
}

/**
 * A user-configured upstream proxy. The password is NOT here: it lives in the
 * Keystore-encrypted store under the proxy's id ([NetworkSecrets]).
 * Field names match iOS `ProxyServer` (Codable), so the JSON is one format.
 */
@Serializable
data class ProxyServer(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val name: String = "",
    /** socks5 | http | https */
    val kind: String = "socks5",
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    /** Hosts that skip this proxy: exact host, "*.suffix" / ".suffix", CIDR, IP. */
    val bypass: List<String> = emptyList(),
) {
    val displayName: String get() = name.ifEmpty { "$host:$port" }

    companion object {
        val KINDS = listOf("socks5", "http", "https")

        fun kindLabel(kind: String) = when (kind) {
            "http" -> "HTTP"
            "https" -> "HTTPS"
            else -> "SOCKS5"
        }

        fun defaultPort(kind: String) = when (kind) {
            "http" -> 8080
            "https" -> 443
            else -> 1080
        }
    }
}

/** The scopes the user can route separately; the SOCKS username (tag) is the raw value. */
enum class NetworkScope(val tag: String) {
    SHELL("shell"), BROWSER("browser"), MCP("mcp"), BACKUP("backup");
}

/**
 * Everything in Settings > Network. Same JSON keys and defaults as iOS
 * `NetworkSettings`, so a future shared spec (src/shared/network/) can
 * describe both; every field has a default so older or partial data decodes.
 *
 * [forceDirect] is the "Restore Direct Connections" switch: while true the
 * module is off - no node, no local entry, every client exactly as before
 * the module existed - but the configuration is kept for when it is turned
 * back on.
 */
@Serializable
data class NetworkSettings(
    val tailscaleEnabled: Boolean = false,
    /** Node name in the tailnet; empty = "minis-android". */
    val hostname: String = "",
    val acceptRoutes: Boolean = false,
    /** Tailscale IP of the exit node in use; null = none. */
    val exitNodeIP: String? = null,
    /** Raw route (never "default"). */
    val defaultRoute: String = "direct",
    /** NetworkScope.tag -> raw route ("default" = follow). */
    val scopes: Map<String, String> = emptyMap(),
    val proxies: List<ProxyServer> = emptyList(),
    /**
     * Provider instance id -> raw route ("default" = follow). Device-local on
     * purpose: routes name this device's proxies and tailnet, so they are not
     * part of the synced ProviderInstance record (and adding a column there
     * risks the add-column wipe).
     */
    val providerRoutes: Map<String, String> = emptyMap(),
    val forceDirect: Boolean = false,
    /**
     * [FEAT-261021-23, iOS 45510cce2] Exit node privacy for the shell, both
     * off by default. With an exit node in use: a shell connection or
     * datagram the local entry cannot take fails (ENETUNREACH) instead of
     * leaving directly from the device's own address...
     */
    val exitNodeFailClosed: Boolean = false,
    /**
     * ...and the shell's DNS for non-tailnet names goes through the exit
     * node too, instead of to the device's own resolver (which then sees
     * every name looked up, but keeps resolvers that only work here).
     */
    val exitNodeDNS: Boolean = false,
) {
    fun route(scope: NetworkScope): NetworkRoute = NetworkRoute.parse(scopes[scope.tag] ?: "default")

    fun providerRoute(instanceId: String): NetworkRoute = NetworkRoute.parse(providerRoutes[instanceId] ?: "default")

    /** Policy tag -> raw route, as the Go core and [NetworkRuntime] read it. */
    val tagRoutes: Map<String, String>
        get() = buildMap {
            for (s in NetworkScope.entries) put(s.tag, route(s).raw)
            for ((id, r) in providerRoutes) put(NetworkRuntime.providerTag(id), r)
        }

    /** Whether anything needs the local entry at all. */
    val needsEntry: Boolean
        get() {
            if (forceDirect) return false
            if (tailscaleEnabled) return true
            if (NetworkRoute.parse(defaultRoute) != NetworkRoute.Direct) return true
            return (scopes.values + providerRoutes.values).any {
                val r = NetworkRoute.parse(it)
                r != NetworkRoute.Direct && r != NetworkRoute.FollowDefault
            }
        }

    /** Whether the Tailscale node should run. */
    val nodeWanted: Boolean get() = tailscaleEnabled && !forceDirect

    /** Removes proxy [id]; routes that named it fall back (the Go core would go direct too). */
    fun withoutProxy(id: String): NetworkSettings {
        val r = "proxy:$id"
        return copy(
            proxies = proxies.filterNot { it.id == id },
            defaultRoute = if (defaultRoute == r) "direct" else defaultRoute,
            scopes = scopes.mapValues { (_, v) -> if (v == r) "default" else v },
            providerRoutes = providerRoutes.filterValues { it != r },
        )
    }

    /**
     * [FEAT-261021-05] "Stop Using This Proxy": routes that named proxy [id]
     * go direct (default route) or back to the default (scopes, providers);
     * the proxy itself is kept.
     */
    fun stopUsing(id: String): NetworkSettings {
        val r = "proxy:$id"
        return copy(
            defaultRoute = if (defaultRoute == r) "direct" else defaultRoute,
            scopes = scopes.mapValues { (_, v) -> if (v == r) "default" else v },
            providerRoutes = providerRoutes.filterValues { it != r },
        )
    }

    /** True for a user who never touched Settings > Network (or reset it). */
    val isUnconfigured: Boolean get() = this == NetworkSettings()
}

/** Persistence of [NetworkSettings] (plain prefs; no secrets in it). */
object NetworkSettingsStore {
    private const val PREFS = "minis_network"
    /** Same key as iOS's UserDefaults key. */
    const val KEY = "network.settings.v1"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun decode(raw: String?): NetworkSettings =
        raw?.let { runCatching { json.decodeFromString<NetworkSettings>(it) }.getOrNull() } ?: NetworkSettings()

    fun encode(s: NetworkSettings): String = json.encodeToString(s)

    fun load(context: Context): NetworkSettings =
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, s: NetworkSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(s)).apply()
    }
}

/**
 * Proxy passwords, one entry per proxy id, in a Keystore-backed
 * EncryptedSharedPreferences file (the store every other credential uses).
 * Never logged.
 */
object NetworkSecrets {
    private const val FILE = "minis_network_secrets"

    private fun prefs(context: Context) = EncryptedPrefsFactory.safeCreate(context.applicationContext, FILE)

    fun password(context: Context, proxyId: String): String =
        runCatching { prefs(context).getString("proxy.$proxyId", null) }.getOrNull().orEmpty()

    fun setPassword(context: Context, proxyId: String, password: String) {
        runCatching {
            val e = prefs(context).edit()
            if (password.isEmpty()) e.remove("proxy.$proxyId") else e.putString("proxy.$proxyId", password)
            e.apply()
        }.onFailure { AppLogger.error("Network", "[Net][Proxy] could not store the password for a proxy: ${it.javaClass.simpleName}") }
    }

    fun remove(context: Context, proxyId: String) = setPassword(context, proxyId, "")
}
