package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [#28] Persists the user's "outbound privacy masking" preference.
 *
 * When enabled, [com.openminis.app.security.OutboundMasker] rewrites
 * credential/e-mail/phone-shaped substrings in every request payload before
 * it leaves the process toward the model provider. Chat history stored on
 * the device is never rewritten — only the bytes on the wire.
 *
 * Default OFF — the user decided the feature must be opt-in ("默认关"): a
 * pattern masker can corrupt payloads the task legitimately needs verbatim
 * (e.g. "extract the e-mails from this file"), so nobody gets masked output
 * they did not ask for.
 */
object OutboundMaskPrefs {
    private const val PREFS = "outbound_mask"
    private const val KEY = "enabled"
    private const val DEFAULT_ENABLED = false

    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Hot-path read used by the provider pipeline before any work. */
    val isEnabled: Boolean get() = _enabled.value

    /** Call once early — from MinisApp.onCreate. Idempotent. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = if (p.contains(KEY)) p.getBoolean(KEY, DEFAULT_ENABLED) else DEFAULT_ENABLED
    }

    /** UI toggle. Persists eagerly. */
    fun setEnabled(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
        _enabled.value = value
    }
}
