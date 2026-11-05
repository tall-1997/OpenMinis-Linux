package com.openminis.app.offload

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

enum class RepeatMode { ONCE, DAILY, WEEKDAYS }

/**
 * Manages scheduling and cancelling alarms/timers using Android AlarmManager.
 * Alarm metadata is persisted in SharedPreferences as a JSON array so it
 * survives process restarts.
 */
class AlarmOffloadManager(private val context: Context) {

    companion object {
        private const val TAG = "AlarmOffloadManager"
        private const val PREFS_NAME = "minis_alarms_prefs"
        private const val KEY_ALARMS = "alarms_json"
        const val CHANNEL_ID = "minis_alarms"
        private const val CHANNEL_NAME = "Minis Alarms & Timers"

        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_ALARM_LABEL = "alarm_label"
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarms and timers scheduled by the Minis agent"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // ── Scheduling ──────────────────────────────────────────────────────

    /**
     * Schedule an alarm at a specific hour:minute.
     * Returns a human-readable confirmation string.
     */
    fun scheduleAlarm(label: String, hour: Int, minute: Int, repeatMode: RepeatMode): String {
        val id = UUID.randomUUID().toString()
        val requestCode = id.hashCode() and 0x7FFFFFFF // positive int

        // [T-android-alarm-rearm-chain] One shared "next occurrence" computation
        // for every mode: the first shot is registered below and the RECEIVER
        // re-arms each following occurrence after every fire (see
        // [onAlarmFired]) — the same receiver-reschedule design
        // ScheduledTaskManager uses because setRepeating is inexact and Doze
        // can delay it by hours.
        val triggerAt = nextOccurrenceMs(hour, minute, repeatMode)

        val pendingIntent = buildPendingIntent(id, requestCode, label)

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent,
            )
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "Cannot schedule exact alarm – missing SCHEDULE_EXACT_ALARM permission: ${e.message}")
            return "Error: exact alarm permission not granted. Please allow exact alarms in system settings."
        }

        val entry = JSONObject().apply {
            put("id", id)
            put("requestCode", requestCode)
            put("label", label)
            put("hour", hour)
            put("minute", minute)
            put("repeatMode", repeatMode.name)
            put("type", "alarm")
            put("triggerAtMs", triggerAt)
        }
        persistAlarm(entry)

        val timeStr = "%02d:%02d".format(hour, minute)
        return "Alarm scheduled: \"$label\" at $timeStr ($repeatMode) [id=$id]"
    }

    /**
     * Schedule a countdown timer that fires after [durationSec] seconds.
     */
    fun scheduleTimer(label: String, durationSec: Int): String {
        val id = UUID.randomUUID().toString()
        val requestCode = id.hashCode() and 0x7FFFFFFF
        val triggerAt = System.currentTimeMillis() + durationSec * 1000L

        val pendingIntent = buildPendingIntent(id, requestCode, label)

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent,
            )
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "Cannot schedule exact alarm: ${e.message}")
            return "Error: exact alarm permission not granted."
        }

        val entry = JSONObject().apply {
            put("id", id)
            put("requestCode", requestCode)
            put("label", label)
            put("durationSec", durationSec)
            put("type", "timer")
            put("triggerAtMs", triggerAt)
        }
        persistAlarm(entry)

        return "Timer scheduled: \"$label\" in ${durationSec}s [id=$id]"
    }

    // ── Listing / Cancelling ────────────────────────────────────────────

    fun listAlarms(): String {
        // First sweep: drop ONCE alarms / timers whose triggerAtMs has passed.
        // The receiver also cleans up on fire, but if it was killed by an OEM
        // power-saver between schedule and trigger the prefs entry would
        // otherwise stick around forever.
        sweepExpiredOnce()

        val alarms = loadAlarms()
        if (alarms.length() == 0) return "No active alarms or timers."

        val now = System.currentTimeMillis()
        val sb = StringBuilder("Active alarms/timers (${alarms.length()}):\n")
        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            sb.append("  - [${a.getString("type")}] \"${a.getString("label")}\" id=${a.getString("id")}")
            if (a.has("hour")) {
                sb.append(" at %02d:%02d".format(a.getInt("hour"), a.getInt("minute")))
                sb.append(" (${a.optString("repeatMode", "ONCE")})")
            } else if (a.has("durationSec")) {
                sb.append(" duration=${a.getInt("durationSec")}s")
            }
            // DAILY/WEEKDAYS may have a triggerAtMs from the *first* fire
            // that's now in the past — that's normal (the receiver re-arms
            // each occurrence and overwrites triggerAtMs; a stale value just
            // means the alarm has not fired since it was scheduled). Only
            // mark non-repeating entries as [expired]; repeating entries we
            // leave unannotated.
            val triggerAt = a.optLong("triggerAtMs", 0L)
            val mode = a.optString("repeatMode", "ONCE")
            val nonRepeating = a.getString("type") == "timer" || mode == "ONCE"
            if (nonRepeating && triggerAt in 1L..now) {
                sb.append(" [expired]")
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Remove ONCE alarms / timers whose triggerAtMs has already passed.
     * Called from [listAlarms] as a cheap GC and from [AlarmReceiver] right
     * after the alarm fires. Idempotent and safe to call repeatedly.
     */
    fun sweepExpiredOnce(): Int {
        val alarms = loadAlarms()
        if (alarms.length() == 0) return 0
        val now = System.currentTimeMillis()
        val kept = JSONArray()
        var removed = 0
        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            val mode = a.optString("repeatMode", "ONCE")
            val nonRepeating = a.optString("type") == "timer" || mode == "ONCE"
            val triggerAt = a.optLong("triggerAtMs", 0L)
            if (nonRepeating && triggerAt in 1L..now) {
                removed++
                continue
            }
            kept.put(a)
        }
        if (removed > 0) {
            prefs.edit().putString(KEY_ALARMS, kept.toString()).apply()
            AppLogger.info(TAG, "swept $removed expired ONCE alarm(s)/timer(s)")
        }
        return removed
    }

    /**
     * Called from [AlarmReceiver] right after an alarm fires. ONCE alarms /
     * timers are dropped from prefs so the next `android-alarm list` call no
     * longer surfaces them.
     *
     * [T-android-alarm-rearm-chain] DAILY/WEEKDAYS are RE-ARMED here instead
     * of being left to the OS: every mode registers through
     * setExactAndAllowWhileIdle, which is a one-shot, so the receiver owns the
     * repeat chain — the next occurrence is computed from hour:minute
     * ([nextOccurrenceMs]), persisted as the entry's triggerAtMs and
     * registered again ([registerExact]). Without this, a repeating alarm
     * fired exactly once and the chain died.
     */
    fun onAlarmFired(alarmId: String) {
        val alarms = loadAlarms()
        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            if (a.optString("id") != alarmId) continue
            val mode = a.optString("repeatMode", "ONCE")
            val nonRepeating = a.optString("type") == "timer" || mode == "ONCE"
            if (nonRepeating) {
                alarms.remove(i)
                prefs.edit().putString(KEY_ALARMS, alarms.toString()).apply()
                AppLogger.info(TAG, "alarm id=$alarmId mode=$mode fired — removed from prefs")
                return
            }
            // [T-android-alarm-rearm-chain] Repeating alarm: arm the next
            // occurrence. A malformed entry (unknown repeatMode, missing
            // hour/minute) can never be re-armed — drop it rather than leave
            // a zombie that listAlarms surfaces forever.
            val repeat = runCatching { RepeatMode.valueOf(mode) }.getOrNull()
            if (repeat == null || !a.has("hour") || !a.has("minute")) {
                alarms.remove(i)
                prefs.edit().putString(KEY_ALARMS, alarms.toString()).apply()
                AppLogger.warning(TAG, "alarm id=$alarmId mode=$mode not re-armable — dropped from prefs")
                return
            }
            // Anchor the search on the just-fired slot (stored triggerAtMs,
            // or now when the fire was late), so an early delivery can never
            // re-arm the same slot and tight-loop the alarm.
            val firedAtMs = maxOf(System.currentTimeMillis(), a.optLong("triggerAtMs", 0L))
            val next = nextOccurrenceMs(a.getInt("hour"), a.getInt("minute"), repeat, firedAtMs)
            a.put("triggerAtMs", next)
            prefs.edit().putString(KEY_ALARMS, alarms.toString()).apply()
            val registered = registerExact(
                a.optString("id"),
                a.optInt("requestCode"),
                a.optString("label", "Alarm"),
                next,
            )
            AppLogger.info(
                TAG,
                "alarm id=$alarmId mode=$mode fired — re-armed for $next (registered=$registered)",
            )
            return
        }
        AppLogger.warning(TAG, "alarm id=$alarmId fired but no matching prefs entry")
    }

    /**
     * [T-android-alarm-boot-rearm] Re-register every persisted, still-live
     * alarm with AlarmManager. Called from [AlarmReceiver]'s BOOT_COMPLETED /
     * MY_PACKAGE_REPLACED branch: pending intents do not survive a reboot (or
     * an app update), so without this sweep every offload alarm silently died.
     *
     * Repeating alarms re-register at their next occurrence computed from
     * hour:minute, which also self-heals a trigger window missed while the
     * device was powered off. ONCE alarms / timers re-register only if their
     * stored trigger is still in the future — [sweepExpiredOnce] drops the
     * rest first.
     *
     * @return the number of alarms re-registered.
     */
    fun rescheduleAll(): Int {
        sweepExpiredOnce()
        val alarms = loadAlarms()
        val now = System.currentTimeMillis()
        var count = 0
        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            val id = a.optString("id")
            val requestCode = a.optInt("requestCode")
            // Same malformed-entry guard as cancelAllAlarms.
            if (id.isEmpty() || requestCode == 0) continue
            val mode = a.optString("repeatMode", "ONCE")
            val next: Long = if (a.optString("type") == "timer" || mode == "ONCE") {
                val triggerAt = a.optLong("triggerAtMs", 0L)
                if (triggerAt <= now) continue
                triggerAt
            } else {
                val repeat = runCatching { RepeatMode.valueOf(mode) }.getOrNull() ?: continue
                if (!a.has("hour") || !a.has("minute")) continue
                nextOccurrenceMs(a.getInt("hour"), a.getInt("minute"), repeat, now)
            }
            if (registerExact(id, requestCode, a.optString("label", "Alarm"), next)) count++
        }
        AppLogger.info(TAG, "rescheduleAll: re-registered $count alarm(s)")
        return count
    }

    fun cancelAlarm(alarmId: String): String {
        val alarms = loadAlarms()
        var found = false

        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            if (a.getString("id") == alarmId) {
                val requestCode = a.getInt("requestCode")
                val label = a.getString("label")
                val pi = buildPendingIntent(alarmId, requestCode, label)
                alarmManager.cancel(pi)
                pi.cancel()
                alarms.remove(i)
                found = true
                break
            }
        }

        if (!found) return "Error: no alarm found with id=$alarmId"

        prefs.edit().putString(KEY_ALARMS, alarms.toString()).apply()
        return "Alarm cancelled: id=$alarmId"
    }

    /**
     * Cancel every persisted alarm/timer. Mirrors apple-alarm `cancel --all`.
     * Returns the count cancelled (0 is a valid no-op). Each entry's
     * PendingIntent is cancelled individually so the OS-side scheduling is
     * cleared even if prefs become orphaned somehow.
     */
    fun cancelAllAlarms(): Int {
        val alarms = loadAlarms()
        val n = alarms.length()
        for (i in 0 until n) {
            val a = alarms.getJSONObject(i)
            val id = a.optString("id")
            val requestCode = a.optInt("requestCode")
            val label = a.optString("label", "Alarm")
            if (id.isEmpty() || requestCode == 0) continue
            val pi = buildPendingIntent(id, requestCode, label)
            alarmManager.cancel(pi)
            pi.cancel()
        }
        prefs.edit().remove(KEY_ALARMS).apply()
        return n
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * [T-android-alarm-rearm-chain] Next wall-clock occurrence of hour:minute
     * strictly after [afterMs], weekend-skipped for [RepeatMode.WEEKDAYS].
     *
     * Anchored on "strictly after" rather than ">= now" so a receiver that
     * fires a hair EARLY (or a stale triggerAtMs) can never re-arm the
     * just-fired slot and tight-loop the alarm. Shared by [scheduleAlarm]
     * (first shot), [onAlarmFired] (per-fire re-arm) and [rescheduleAll]
     * (BOOT) so all three agree on what "next" means.
     */
    private fun nextOccurrenceMs(
        hour: Int,
        minute: Int,
        mode: RepeatMode,
        afterMs: Long = System.currentTimeMillis(),
    ): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis <= afterMs) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (mode == RepeatMode.WEEKDAYS) skipToNextWeekday(cal)
        return cal.timeInMillis
    }

    /**
     * Register one alarm at [triggerAtMs]. Mirrors ScheduledTaskManager's
     * registration trio: setExactAndAllowWhileIdle first, falling back to
     * inexact setAndAllowWhileIdle when the S+ exact-alarm permission was
     * revoked, so a degraded permission means "fires eventually" instead of
     * silently dropping the repeat chain. Used by the re-arm and BOOT paths,
     * where no caller sees an error string.
     *
     * @return true when the alarm was registered (exactly or inexactly).
     */
    private fun registerExact(id: String, requestCode: Int, label: String, triggerAtMs: Long): Boolean {
        val pi = buildPendingIntent(id, requestCode, label)
        return try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            true
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "exact-alarm denied for id=$id (${e.message}); falling back to inexact")
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                true
            } catch (t2: Throwable) {
                AppLogger.error(TAG, "inexact fallback failed for id=$id: ${t2.message}")
                false
            }
        }
    }

    private fun buildPendingIntent(id: String, requestCode: Int, label: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, id)
            putExtra(EXTRA_ALARM_LABEL, label)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun skipToNextWeekday(cal: Calendar) {
        while (true) {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) break
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    private fun loadAlarms(): JSONArray {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun persistAlarm(entry: JSONObject) {
        val alarms = loadAlarms()
        alarms.put(entry)
        prefs.edit().putString(KEY_ALARMS, alarms.toString()).apply()
    }
}
