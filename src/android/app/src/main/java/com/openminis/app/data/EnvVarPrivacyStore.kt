package com.openminis.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's "Privacy Mode" preference for shell-execute output.
 *
 * When enabled, [EnvVarRedactor] masks any environment variable value
 * that appears verbatim in a shell tool's stdout/stderr before that
 * text is fed back to the model. The user still sees the unmasked
 * output in chat; only the bytes flowing into the agent's context are
 * rewritten.
 *
 * Default OFF — opt-in to avoid surprising users who rely on echoing
 * values back during debugging.
 *
 * Mirrors iOS `EnvVarPrivacyStore`.
 */
object EnvVarPrivacyStore {
    private const val PREFS = "envvar_privacy"
    private const val KEY = "privacy_mode_enabled"
    /** Default ON — secrets stay out of the model's context unless the user opts out. */
    private const val DEFAULT_ENABLED = true

    private var prefs: com.openminis.app.config.PrefsHandle? = null

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Hot-path read used by the redactor before any work. */
    val isEnabled: Boolean get() = _enabled.value

    /** Call once early — from MinisApp.onCreate. Idempotent. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = com.openminis.app.config.Prefs.file(PREFS)
        prefs = p
        _enabled.value = if (p.contains(KEY)) p.bool(KEY, DEFAULT_ENABLED) else DEFAULT_ENABLED
    }

    /** UI toggle. Persists eagerly. */
    fun setEnabled(value: Boolean) {
        prefs?.set(KEY, value)
        _enabled.value = value
    }
}
