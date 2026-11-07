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

    // [T-plugin-key-stable-identity] #283: keys are keyed by the connector
    // scope (derived from the stable catalog id), NOT by the full
    // configurationKey() fingerprint. The fingerprint folds in every tool
    // summary and param description, so any remote-catalog wording tweak used
    // to orphan the key — the visible result was "every update blanks the key
    // and asks for it again". Secrets now survive catalog rewording;
    // isInstalled() still gates tool availability, just not secret visibility.
    private fun apiKeyStorageKey(pluginId: String): String =
        connectorScopePrefix(pluginId) + "apikey"

    /** Pure migration pick: first non-blank legacy fingerprint key under the scope. */
    internal fun legacyApiKey(legacy: Map<String, *>, pluginId: String): String? {
        val prefix = connectorScopePrefix(pluginId)
        val stableKey = apiKeyStorageKey(pluginId)
        return legacy.keys.asSequence()
            .filter { it.startsWith(prefix) && it != stableKey }
            .mapNotNull { legacy[it] as? String }
            .firstOrNull { it.isNotBlank() }
    }

    fun apiKey(context: Context, plugin: PluginRegistry.RemotePlugin): String? {
        val prefs = EncryptedPrefsFactory.safeCreate(context, KEYS_FILE)
        val stableKey = apiKeyStorageKey(plugin.id)
        prefs.getString(stableKey, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val recovered = legacyApiKey(prefs.all, plugin.id) ?: return null
        prefs.edit().putString(stableKey, recovered).apply()
        return recovered
    }

    fun setApiKey(context: Context, plugin: PluginRegistry.RemotePlugin, key: String?) {
        val prefs = EncryptedPrefsFactory.safeCreate(context, KEYS_FILE)
        val stableKey = apiKeyStorageKey(plugin.id)
        if (key.isNullOrBlank()) {
            // Clear every key variant under the scope: the stable key must stay
            // the single authoritative slot, or migration would resurrect a
            // cleared key from a stale fingerprint entry on the next read.
            prefs.edit().apply {
                remove(stableKey)
                prefs.all.keys
                    .filter { it.startsWith(connectorScopePrefix(plugin.id)) && it != stableKey }
                    .forEach(::remove)
            }.apply()
        } else {
            prefs.edit().putString(stableKey, key.trim()).apply()
        }
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
