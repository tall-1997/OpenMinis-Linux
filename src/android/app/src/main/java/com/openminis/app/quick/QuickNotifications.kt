package com.openminis.app.quick

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

/**
 * [E-batch-quick-invocation] Notification plumbing for the quick surface:
 * the floating ball's silent foreground-service channel and the completion
 * notification a quick-panel send posts when the unattended run finishes.
 *
 * The completion notification deep-links `minis://session/<id>` — the same
 * route ScheduledAgentRunner / AutomationAgentRunner already use, handled by
 * MainActivity's VIEW filter.
 */
object QuickNotifications {

    private const val TAG = "QuickNotifications"

    /** Result notifications ("quick prompt finished"). Default importance. */
    const val CHANNEL_RESULTS = "minis_quick_results"

    /** Floating-ball foreground service. LOW importance: must never beep. */
    const val CHANNEL_BALL = "minis_quick_ball"

    /** Stable id for the ball's FGS notification (one per process). */
    const val BALL_NOTIFICATION_ID = 47_101

    /**
     * Id for completion notices. One per session, so a second send into the
     * same session replaces the previous notice instead of piling up.
     */
    fun completionNotificationId(sessionId: String): Int =
        ("quick_$sessionId").hashCode() and 0x7FFFFFFF

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_RESULTS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RESULTS,
                    context.getString(R.string.quick_notify_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.quick_notify_channel_name)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_BALL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_BALL,
                    context.getString(R.string.quick_ball_notification_title),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    /**
     * The VIEW intent that opens [sessionId] in the main app. Also used by the
     * panel's "open session" button so both paths land identically.
     */
    fun openSessionIntent(context: Context, sessionId: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("minis://session/$sessionId")).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Post the "quick prompt finished" notice. Lockscreen policy mirrors
     * ScheduledAgentRunner: the reply preview is model output, so the public
     * (lockscreen) version shows only a benign stand-in.
     *
     * POST_NOTIFICATIONS (Android 13+) may be denied — a SecurityException
     * here means the user will find the result in the session, not the shade.
     */
    fun postCompletion(context: Context, sessionId: String, preview: String, ok: Boolean) {
        ensureChannels(context)
        val openPi = PendingIntent.getActivity(
            context,
            completionNotificationId(sessionId),
            openSessionIntent(context, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.getString(R.string.quick_notify_title)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Minis")
            // Same lockscreen stand-in the scheduled-task runner uses — the
            // preview is model output and must not show while locked.
            .setContentText(context.getString(R.string.notify_scheduled_done_locked))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(openPi)
            // The long-press action and the notification body open the session.
            .addAction(0, context.getString(R.string.quick_panel_open_session), openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(completionNotificationId(sessionId), notification)
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "POST_NOTIFICATIONS denied — quick completion notice suppressed")
        }
    }
}
