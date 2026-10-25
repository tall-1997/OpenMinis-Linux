package com.openminis.app.plugins

import android.content.Context
import com.openminis.app.tools.FetchUrlGuard
import okhttp3.Dns
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/** Explicit per-connector network exception; public web_fetch never uses this. */
object ConnectorNetworkPolicy {
    private const val PREFS = "plugin_network_policy"
    private const val PRIVATE_PREFIX = "allow_private_"

    fun allowsPrivate(context: Context, plugin: PluginRegistry.RemotePlugin): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PRIVATE_PREFIX + plugin.configurationKey(), true)

    fun setAllowsPrivate(context: Context, plugin: PluginRegistry.RemotePlugin, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PRIVATE_PREFIX + plugin.configurationKey(), enabled).apply()
    }

    internal fun clearPluginConfigurations(context: Context, pluginId: String) {
        val prefix = PRIVATE_PREFIX + connectorScopePrefix(pluginId)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            remove(PRIVATE_PREFIX + pluginId)
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach(::remove)
        }.apply()
    }

    fun configurationBlockedReason(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "invalid URL"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "only http/https URLs are allowed"
        val host = uri.host?.lowercase()?.trim('.') ?: return "URL is missing a host"
        if (uri.rawUserInfo != null || uri.rawFragment != null) return "userinfo and fragments are not allowed in connector URLs"
        if (isCloudMetadataOrInternal(host)) return "cloud metadata and internal control hosts are always blocked"
        return null
    }

    internal fun isCloudMetadataOrInternal(host: String): Boolean {
        val normalized = host.lowercase().trim('[', ']').trimEnd('.')
        if (normalized == "metadata.google.internal" || normalized.endsWith(".internal")) return true
        if (normalized == "169.254.169.254" || normalized.startsWith("169.254.")) return true
        if (normalized == "100.100.100.200" || normalized == "100.100.100.201" || normalized == "168.63.129.16") return true
        return normalized == "fd00:ec2::254" || normalized == "fd20:ce::254" || normalized.startsWith("fe80:")
    }

    internal fun isDnsAddressAllowed(address: InetAddress, allowPrivate: Boolean): Boolean =
        !isCloudMetadataOrInternal(address.hostAddress.orEmpty()) &&
            (!FetchUrlGuard.isUnsafeAddress(address) ||
                (allowPrivate && FetchUrlGuard.isExplicitPrivateAddress(address)))

    fun dns(context: Context, plugin: PluginRegistry.RemotePlugin): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (isCloudMetadataOrInternal(hostname)) throw UnknownHostException("cloud metadata and internal control hosts are always blocked")
            val resolved = InetAddress.getAllByName(hostname).toList()
            val allowPrivate = allowsPrivate(context, plugin)
            if (resolved.isEmpty() || resolved.any { address -> !isDnsAddressAllowed(address, allowPrivate) }) {
                throw UnknownHostException("Blocked non-public DNS answer for $hostname")
            }
            return resolved
        }
    }

    fun blockedReason(context: Context, plugin: PluginRegistry.RemotePlugin, url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "invalid URL"
        val host = uri.host?.lowercase()?.trim('.') ?: return "URL is missing a host"
        if (uri.rawUserInfo != null || uri.rawFragment != null) return "userinfo and fragments are not allowed in connector URLs"
        if (isCloudMetadataOrInternal(host)) return "cloud metadata and internal control hosts are always blocked"
        val normal = FetchUrlGuard.blockedReason(url)
        if (normal == null) return null
        if (!allowsPrivate(context, plugin)) return normal
        return if (FetchUrlGuard.isPrivateOrLoopbackIp(host) || host == "localhost" || host.endsWith(".localhost")) {
            null
        } else {
            normal
        }
    }
}
