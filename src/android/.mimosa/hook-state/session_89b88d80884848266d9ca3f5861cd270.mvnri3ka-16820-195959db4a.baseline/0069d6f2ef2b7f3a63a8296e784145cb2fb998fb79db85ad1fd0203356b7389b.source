package com.openminis.app.service

import android.os.Build

/**
 * Whether this device's SystemUI can host a live notification template.
 *
 * Android 16 Live Updates (`ProgressStyle`, `requestPromotedOngoing`,
 * chronometer, indeterminate progress) are safe only in AOSP SystemUI.
 * HyperOS, ZUI, ColorOS, OriginOS, One UI and the other forks keep their
 * own island window inside the SystemUI process. They re-inflate that
 * window for every chronometer tick and every progress/promotion update,
 * and they do not release the old view. After a while the SystemUI heap
 * fragments, the process is killed, and the user sees the system UI restart.
 *
 * Identity is the fingerprint / manufacturer, not a cached permission bit.
 * `canPostPromotedNotifications()` returns true on several of these forks
 * even though their renderer is not the AOSP one.
 */
internal object SystemUiHost {

    data class Signals(
        val manufacturer: String = "",
        val brand: String = "",
        val display: String = "",
        val fingerprint: String = "",
    )

    fun allowsLiveNotificationTemplates(signals: Signals = current()): Boolean {
        val blob = listOf(signals.manufacturer, signals.brand, signals.display, signals.fingerprint)
            .joinToString(" ")
            .lowercase()
        return blob.contains("google") ||
            blob.contains("/aosp_") ||
            blob.contains("aosp/") ||
            blob.contains("sdk_gphone")
    }

    fun current(): Signals = Signals(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        brand = Build.BRAND.orEmpty(),
        display = Build.DISPLAY.orEmpty(),
        fingerprint = Build.FINGERPRINT.orEmpty(),
    )
}
