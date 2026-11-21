package com.openminis.app.data

import android.content.Context

/**
 * [T-android-enhanced-cache] Persists whether the user has acknowledged the
 * one-time Enhanced Cache confirmation dialog.
 *
 * The Enhanced Cache *toggle state* itself is per-ViewModel memory only (not
 * persisted — mirrors iOS `AIChatViewModel.enhancedCacheEnabled`). Only the
 * "user has seen and accepted the extra-billing warning" flag is durable, so
 * the confirmation AlertDialog appears exactly once per install. Mirrors iOS
 * UserDefaults key `enhancedCacheConfirmed`.
 */
object EnhancedCachePrefs {
    private const val PREFS = "minis_enhanced_cache_prefs"
    private const val KEY_CONFIRMED = "enhancedCacheConfirmed"

    private fun prefs() =
        com.openminis.app.config.Prefs.file(PREFS)

    fun isConfirmed(context: Context): Boolean =
        prefs().bool(KEY_CONFIRMED, false)

    fun setConfirmed(context: Context) {
        prefs().set(KEY_CONFIRMED, true)
    }
}
