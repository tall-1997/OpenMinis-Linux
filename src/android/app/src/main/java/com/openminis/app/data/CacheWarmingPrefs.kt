package com.openminis.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings switch for prompt cache warming ([com.openminis.app.provider.PromptCacheWarmer]).
 * SharedPreferences, same pattern as [ToolLimitPrefs]: an object singleton
 * primed once with the app context, unprimed reads return the default so unit
 * tests and early callers never need an Android handle.
 *
 * DEFAULT OFF (task 4 requirement): warming sends extra billable requests, so
 * it ships dark until the user (or a future UI toggle) flips the switch.
 */
object CacheWarmingPrefs {
    const val PREFS = "minis_cache_warming"
    private const val KEY_ENABLED = "cacheWarmingEnabled"

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    private val _revision = MutableStateFlow(0)

    /** Bumps on every write so observers can re-read cheaply (ToolLimitPrefs pattern). */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun prime(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _revision.value++
    }

    /** Whether the warming hook may send refresh requests at all. Default false. */
    fun enabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, false) ?: false

    fun setEnabled(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        _revision.value++
    }
}
