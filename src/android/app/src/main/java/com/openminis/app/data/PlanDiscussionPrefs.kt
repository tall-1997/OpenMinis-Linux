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
        cachedMode = readMode(prefs())
    }

    fun mode(): Mode = cachedMode

    fun isEnabled(): Boolean = cachedMode != Mode.OFF

    fun setMode(mode: Mode) {
        cachedMode = mode
        if (appContext != null) {
            // 两个键一起写：mode 和 enabled 描述同一个状态，
            // 分两次落盘会出现「模式是新值、开关还是旧值」的中间态
            prefs().write {
                putString(KEY_MODE, mode.wire)
                putBoolean(KEY_ENABLED, mode != Mode.OFF)
            }
        }
    }

    fun setMode(context: Context, mode: Mode) {
        cachedMode = mode
        prefs().write {
            putString(KEY_MODE, mode.wire)
            putBoolean(KEY_ENABLED, mode != Mode.OFF)
        }
    }

    /** Chat-menu compatibility: on maps to AUTO (smarter default). */
    fun setEnabled(enabled: Boolean) {
        setMode(if (enabled) Mode.AUTO else Mode.OFF)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        setMode(context, if (enabled) Mode.AUTO else Mode.OFF)
    }

    private fun readMode(sp: com.openminis.app.config.PrefsHandle): Mode {
        val stored = sp.stringOrNull(KEY_MODE)
        if (stored != null) return Mode.fromWire(stored)
        return if (sp.bool(KEY_ENABLED, false)) Mode.ALWAYS else Mode.OFF
    }

    private fun prefs() =
        com.openminis.app.config.Prefs.file(PREFS)
}
