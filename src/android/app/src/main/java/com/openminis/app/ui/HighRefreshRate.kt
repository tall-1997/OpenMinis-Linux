package com.openminis.app.ui

import android.app.Activity
import android.content.Context
import android.os.Build

/** Asks the window for the display's highest refresh rate. Off leaves the system default. */
object HighRefreshRate {
    private const val PREFS = "display_prefs"
    private const val KEY = "high_refresh"

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
    }

    fun apply(activity: Activity) {
        if (Build.VERSION.SDK_INT < 23) return
        val window = activity.window
        val lp = window.attributes
        if (!enabled(activity)) {
            if (lp.preferredDisplayModeId != 0) {
                lp.preferredDisplayModeId = 0
                window.attributes = lp
            }
            return
        }
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
        val modes = display?.supportedModes ?: return
        val best = modes.maxByOrNull { it.refreshRate } ?: return
        lp.preferredDisplayModeId = best.modeId
        lp.preferredRefreshRate = best.refreshRate
        window.attributes = lp
    }
}
