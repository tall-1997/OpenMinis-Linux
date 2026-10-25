package com.openminis.app.data

import android.content.Context

/**
 * Plan-discussion mode:
 * - OFF: never run
 * - AUTO: skip short chitchat; run for task-shaped messages
 * - ALWAYS: every non-empty user send (legacy "enabled" behaviour)
 *
 * Existing KEY_ENABLED=true migrates to ALWAYS so previously-on users keep
 * the same cadence until they pick Auto in Settings.
 */
object PlanDiscussionPrefs {
    private const val PREFS = "minis_plan_discussion_prefs"
    private const val KEY_ENABLED = "planDiscussionEnabled"
    private const val KEY_MODE = "planDiscussionMode"

    enum class Mode(val wire: String) {
        OFF("off"),
        AUTO("auto"),
        ALWAYS("always"),
        ;

        companion object {
            fun fromWire(raw: String?): Mode = when (raw?.trim()?.lowercase()) {
                AUTO.wire -> AUTO
                ALWAYS.wire -> ALWAYS
                else -> OFF
            }
        }
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedMode: Mode = Mode.OFF

    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedMode = readMode(prefs(context))
    }

    fun mode(): Mode = cachedMode

    fun isEnabled(): Boolean = cachedMode != Mode.OFF

    fun setMode(mode: Mode) {
        cachedMode = mode
        appContext?.let { ctx ->
            prefs(ctx).edit()
                .putString(KEY_MODE, mode.wire)
                .putBoolean(KEY_ENABLED, mode != Mode.OFF)
                .apply()
        }
    }

    fun setMode(context: Context, mode: Mode) {
        cachedMode = mode
        prefs(context).edit()
            .putString(KEY_MODE, mode.wire)
            .putBoolean(KEY_ENABLED, mode != Mode.OFF)
            .apply()
    }

    /** Chat-menu compatibility: on maps to AUTO (smarter default). */
    fun setEnabled(enabled: Boolean) {
        setMode(if (enabled) Mode.AUTO else Mode.OFF)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        setMode(context, if (enabled) Mode.AUTO else Mode.OFF)
    }

    private fun readMode(sp: android.content.SharedPreferences): Mode {
        val stored = sp.getString(KEY_MODE, null)
        if (stored != null) return Mode.fromWire(stored)
        return if (sp.getBoolean(KEY_ENABLED, false)) Mode.ALWAYS else Mode.OFF
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
