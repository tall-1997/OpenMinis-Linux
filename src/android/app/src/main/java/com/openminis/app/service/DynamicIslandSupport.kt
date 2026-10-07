package com.openminis.app.service

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * [T-android-dynamic-island] Capability probe for the two "dynamic island"
 * surfaces this app speaks:
 *
 *  1. AOSP Android 16 (Baklava, API 36) "Live Updates" — the promoted-ongoing
 *     ProgressStyle chip. Requires SDK 36+ AND
 *     `NotificationManager.canPostPromotedNotifications()` (the per-app user
 *     grant, togglable at runtime — hence uncached, re-probed on every
 *     foreground transition).
 *
 *  2. [T-android-hyperos-island] Xiaomi HyperOS 小米超级岛 — the first-party
 *     `miui.focus.param` protocol (see [HyperOsIsland]). Requires a Xiaomi/
 *     Redmi/POCO host whose OS speaks the OS3 island protocol. The per-app
 *     focus-notification *permission* is a separate slow binder probe, so it
 *     is NOT part of "capable" (the settings toggle stays usable); it gates
 *     [isDynamicIslandActive] via the cached [HyperOsIsland.focusPermissionCached],
 *     refreshed on background threads.
 *
 * The HyperOS branch is checked FIRST: forked SystemUIs report
 * `canPostPromotedNotifications() == true` even though their renderer is not
 * the AOSP one — the AOSP probe alone would misroute a HyperOS device onto
 * the promoted-ProgressStyle path that [SystemUiHost] exists to avoid.
 *
 * As of 2026-07 the AOSP path only actually returns true on Pixel 6+ hardware
 * running the Android 16 QPR that shipped Live Updates; everywhere else the
 * guards short-circuit and the mutual-exclusion logic degrades cleanly to the
 * existing overlay + plain-notification behavior.
 */
object DynamicIslandSupport {

    private const val TAG = "DynamicIslandSupport"

    /**
     * True when this device can render a dynamic island right now — either
     * the AOSP Live-Updates grant or the HyperOS island protocol. Runtime-safe
     * on all API levels and all OEMs.
     */
    fun isDynamicIslandCapable(context: Context): Boolean {
        if (HyperOsIsland.isHyperOsHost()) {
            return HyperOsIsland.isIslandCapable(context)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        return try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.canPostPromotedNotifications() == true
        } catch (t: Throwable) {
            // Defensive: some early/partial Baklava builds may throw if the
            // feature isn't fully wired. Treat any failure as "not capable"
            // so we fall back to the overlay + plain-notification path.
            Log.w(TAG, "canPostPromotedNotifications() failed: ${t.message}")
            false
        }
    }

    /**
     * True when a dynamic island should be the ACTIVE status surface: the
     * device is capable AND the user enabled the toggle. This is the single
     * predicate that (a) selects the promoted / HyperOS-focus notification
     * branch and (b) short-circuits the floating overlay so the two never
     * render at once.
     *
     * HyperOS additionally requires the cached per-app focus-notification
     * permission — without it the island cannot render, so the overlay must
     * stay the status surface (the notification still posts as a quiet row).
     */
    fun isDynamicIslandActive(context: Context, userEnabled: Boolean): Boolean {
        if (!userEnabled) return false
        if (HyperOsIsland.isHyperOsHost()) {
            return HyperOsIsland.isIslandCapable(context) &&
                HyperOsIsland.focusPermissionCached()
        }
        return isDynamicIslandCapable(context)
    }

    /**
     * False on HyperOS, ZUI and every other forked SystemUI. Those forks
     * report the Live Updates permission as granted, then re-inflate the
     * promoted template in their own island until SystemUI is killed.
     * Callers must post a static row instead of [isDynamicIslandActive].
     */
    fun allowsLiveNotificationTemplates(): Boolean =
        SystemUiHost.allowsLiveNotificationTemplates()
}
