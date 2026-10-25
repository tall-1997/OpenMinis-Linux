package com.openminis.app.plugins

import java.security.MessageDigest

private fun digestHex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .take(16)
    .joinToString("") { "%02x".format(it) }

internal fun connectorScopePrefix(pluginId: String): String =
    "connector:${digestHex(pluginId.trim())}:"

/** Stable identity for secrets and network grants; catalog ID alone is not trusted. */
internal fun PluginRegistry.RemotePlugin.configurationKey(): String {
    val uri = runCatching { java.net.URI(baseUrl.trim()).normalize() }.getOrNull()
    val normalizedOrigin = if (uri?.scheme != null && uri.host != null) buildString {
        val scheme = uri.scheme.lowercase()
        append(scheme).append("://").append(uri.host.lowercase().trimEnd('.'))
        val defaultPort = if (scheme == "https") 443 else 80
        if (uri.port >= 0 && uri.port != defaultPort) append(':').append(uri.port)
        append(uri.path.trimEnd('/'))
        uri.rawQuery?.let { append('?').append(it) }
    } else "invalid:${baseUrl.trim()}"
    val contract = tools.sortedBy { it.name }.joinToString("\n") { tool ->
        buildString {
            append(tool.name).append('|').append(tool.summary).append('|')
                .append(tool.method.uppercase()).append('|').append(tool.path)
            tool.params.sortedBy { it.name }.forEach { param ->
                append('|').append(param.name).append(':').append(param.description)
                    .append(':').append(param.required).append(':').append(param.type)
                    .append(':').append(param.body).append(':').append(param.default.orEmpty())
            }
        }
    }
    val material = listOf(
        id.trim(), normalizedOrigin, authType.trim().lowercase(), authHeader.trim().lowercase(), contract,
    ).joinToString("\u0000")
    return connectorScopePrefix(id) + digestHex(material)
}
