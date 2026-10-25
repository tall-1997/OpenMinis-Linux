package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App-level persisted "auto-compact on threshold" toggle.
 *
 * Mirrors iOS `AIChatViewModel.autoCompactEnabled`, whose backing store is the
 * UserDefaults key `autoCompactOnThreshold` (AIChatViewModel.swift:853). The
 * key name is reused verbatim so the two platforms stay greppable as one
 * feature.
 *
 * Semantics:
 *   - true (the default): crossing the compact threshold before a send
 *     compacts silently and then sends.
 *   - false: the same crossing PROMPTS the user.
 * Threshold percent defaults to 90 and is user-configurable.
 *
 * Global rather than per-session on purpose: iOS persists it so "future
 * conversations inherit it", which is the whole point of the one-tap opt-in
 * offered on the prompt.
 */
object AutoCompactPrefs {
    private const val PREFS = "minis_auto_compact_prefs"
    private const val KEY_ENABLED = "autoCompactOnThreshold"
    private const val KEY_THRESHOLD = "autoCompactThresholdPercent"
    const val DEFAULT_THRESHOLD_PERCENT = 90

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedEnabled: Boolean = true

    @Volatile
    private var cachedThresholdPercent: Int = DEFAULT_THRESHOLD_PERCENT

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Capture the app context and warm the cache. Called from MinisApp.onCreate,
     * so [isEnabled] is safe from call sites that have no Context — the same
     * arrangement [FastModePrefs] uses.
     */
    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, true)
        cachedThresholdPercent = prefs(context).getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD_PERCENT)
            .coerceIn(50, 95)
    }

    /** Context-free read. True before [prime] runs (auto-compact on by default). */
    fun isEnabled(): Boolean = cachedEnabled

    fun thresholdPercent(): Int = cachedThresholdPercent.coerceIn(50, 95)

    fun setThresholdPercent(percent: Int) {
        cachedThresholdPercent = percent.coerceIn(50, 95)
        appContext?.let {
            prefs(it).edit().putInt(KEY_THRESHOLD, cachedThresholdPercent).apply()
        }
    }

    fun setThresholdPercent(context: Context, percent: Int) {
        cachedThresholdPercent = percent.coerceIn(50, 95)
        prefs(context).edit().putInt(KEY_THRESHOLD, cachedThresholdPercent).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Context-free write for the in-chat one-tap opt-in ("Compact & always
     * auto-compact"), which runs from the ViewModel where no Activity context
     * is at hand. No-ops the persist step if [prime] never ran, but still
     * updates the cache so the current process behaves as asked.
     */
    fun setEnabled(enabled: Boolean) {
        cachedEnabled = enabled
        appContext?.let { prefs(it).edit().putBoolean(KEY_ENABLED, enabled).apply() }
    }
}
