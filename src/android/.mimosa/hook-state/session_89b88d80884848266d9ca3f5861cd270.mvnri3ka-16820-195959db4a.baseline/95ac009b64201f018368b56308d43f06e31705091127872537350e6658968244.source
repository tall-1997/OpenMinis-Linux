package com.openminis.app.plugins

import android.content.Context
import com.openminis.app.tools.FetchUrlGuard
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Remote OpenAPI-lite plugin catalog.
 *
 * Adapted from XINCODE-Public PluginRegistry
 * (GPL-3.0-or-later, https://github.com/kusesad-1122/XINCODE-Public).
 */
object PluginRegistry {

    const val REGISTRY_URL =
        "https://raw.githubusercontent.com/kusesad-1122/XINCODE-Public/main/docs/plugins/registry.json"
    private const val PREFS = "plugin_registry"
    private const val CACHE_KEY = "plugin_registry_cache"

    data class RemoteParam(
        val name: String,
        val description: String,
        val required: Boolean,
        val type: String,
        val body: Boolean,
        val default: String?,
    )

    data class RemoteTool(
        val name: String,
        val summary: String,
        val method: String,
        val path: String,
        val params: List<RemoteParam>,
    )

    data class RemotePlugin(
        val id: String,
        val name: String,
        val description: String,
        val icon: String,
        val authType: String,
        val authHeader: String,
        val baseUrl: String,
        val tools: List<RemoteTool>,
        val category: String = "",
    )

    fun parse(text: String): List<RemotePlugin> {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return emptyList()
        }
        val arr = root.optJSONArray("plugins") ?: return emptyList()
        val out = mutableListOf<RemotePlugin>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val id = p.optString("id").trim()
            val name = p.optString("name").trim()
            val baseUrl = p.optString("base_url").trim()
            if (id.isBlank() || name.isBlank() || baseUrl.isBlank()) continue
            val toolsJson = p.optJSONArray("tools") ?: continue
            val tools = mutableListOf<RemoteTool>()
            for (t in 0 until toolsJson.length()) {
                val tj = toolsJson.optJSONObject(t) ?: continue
                val tName = tj.optString("name").trim()
                if (tName.isBlank()) continue
                val params = mutableListOf<RemoteParam>()
                val pj = tj.optJSONArray("params")
                if (pj != null) {
                    for (k in 0 until pj.length()) {
                        val o = pj.optJSONObject(k) ?: continue
                        val pn = o.optString("name").trim()
                        if (pn.isBlank()) continue
                        params.add(
                            RemoteParam(
                                name = pn,
                                description = o.optString("description"),
                                required = o.optBoolean("required", false),
                                type = o.optString("type", "string").ifBlank { "string" },
                                body = o.optBoolean("body", false),
                                default = o.optString("default").ifBlank { null },
                            ),
                        )
                    }
                }
                tools.add(
                    RemoteTool(
                        name = tName,
                        summary = tj.optString("summary"),
                        method = tj.optString("method", "GET").uppercase(),
                        path = tj.optString("path", "/"),
                        params = params,
                    ),
                )
            }
            if (tools.isEmpty()) continue
            out.add(
                RemotePlugin(
                    id = id,
                    name = name,
                    description = p.optString("description"),
                    icon = p.optString("icon"),
                    authType = p.optString("auth_type", "none").ifBlank { "none" },
                    authHeader = p.optString("auth_header"),
                    baseUrl = baseUrl,
                    tools = tools,
                    category = p.optString("category"),
                ),
            )
        }
        return out
    }

    /** Fetch remote catalog; on failure return last-good cache. Second = live. */
    fun fetch(context: Context): Pair<List<RemotePlugin>, Boolean> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            FetchUrlGuard.blockedReason(REGISTRY_URL)?.let { throw IllegalArgumentException(it) }
            var currentUrl = REGISTRY_URL
            val origin = URI(REGISTRY_URL)
            var redirects = 0
            val client = OkHttpClient.Builder()
                .dns(FetchUrlGuard.publicInternetDns())
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            var text: String? = null
            while (text == null) {
                FetchUrlGuard.blockedReason(currentUrl)?.let { throw IllegalArgumentException(it) }
                val response = client.newCall(
                    Request.Builder().url(currentUrl).header("Accept", "application/json").build(),
                ).execute()
                text = response.use {
                    if (it.code in 300..399) {
                        val location = it.header("Location") ?: throw IllegalStateException("redirect missing Location")
                        if (redirects++ >= 2) throw IllegalStateException("too many registry redirects")
                        val next = URI(currentUrl).resolve(location)
                        if (!next.scheme.equals(origin.scheme, true) || !next.host.equals(origin.host, true) ||
                            effectivePort(next) != effectivePort(origin)
                        ) throw IllegalArgumentException("cross-origin registry redirect blocked")
                        currentUrl = next.toString()
                        null
                    } else {
                        if (!it.isSuccessful) throw IllegalStateException("registry HTTP ${it.code}")
                        it.body?.string() ?: throw IllegalStateException("empty registry response")
                    }
                }
            }
            val catalogText = checkNotNull(text)
            val plugins = parse(catalogText)
            if (plugins.isEmpty()) throw IllegalStateException("empty registry")
            prefs.edit().putString(CACHE_KEY, catalogText).apply()
            return plugins to true
        } catch (_: Exception) {
            val cached = prefs.getString(CACHE_KEY, null)
            if (!cached.isNullOrBlank()) {
                return parse(cached) to false
            }
            return emptyList<RemotePlugin>() to false
        }
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", true) -> 443
        uri.scheme.equals("http", true) -> 80
        else -> -1
    }

    fun cached(context: Context): List<RemotePlugin> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CACHE_KEY, null) ?: return emptyList()
        return parse(text)
    }
}
