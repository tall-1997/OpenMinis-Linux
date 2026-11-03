package com.openminis.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.openminis.app.MainActivity

/**
 * [T-backup-undo-restore] Relaunches the app after a restore has replaced
 * files underneath every live repository.
 *
 * A restore undo swaps `minis.db`, `shared_prefs/`, and the session tree. The
 * database handle is closed first (`AppDatabase.closeForRestore`), but
 * `SharedPreferences` keeps its parsed map in memory, and every repository,
 * ViewModel singleton and workspace path caches state derived from those
 * files. Rebuilding all of it by hand means finding every cache and proving
 * none was missed — a process restart makes the on-disk state authoritative in
 * one step, which is why every backup tool on Android ends this way.
 *
 * The AlarmManager hop matters: the restart intent has to survive the death of
 * this process. Scheduling it 300 ms out and only then killing the PID is the
 * standard trick — calling `startActivity` and killing immediately races the
 * activity launch against process teardown on slow devices.
 */
object AppRestart {

    private const val RESTART_DELAY_MS = 300L

    fun restart(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pending = PendingIntent.getActivity(
            app,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val scheduled = runCatching {
            val alarms = app.getSystemService(AlarmManager::class.java)
                ?: return@runCatching false
            alarms.set(AlarmManager.RTC, System.currentTimeMillis() + RESTART_DELAY_MS, pending)
            true
        }.getOrDefault(false)
        if (!scheduled) {
            // No alarm service (some OEM builds strip it): launch directly.
            // The kill below may take the activity with it, so the user
            // re-opens the app manually — slower, never stuck.
            runCatching { app.startActivity(intent) }
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Request code for the relaunch intent; unique within the app. */
    private const val REQUEST_CODE = 0x5EED
}
