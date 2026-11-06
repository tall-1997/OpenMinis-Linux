package com.openminis.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

/**
 * [T-bg-overlay phase 2 fix] The one-shot "grant Display over other apps" nudge.
 *
 * Owned by its own object rather than the foreground service so the throttle
 * flag has an explicit lifetime, and so `AgentForegroundService` stays inside
 * its architecture ceiling as the status-surface work grows.
 */
internal object OverlayPermissionNudge {

    private const val TAG = "OverlayPermissionNudge"

    /**
     * `true` after the first emission this process so a single agent loop that
     * calls 10 tools doesn't post 10 identical heads-up notifications. Reset on
     * service destroy so a fresh launch after the user has had a chance to
     * think about it can remind them again.
     */
    @Volatile
    private var posted = false

    fun reset() {
        posted = false
    }

    fun maybePost(context: Context) {
        if (posted) return
        posted = true
        val app = context.applicationContext
        try {
            val managerIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${app.packageName}"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val tap = PendingIntent.getActivity(
                app,
                2,
                managerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val nudge = NotificationCompat.Builder(app, AgentForegroundService.OVERLAY_NUDGE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(app.getString(R.string.bg_overlay_nudge_title))
                .setContentText(app.getString(R.string.bg_overlay_nudge_body))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(app.getString(R.string.bg_overlay_nudge_body)),
                )
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            app.getSystemService(NotificationManager::class.java)
                ?.notify(AgentForegroundService.OVERLAY_NUDGE_NOTIFICATION_ID, nudge)
            AppLogger.debug(TAG, "overlay permission nudge posted (SAW not granted but toggle is ON)")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "overlay permission nudge failed: ${t.message}")
        }
    }
}
