package com.openminis.app.plugins

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.util.EncryptedPrefsFactory

/**
 * Installed online OpenAPI plugins + encrypted API keys.
 *
 * Adapted from XINCODE-Public PluginStore online flags
 * (GPL-3.0-or-later, https://github.com/kusesad-1122/XINCODE-Public).
 */
object OnlinePluginStore {
    private const val FLAGS = "plugin_online_flags"
    private const val KEYS_FILE = "plugin_online_keys"

    private fun fingerprintKey(pluginId: String) = "fingerprint_$pluginId"

    fun isInstalled(context: Context, plugin: PluginRegistry.RemotePlugin): Boolean {
        val prefs = context.getSharedPreferences(FLAGS, Context.MODE_PRIVATE)
        return prefs.getBoolean(plugin.id, false) &&
            prefs.getString(fingerprintKey(plugin.id), null) == plugin.configurationKey()
    }

    fun installedIds(context: Context): Set<String> {
        val registry = PluginRegistry.cached(context)
        return registry.filter { isInstalled(context, it) }.mapTo(mutableSetOf()) { it.id }
    }

    fun setInstalled(context: Context, plugin: PluginRegistry.RemotePlugin, installed: Boolean) {
        val prefs = context.getSharedPreferences(FLAGS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(plugin.id, installed)
            .apply {
                if (installed) putString(fingerprintKey(plugin.id), plugin.configurationKey())
                else remove(fingerprintKey(plugin.id))
            }
            .apply()
        if (!installed) {
            val keys = EncryptedPrefsFactory.safeCreate(context, KEYS_FILE)
            val prefix = connectorScopePrefix(plugin.id)
            keys.edit().apply {
                remove(plugin.id)
                keys.all.keys.filter { it.startsWith(prefix) }.forEach(::remove)
            }.apply()
            ConnectorNetworkPolicy.clearPluginConfigurations(context, plugin.id)
            OnlineApiTool.evictNetworkClient(plugin)
        }
    }

    fun apiKey(context: Context, plugin: PluginRegistry.RemotePlugin): String? {
        if (!isInstalled(context, plugin)) return null
        val prefs = EncryptedPrefsFactory.safeCreate(context, KEYS_FILE)
        return prefs.getString(plugin.configurationKey(), null)?.takeIf { it.isNotBlank() }
    }

    fun setApiKey(context: Context, plugin: PluginRegistry.RemotePlugin, key: String?) {
        val prefs = EncryptedPrefsFactory.safeCreate(context, KEYS_FILE)
        val storageKey = plugin.configurationKey()
        if (key.isNullOrBlank()) prefs.edit().remove(storageKey).apply()
        else prefs.edit().putString(storageKey, key.trim()).apply()
    }

    fun toolDefinitions(context: Context): List<AgentToolDefinition> {
        val installed = installedIds(context)
        if (installed.isEmpty()) return emptyList()
        return PluginRegistry.cached(context)
            .filter { it.id in installed && isInstalled(context, it) }
            .flatMap { plugin -> plugin.tools.map { OnlineApiTool.definition(plugin, it) } }
    }

    /**
     * [T-android-agent-tools-memo] Cheap change-stamp for [toolDefinitions]:
     * covers both inputs (installed flags + the cached remote registry). Reading
     * two SharedPreferences values is orders of magnitude cheaper than building
     * the definitions, so the agent loop can key its memo on this per access.
     */
    fun toolDefinitionsStamp(context: Context): Long {
        val flags = context.getSharedPreferences(FLAGS, Context.MODE_PRIVATE).all
        val registry = context.getSharedPreferences("plugin_registry", Context.MODE_PRIVATE)
            .getString("plugin_registry_cache", null)
        return 31L * flags.hashCode() + (registry?.hashCode() ?: 0)
    }
}
