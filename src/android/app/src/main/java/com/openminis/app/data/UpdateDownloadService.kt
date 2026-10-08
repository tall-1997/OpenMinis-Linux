package com.openminis.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * Foreground service that keeps an in-flight update download alive while the
 * user leaves the app, and mirrors its progress into a system notification.
 *
 * [T-update-download-fgs] The download itself runs in
 * [UpdateDownloadManager]'s process-wide scope — this service does NOT own
 * the download coroutine. It only:
 *
 *  1. holds a `dataSync` foreground slot so LMK doesn't kill the process
 *     mid-download (a bare coroutine dies with the process the moment the
 *     user switches away);
 *  2. collects [UpdateDownloadManager.state] and renders it as a progress
 *     notification, so the user can see the download is alive without
 *     coming back to the Settings screen.
 *
 * **dataSync 6h/24h cap (Android 14+):** a single APK download is minutes,
 * and the service stops itself the moment the download leaves the running
 * state — the cumulative cap would need ~240 full re-downloads per day to
 * bite. The AgentForegroundService incident (T-android-fgs-timeout-crash)
 * was a *long-lived* dataSync service; this one is strictly transient.
 *
 * **WorkManager fallback:** deliberately not implemented. The manager's
 * `.part` + ETag resume already survives process death; a 130 MB transfer
 * that outlives a 6-hour dataSync budget is not a realistic phone scenario.
 *
 * **Android 12+ background-start restriction:** [ensureStarted] is only
 * called from user interaction (Settings screen), so
 * `startForegroundService` is legal. If the OS still refuses (edge cases:
 * notification permission revoked mid-flow), we log and continue without
 * the service — the download degrades to the pre-service bare-coroutine
 * behavior rather than dying.
 */
class UpdateDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Enter the foreground BEFORE returning from onCreate — the
        // startForegroundService contract gives us ~5s to do this or the
        // system ANRs us. Indeterminate progress until the first state
        // emission lands (the manager emits immediately on collect).
        startInForeground(
            progress = -1f, doneFile = null, error = null,
            downloadedBytes = 0L, totalBytes = -1L,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-collect on every (re)start; StateFlow replays the current value.
        scope.launch {
            UpdateDownloadManager.state.collect { st ->
                if (!st.running) {
                    // Terminal state: done, failed, parked for metered
                    // confirmation, or idle. Post a final notification for
                    // done/failed, then get out of the foreground slot.
                    if (st.doneFile != null || st.error != null) {
                        updateNotification(
                            progress = st.progress, doneFile = st.doneFile, error = st.error,
                            downloadedBytes = st.downloadedBytes, totalBytes = st.totalBytes,
                        )
                    }
                    stopSelf()
                    return@collect
                }
                updateNotification(
                    progress = st.progress, doneFile = null, error = null,
                    downloadedBytes = st.downloadedBytes, totalBytes = st.totalBytes,
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(
        progress: Float,
        doneFile: File?,
        error: String?,
        downloadedBytes: Long,
        totalBytes: Long,
    ) {
        val notification = buildNotification(progress, doneFile, error, downloadedBytes, totalBytes)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(
        progress: Float,
        doneFile: File?,
        error: String?,
        downloadedBytes: Long,
        totalBytes: Long,
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(progress, doneFile, error, downloadedBytes, totalBytes))
    }

    // [T-update-download-fgs] The progress body is the ONLY string in this
    // service with positional format specifiers. update_notif_progress has
    // TWO of them (%1$s bytes / %2$s total); passing fewer args than the
    // string declares throws MissingFormatArgumentException from inside
    // onStartCommand and kills the process — exactly the 2.0.50 crash. The
    // StringFormatArgSafetyTest source scan keeps every call site honest.
    private fun buildNotification(
        progress: Float,
        doneFile: File?,
        error: String?,
        downloadedBytes: Long,
        totalBytes: Long,
    ): android.app.Notification {
        val ctx = this
        val deepLink = Uri.parse("minis://settings")
        val launchIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            setPackage(ctx.packageName)
        }
        val contentIntent = PendingIntent.getActivity(
            ctx,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        when {
            doneFile != null -> builder
                .setContentTitle(ctx.getString(R.string.update_notif_done_title))
                .setContentText(ctx.getString(R.string.update_notif_done_body))
                .setOngoing(false)
                .setAutoCancel(true)
            error != null -> builder
                .setContentTitle(ctx.getString(R.string.update_notif_failed_title))
                .setContentText(error)
                .setStyle(NotificationCompat.BigTextStyle().bigText(error))
                .setOngoing(false)
                .setAutoCancel(true)
            else -> {
                val pct = if (progress >= 0f) (progress * 100f).roundToInt() else -1
                builder
                    .setContentTitle(ctx.getString(R.string.update_notif_title))
                    .setContentText(
                        if (pct >= 0) ctx.getString(
                            R.string.update_notif_progress,
                            Formatter.formatFileSize(ctx, downloadedBytes.coerceAtLeast(0L)),
                            if (totalBytes > 0) Formatter.formatFileSize(ctx, totalBytes) else "?",
                        )
                        else ctx.getString(R.string.update_notif_indeterminate),
                    )
                    .setProgress(100, if (pct >= 0) pct else 0, pct < 0)
            }
        }
        return builder.build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.update_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val TAG = "UpdateDownloadService"
        const val CHANNEL_ID = "update_download"
        private const val NOTIF_ID = 4101

        /**
         * Bring the service up alongside a running download. Safe to call
         * repeatedly: onStartCommand re-subscribes, and a duplicate
         * startForegroundService is a no-op while the service lives.
         */
        fun ensureStarted(context: Context) {
            val appCtx = context.applicationContext
            try {
                ContextCompat.startForegroundService(
                    appCtx,
                    Intent(appCtx, UpdateDownloadService::class.java),
                )
            } catch (e: Exception) {
                // Android 12+ can refuse a background start; also covers
                // devices where dataSync FGS is blocked by policy. The
                // download keeps running as a bare coroutine — degraded
                // survival, not a failure.
                AppLogger.warning(TAG, "download service start refused: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /** Drop the foreground slot once the download is no longer running. */
        fun ensureStopped(context: Context) {
            val appCtx = context.applicationContext
            appCtx.stopService(Intent(appCtx, UpdateDownloadService::class.java))
        }
    }
}
