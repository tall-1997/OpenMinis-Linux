package com.openminis.app.ui.chat

import android.os.Handler
import android.os.Looper
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker

/**
 * [T-android-attach-picker-suppress] Single handoff helper for every external
 * picker the attach menu launches: camera, photo/video picker, document
 * picker.
 *
 * #215 root cause: the camera entry set the overlay-suppress gate before
 * launch (#451) but the photo/file entries never did. Fullscreen, the system
 * picker backgrounds Minis, and the bg-only capsule rule then pops the
 * floating capsule on top of the picker whenever a tool is running or a
 * completion lingers. Multi-window keeps Minis foregrounded, so the capsule
 * never appears — which is exactly why the field report was "works in
 * multi-task, broken fullscreen". Routing all three entries through this one
 * gate removes the asymmetry.
 *
 * Launch is deferred one main-looper turn: the attach menu is a Compose
 * Popup whose window is torn down in the same frame as the menu item's
 * onClick, so launching the system picker after the Popup has fully
 * unmounted keeps the handoff clean on every Android version.
 */
internal fun launchExternalPickerAfterMenuDismiss(launch: () -> Unit) {
    SessionActivityTracker.setExternalPickerSuppressActive(true)
    Handler(Looper.getMainLooper()).post {
        runCatching(launch).onFailure { t ->
            // The launch never reached the picker, so no result callback
            // will fire to release the gate. Clear it here so a later bg
            // event shows the capsule normally again.
            AppLogger.warning("AttachPicker", "external picker launch failed: ${t.message}")
            SessionActivityTracker.setExternalPickerSuppressActive(false)
        }
    }
}
