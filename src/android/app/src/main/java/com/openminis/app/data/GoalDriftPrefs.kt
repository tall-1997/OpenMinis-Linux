package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.goal.GoalDriftReconciler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [T-goal-drift-reconciler] The user-facing knob for the drift reconciler's
 * stall threshold ([GoalDriftReconciler.STALL_TURNS_BEFORE_NUDGE] is the
 * shipped default): how many consecutive tool turns an unchanged plan board
 * may sit through — while the session Goal is ACTIVE — before the host nudges
 * the model back toward the objective.
 *
 * App-wide, not per session: it is a safety posture for long-running agent
 * work, matching [GuardPrefs]. Higher = fewer nudges (more rope before the
 * reminder); lower = tighter supervision. Values are clamped to
 * [GoalDriftReconciler.STALL_TURNS_MIN]..[GoalDriftReconciler.STALL_TURNS_MAX]
 * on write AND on the reconciler side, so a hand-edited prefs file cannot
 * disable the net or bury it.
 */
object GoalDriftPrefs {

    private const val PREFS = "minis_goal_drift_prefs"
    private const val KEY_STALL_TURNS = "goalDriftStallTurns"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedStallTurns: Int = GoalDriftReconciler.STALL_TURNS_BEFORE_NUDGE

    /** Bumped on every write so a settings screen recomposes (same idiom as
     *  [GuardPrefs]). */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Capture the app context and warm the cache. Called from MinisApp.onCreate,
     * so [stallTurns] is safe from call sites that have no Context.
     */
    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedStallTurns = prefs(context).getInt(
            KEY_STALL_TURNS,
            GoalDriftReconciler.STALL_TURNS_BEFORE_NUDGE,
        ).coerceIn(GoalDriftReconciler.STALL_TURNS_MIN, GoalDriftReconciler.STALL_TURNS_MAX)
    }

    /** Consecutive silent tool turns tolerated before a drift nudge. */
    fun stallTurns(): Int = cachedStallTurns

    fun setStallTurns(context: Context, value: Int) {
        val clamped = value.coerceIn(GoalDriftReconciler.STALL_TURNS_MIN, GoalDriftReconciler.STALL_TURNS_MAX)
        if (cachedStallTurns == clamped) return
        cachedStallTurns = clamped
        prefs(context).edit().putInt(KEY_STALL_TURNS, clamped).apply()
        _revision.value += 1
    }

    /** Context-free write for the settings row (which does have a Context);
     *  kept for symmetry with [GuardPrefs] so a test can drive it. */
    fun setStallTurns(value: Int) {
        val clamped = value.coerceIn(GoalDriftReconciler.STALL_TURNS_MIN, GoalDriftReconciler.STALL_TURNS_MAX)
        if (cachedStallTurns == clamped) return
        cachedStallTurns = clamped
        appContext?.let { prefs(it).edit().putInt(KEY_STALL_TURNS, clamped).apply() }
        _revision.value += 1
    }

    /** Test seam: restore the shipped default. */
    internal fun resetForTest() {
        if (cachedStallTurns == GoalDriftReconciler.STALL_TURNS_BEFORE_NUDGE) return
        cachedStallTurns = GoalDriftReconciler.STALL_TURNS_BEFORE_NUDGE
        _revision.value += 1
    }
}
