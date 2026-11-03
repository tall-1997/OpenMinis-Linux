package com.openminis.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-tool-result-aging] User-facing knob for the history-side token saver.
 *
 * Three levels, all of them *subtractive only* — nothing is enabled, no message
 * is removed, no output is lost (folded results keep their spill path or say
 * how to fetch it again):
 *
 *  - [Mode.OFF]         keep sending every historical tool result verbatim.
 *  - [Mode.BALANCED]    default: fold results older than the newest
 *                       [Mode.keepRecent] once the request is already large.
 *  - [Mode.AGGRESSIVE]  fold much earlier and much smaller — for users on
 *                       small-context models who would rather re-read a file
 *                       than pay for the whole transcript.
 *
 * The trigger is a *minimum*: the send path also fires when the estimated
 * request already crosses [WINDOW_RATIO] of the model's context window, so a
 * small-window model gets the saving even though its absolute char count never
 * reaches [Mode.triggerTokens].
 *
 * SharedPreferences-backed like [ToolLimitPrefs]; unprimed reads return
 * defaults so unit tests and early boot never see a null mode.
 */
object ContextSaverPrefs {
    const val PREFS = "minis_context_saver"

    /** Fraction of the model context window that forces a pass regardless of level. */
    const val WINDOW_RATIO = 0.45

    enum class Mode(
        val id: String,
        /** Estimated-token floor before the pass runs. */
        val triggerTokens: Int,
        /** Newest results kept verbatim. */
        val keepRecent: Int,
        /** Results at or under this size are not worth folding. */
        val minChars: Int,
    ) {
        // OFF keeps `keepRecent = 0` on purpose: the mode check runs first, and
        // a zero here would fold everything if a future caller forgot it.
        OFF("off", Int.MAX_VALUE, 0, Int.MAX_VALUE),
        BALANCED("balanced", 30_000, 6, 1_200),
        AGGRESSIVE("aggressive", 15_000, 3, 400),
        ;

        companion object {
            fun fromId(id: String?): Mode = Mode.entries.firstOrNull { it.id == id } ?: BALANCED
        }
    }

    private const val KEY_MODE = "mode"

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun prime(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _revision.value++
    }

    fun mode(): Mode = Mode.fromId(prefs?.getString(KEY_MODE, Mode.BALANCED.id))

    fun setMode(mode: Mode) {
        prefs?.edit()?.putString(KEY_MODE, mode.id)?.apply()
        _revision.value++
    }

    /**
     * Effective token trigger for [windowTokens]: the level's floor, lowered to
     * [WINDOW_RATIO] of the window when the model is small. Returns
     * [Int.MAX_VALUE] for [Mode.OFF] so the caller's comparison never fires.
     */
    fun triggerTokens(mode: Mode, windowTokens: Int): Int {
        if (mode == Mode.OFF) return Int.MAX_VALUE
        if (windowTokens <= 0) return mode.triggerTokens
        val byWindow = (windowTokens * WINDOW_RATIO).toInt().coerceAtLeast(1)
        return minOf(mode.triggerTokens, byWindow)
    }
}
