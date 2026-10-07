package com.openminis.app.mcp.server

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * Persistent config for the built-in MCP server.
 *
 * enabled + port live in plain SharedPreferences (they're not secrets);
 * the bearer token lives in EncryptedSharedPreferences via
 * [EncryptedPrefsFactory], following the provider_secrets / oauth_prefs pattern.
 */
class McpServerConfigStore(context: Context) {

    companion object {
        private const val PREFS = "mcp_server"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_SHELL_POLICY = "shell_policy"

        /** shell_exec gate policies. auto = allow most commands; strict = ask/deny risky ones. */
        const val SHELL_POLICY_AUTO = "auto"
        const val SHELL_POLICY_STRICT = "strict"

        fun validShellPolicy(p: String?): Boolean = p == SHELL_POLICY_AUTO || p == SHELL_POLICY_STRICT
    }

    data class Config(
        val enabled: Boolean = false,
        val port: Int = McpHttpServer.DEFAULT_PORT,
        val token: String? = null,
        val shellPolicy: String = SHELL_POLICY_AUTO,
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secretPrefs: SharedPreferences = EncryptedPrefsFactory.safeCreate(context, "mcp_server_secrets")

    private val _config = MutableStateFlow(load())
    val config: StateFlow<Config> = _config.asStateFlow()

    private fun load(): Config = Config(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        port = prefs.getInt(KEY_PORT, McpHttpServer.DEFAULT_PORT)
            .takeIf { McpHttpServer.validPort(it) } ?: McpHttpServer.DEFAULT_PORT,
        token = secretPrefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() },
        shellPolicy = prefs.getString(KEY_SHELL_POLICY, null)
            ?.takeIf { validShellPolicy(it) } ?: SHELL_POLICY_AUTO,
    )

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled && secretPrefs.getString(KEY_TOKEN, null).isNullOrBlank()) {
            regenerateToken()
        } else {
            _config.value = load()
        }
    }

    fun setPort(port: Int) {
        val valid = if (McpHttpServer.validPort(port)) port else McpHttpServer.DEFAULT_PORT
        prefs.edit().putInt(KEY_PORT, valid).apply()
        _config.value = load()
    }

    /** Persist shell_exec gate policy ("auto"|"strict"). Invalid values are ignored. */
    fun setShellPolicy(policy: String) {
        if (!validShellPolicy(policy)) return
        prefs.edit().putString(KEY_SHELL_POLICY, policy).apply()
        _config.value = load()
    }

    /** Generate and persist a fresh token. Returns the new token. */
    fun regenerateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        secretPrefs.edit().putString(KEY_TOKEN, token).apply()
        _config.value = load()
        return token
    }

    /** Removes the stored token (auth off). */
    fun clearToken() {
        secretPrefs.edit().remove(KEY_TOKEN).apply()
        _config.value = load()
    }

    fun currentToken(): String? = _config.value.token

    /** Current shell gate policy string ("auto"|"strict"); "auto" when no store. */
    fun currentShellPolicy(): String = _config.value.shellPolicy
}