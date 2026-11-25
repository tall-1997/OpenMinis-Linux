package com.openminis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * [T-oauth-wait-fgs] A `dataSync` foreground service that exists only while an
 * OAuth sign-in waits for its localhost redirect - see [OAuthWaitKeepAlive] for
 * why. It does no work; being in the foreground is the whole point.
 *
 * Its own type rather than AgentForegroundService (mediaPlayback, which a
 * sign-in is not) or BackupForegroundService (whose lifecycle is a backup's).
 *
 * Lifecycle: [OAuthWaitKeepAlive.begin] starts it for every flow; it stops
 * ITSELF when [OAuthWaitKeepAlive.active] reaches 0 or the last flow passes its
 * deadline. Nobody calls stopService: stopping a service between
 * startForegroundService and its startForeground is exactly the "did not then
 * call startForeground" crash, and stopSelfResult with the latest start id
 * cannot race a start that is already on its way.
 *
 * Compatibility (minSdk 26, targetSdk 35):
 *  - 26-28: ServiceCompat.startForeground drops the type; the channel is
 *    created first, idempotently.
 *  - 29+: started with FOREGROUND_SERVICE_TYPE_DATA_SYNC, matching the manifest
 *    (required from targetSdk 34). Not shortService: that is 34+ only and
 *    capped at 3 minutes, shorter than a sign-in may take.
 *  - 31+: a refused start (ForegroundServiceStartNotAllowedException) is caught
 *    in [start] and in [onStartCommand]; the sign-in continues unprotected.
 *  - 33+: a denied POST_NOTIFICATIONS only hides the notification; the service
 *    still runs, and this flow never asks for the permission.
 *  - 35: the dataSync budget is handled in [onTimeout] by stopping at once.
 */
class OAuthWaitService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every startForegroundService must be answered with startForeground
        // within ~5 s, even one delivered after the flow already ended - so
        // this comes first, before any other work, and only then do we decide
        // whether to stay.
        if (!goForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val n = OAuthWaitKeepAlive.prune()
        if (n == 0) {
            AppLogger.info(TAG, "[OAuthWait] service started after the flows ended - stopping at once")
            stopNow("no-active-flows")
            return START_NOT_STICKY
        }
        AppLogger.info(TAG, "[OAuthWait] service foreground active=$n startId=$startId")
        if (!observing) {
            observing = true
            observe()
        }
        // NOT_STICKY: after process death there is no flow left to protect;
        // a restarted service would only show a stale notification.
        return START_NOT_STICKY
    }

    private fun observe() {
        scope.launch {
            OAuthWaitKeepAlive.active.collectLatest { n ->
                if (n == 0) {
                    stopNow("all-flows-ended")
                    return@collectLatest
                }
                // Sleep to the earliest deadline, then release what expired;
                // the count change restarts this block.
                val next = OAuthWaitKeepAlive.nextDeadline() ?: return@collectLatest
                delay((next - OAuthWaitKeepAlive.now()).coerceAtLeast(0L) + 1)
                OAuthWaitKeepAlive.prune()
            }
        }
    }

    /** True when the service is now in the foreground. */
    private fun goForeground(): Boolean {
        ensureChannel(this)
        return try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                // Ignored below API 29 by ServiceCompat.
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (31+) is an
            // IllegalStateException; a missing FGS-type permission is a
            // SecurityException. Either way: no protection, sign-in unaffected.
            AppLogger.error(TAG, "[OAuthWait] startForeground refused: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun stopNow(reason: String) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Only if no newer start is on its way; that one re-evaluates.
        val stopped = stopSelfResult(lastStartId)
        AppLogger.info(TAG, "[OAuthWait] service stop reason=$reason stopped=$stopped")
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.oauth_wait_notif_title))
            .setContentText(getString(R.string.oauth_wait_notif_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Swiping Minis Ultra away also closes the Custom Tab, which lives in Minis Ultra's
     * task: no redirect can come any more, so stop instead of holding a
     * notification for a sign-in that is gone.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        OAuthWaitKeepAlive.clear("task-removed")
        stopNow("task-removed")
        super.onTaskRemoved(rootIntent)
    }

    /**
     * The dataSync time budget is used up (Android 15+). Stop NOW: missing
     * the short grace period after this callback kills the whole process.
     * A sign-in (minutes) should never get here.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.warning(TAG, "[OAuthWait] dataSync time limit reached - stopping")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Deprecated("Replaced by onTimeout(Int, Int) on API 35")
    override fun onTimeout(startId: Int) = onTimeout(startId, 0)

    override fun onDestroy() {
        scope.cancel()
        observing = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OAuthWait"
        const val CHANNEL_ID = "oauth_wait"
        private const val NOTIFICATION_ID = 4301

        /**
         * Called from [OAuthWaitKeepAlive.begin], i.e. from the user's Sign In
         * tap with Minis Ultra in the foreground. Still guarded: on 31+ a start from
         * the background throws ForegroundServiceStartNotAllowedException, and
         * nothing here may break the sign-in.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, OAuthWaitService::class.java))
            } catch (e: Exception) {
                val kind = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                ) "ForegroundServiceStartNotAllowedException" else e.javaClass.simpleName
                AppLogger.error(TAG, "[OAuthWait] could not start service ($kind): ${e.message} - sign-in continues without it")
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.oauth_wait_notif_channel),
                    // LOW: visible, silent, no heads-up.
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
