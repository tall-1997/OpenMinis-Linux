package com.openminis.app.data

import android.content.Context

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
 * Threshold percent defaults to 75 and is user-configurable.
 *
 * [GH#74] The default moved 90 → 75: at 90 a long tool-heavy turn can cross the
 * provider's real ceiling between the pre-send check and the next iteration,
 * which is exactly the `context_length_exceeded` case the loop then has to
 * recover from. 75 leaves room for the summary + re-appended recent turns
 * before the hard limit. Explicit per-model overrides (and previously saved
 * global values) still win; only the untouched default changed.
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

    private fun prefs() = com.openminis.app.config.Prefs.file(PREFS)

    /**
     * Capture the app context and warm the cache. Called from MinisApp.onCreate,
     * so [isEnabled] is safe from call sites that have no Context — the same
     * arrangement [FastModePrefs] uses.
     */
    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedEnabled = prefs().bool(KEY_ENABLED, true)
        cachedThresholdPercent = prefs().int(KEY_THRESHOLD, DEFAULT_THRESHOLD_PERCENT)
            .coerceIn(50, 95)
    }

    /** Context-free read. True before [prime] runs (auto-compact on by default). */
    fun isEnabled(): Boolean = cachedEnabled

    fun thresholdPercent(): Int = cachedThresholdPercent.coerceIn(50, 95)

    fun setThresholdPercent(percent: Int) {
        cachedThresholdPercent = percent.coerceIn(50, 95)
        appContext?.let {
            prefs().set(KEY_THRESHOLD, cachedThresholdPercent)
        }
    }

    fun setThresholdPercent(context: Context, percent: Int) {
        cachedThresholdPercent = percent.coerceIn(50, 95)
        prefs().set(KEY_THRESHOLD, cachedThresholdPercent)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs().set(KEY_ENABLED, enabled)
    }

    /**
     * Context-free write for the in-chat one-tap opt-in ("Compact & always
     * auto-compact"), which runs from the ViewModel where no Activity context
     * is at hand. No-ops the persist step if [prime] never ran, but still
     * updates the cache so the current process behaves as asked.
     */
    fun setEnabled(enabled: Boolean) {
        cachedEnabled = enabled
        appContext?.let { prefs().set(KEY_ENABLED, enabled) }
    }
}
