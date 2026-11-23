package com.openminis.app.cottranslate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-cot-translate] User preferences for the reasoning translation feature.
 * Backed by the app's standard prefs file (NOT the vscreen/memory namespaces);
 * the toggle is read-only cold (UI gates the feature before any request).
 */
object CotTranslationPrefs {
    private const val PREFS = "cot_translation"

    /**
     * [T-cot-translate-live-gate] Bumped on every write so already-composed
     * thinking blocks re-read [enabled] — the old one-shot remember meant
     * turning the feature on in Settings never reached an open chat.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_PRIVACY_ACK = "privacy_ack"

    fun enabled(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        _revision.value++
    }

    fun provider(context: android.content.Context): TranslationProvider =
        providerFromStored(prefs(context).getString(KEY_PROVIDER, null))

    /** Pure readback — "LLM"/"GOOGLE"/"BING" → enum; unknown/missing → BING. */
    internal fun providerFromStored(stored: String?): TranslationProvider =
        TranslationProvider.entries.firstOrNull { it.name == stored }
            ?: TranslationProvider.BING

    fun setProvider(context: android.content.Context, value: TranslationProvider) {
        prefs(context).edit().putString(KEY_PROVIDER, value.name).apply()
        _revision.value++
    }

    /** Target language tag, or "auto" (follows the app locale at request time). */
    fun targetLanguage(context: android.content.Context): String =
        prefs(context).getString(KEY_LANGUAGE, "auto") ?: "auto"

    fun setTargetLanguage(context: android.content.Context, value: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, value.ifBlank { "auto" }).apply()
        _revision.value++
    }

    /** True once the user accepted the privacy notice (content leaves the device). */
    fun privacyAcknowledged(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_PRIVACY_ACK, false)

    fun setPrivacyAcknowledged(context: android.content.Context) {
        prefs(context).edit().putBoolean(KEY_PRIVACY_ACK, true).apply()
    }

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
}
