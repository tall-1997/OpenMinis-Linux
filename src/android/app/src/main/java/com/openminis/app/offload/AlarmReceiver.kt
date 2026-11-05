package com.openminis.app.offload

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * BroadcastReceiver that fires when an alarm or timer triggers.
 * Shows a high-priority notification with the alarm label.
 *
 * [T-android-alarm-rearm-chain] Also owns the repeat chain: every fire calls
 * [AlarmOffloadManager.onAlarmFired], which re-arms the next occurrence for
 * DAILY/WEEKDAYS entries (all modes register as one-shot
 * setExactAndAllowWhileIdle alarms). BOOT_COMPLETED / MY_PACKAGE_REPLACED
 * re-register every persisted alarm via
 * [AlarmOffloadManager.rescheduleAll].
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val NOTIFICATION_GROUP = "minis_alarm_group"

        /**
         * Ceiling for the whole BOOT reschedule (two prefs sweeps +
         * AlarmManager re-registrations). A manifest-declared receiver gets
         * roughly 10s of background-broadcast budget before the system ANRs
         * it; 8s leaves headroom (same constant as the scheduled /
         * automation receivers).
         */
        private const val HANDOFF_TIMEOUT_MS = 8_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        // [T-android-alarm-boot-rearm] BOOT_COMPLETED / MY_PACKAGE_REPLACED:
        // AlarmManager pending intents do not survive a reboot or an app
        // update, so the old "the OS re-fires the persisted alarms on its own"
        // assumption never held — every offload alarm used to die silently.
        // Re-register them all here, on the app's coroutine scope behind
        // goAsync() with a hard timeout — the same short-handoff shape
        // AutomationRuleReceiver / ScheduledTaskAlarmReceiver use for their
        // own BOOT resync.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AppLogger.info(TAG, "${intent.action} — rescheduling persisted alarms")
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            val scope = (appContext as? com.openminis.app.MinisApp)?.appCoroutineScopes?.application
            if (scope == null) {
                // Half-built app (GH#156 shape): nothing we can do, but the
                // broadcast must still be released.
                pendingResult.finish()
                return
            }
            scope.launch {
                try {
                    withTimeout(HANDOFF_TIMEOUT_MS) {
                        try {
                            com.openminis.app.scheduled.ScheduledTaskManager(appContext).rescheduleAll()
                        } catch (t: Throwable) {
                            AppLogger.warning(TAG, "scheduled-task rescheduleAll failed: ${t.message}")
                        }
                        try {
                            AlarmOffloadManager(appContext).rescheduleAll()
                        } catch (t: Throwable) {
                            AppLogger.warning(TAG, "alarm rescheduleAll failed: ${t.message}")
                        }
                    }
                } catch (t: Throwable) {
                    // Includes TimeoutCancellationException — release the
                    // broadcast rather than holding it until the system ANRs.
                    AppLogger.warning(TAG, "${intent.action} reschedule timed out: ${t.message}")
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        val alarmId = intent.getStringExtra(AlarmOffloadManager.EXTRA_ALARM_ID) ?: "unknown"
        val label = intent.getStringExtra(AlarmOffloadManager.EXTRA_ALARM_LABEL) ?: "Alarm"

        AppLogger.debug(TAG, "Alarm triggered: id=$alarmId label=$label")

        // [T-android-alarm-rearm-chain] Drop the entry from prefs for ONCE
        // alarms / timers so the next `android-alarm list` call no longer
        // surfaces them. DAILY/WEEKDAYS are RE-ARMED inside onAlarmFired —
        // setExactAndAllowWhileIdle is a one-shot, so the receiver owns the
        // repeat chain and must schedule the next occurrence itself.
        if (alarmId != "unknown") {
            try {
                AlarmOffloadManager(context).onAlarmFired(alarmId)
            } catch (e: Throwable) {
                AppLogger.warning(TAG, "onAlarmFired($alarmId) failed: ${e.message}")
            }
        }

        val notificationId = alarmId.hashCode() and 0x7FFFFFFF

        // Launch intent – open the app when the notification is tapped
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPi = if (launchIntent != null) {
            PendingIntent.getActivity(
                context,
                notificationId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }

        val notification = NotificationCompat.Builder(context, AlarmOffloadManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Minis Alarm")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setGroup(NOTIFICATION_GROUP)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .apply { if (contentPi != null) setContentIntent(contentPi) }
            .build()

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.notify(notificationId, notification)
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "Cannot post notification – missing POST_NOTIFICATIONS permission: ${e.message}")
        }
    }
}
