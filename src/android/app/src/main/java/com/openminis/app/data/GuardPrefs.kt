 package com.openminis.app.data
 
 import android.content.Context
 import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
 
 /**
  * [T-guard-stop-policy] The user-facing switch for the loop guard's hard
  * bound.
  *
  * WHY: `LoopGuard` can either STOP a session once the repeat ladder is
  * exhausted or keep advising. Stopping is the shipped default (a runaway
  * agent must not be allowed to burn the whole context), but a legitimate
  * long poll (waiting for a build, tailing a deploy) hits the same ladder and
  * had no escape hatch at all. This pref is that escape hatch: ON keeps the
  * current behaviour, OFF downgrades the last rung to a reminder.
  *
  * App-wide, not per session: it is a safety posture, and a session that
  * disables it should not silently re-enable it on the next chat.
  */
 object GuardPrefs {
 
    private const val PREFS = "minis_guard_prefs"
    private const val KEY_STOP_ENABLED = "loopGuardStopEnabled"
    
    @Volatile
    private var appContext: Context? = null
    
    @Volatile
    private var cachedStopEnabled: Boolean = true

    /** Bumped on every write so a settings screen recomposes (same idiom as
     *  [AutoCompactPrefs]). */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision
    
    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    
    /**
     * Capture the app context and warm the cache. Called from MinisApp.onCreate,
     * so [stopEnabled] is safe from call sites that have no Context.
     */
    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedStopEnabled = prefs(context).getBoolean(KEY_STOP_ENABLED, true)
    }
    
    /** True (default) when the guard may stop the session. */
    fun stopEnabled(): Boolean = cachedStopEnabled
    
    fun setStopEnabled(context: Context, enabled: Boolean) {
        if (cachedStopEnabled == enabled) return
        cachedStopEnabled = enabled
        prefs(context).edit().putBoolean(KEY_STOP_ENABLED, enabled).apply()
        _revision.value += 1
    }
    
    /**
     * Context-free write for the settings row (which does have a Context);
     * kept for symmetry with [AutoCompactPrefs] so a test can drive it.
     */
    fun setStopEnabled(enabled: Boolean) {
        if (cachedStopEnabled == enabled) return
        cachedStopEnabled = enabled
        appContext?.let { prefs(it).edit().putBoolean(KEY_STOP_ENABLED, enabled).apply() }
        _revision.value += 1
    }
    
    /** Test seam: restore the shipped default. */
    internal fun resetForTest() {
        if (cachedStopEnabled == true) return
        cachedStopEnabled = true
        _revision.value += 1
    }
 }
 
