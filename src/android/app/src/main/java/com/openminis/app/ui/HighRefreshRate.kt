package com.openminis.app.ui

import android.app.Activity
import android.content.Context
import android.os.Build

/**
 * One display mode the window may request. [id] is [android.view.Display.Mode.getModeId].
 * Selection stays free of Android types so the resolution rule can be tested on the JVM.
 */
internal data class RefreshMode(
    val id: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

/**
 * Highest refresh rate at the current resolution. A faster mode at another
 * resolution is ignored so the window does not drop to a lower panel size.
 * If nothing matches the current size, the absolute highest rate is used.
 */
internal fun pickHighestRefresh(
    modes: List<RefreshMode>,
    width: Int,
    height: Int,
): RefreshMode? {
    if (modes.isEmpty()) return null
    val same = modes.filter { it.width == width && it.height == height }
    return (if (same.isNotEmpty()) same else modes).maxWithOrNull(
        compareBy<RefreshMode> { it.refreshRate }.thenBy { it.id },
    )
}

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
            if (lp.preferredDisplayModeId != 0 || lp.preferredRefreshRate != 0f) {
                lp.preferredDisplayModeId = 0
                lp.preferredRefreshRate = 0f
                window.attributes = lp
            }
            return
        }
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        } ?: return
        val current = display.mode
        val best = pickHighestRefresh(
            display.supportedModes.map {
                RefreshMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
            },
            current.physicalWidth,
            current.physicalHeight,
        ) ?: return
        // One write. The mode id already carries the refresh rate. Setting
        // preferredRefreshRate as well makes some OEM compositors apply the
        // rate after the mode and drop back to 60Hz.
        if (lp.preferredDisplayModeId == best.id && lp.preferredRefreshRate == 0f) return
        lp.preferredDisplayModeId = best.id
        lp.preferredRefreshRate = 0f
        window.attributes = lp
    }
}
