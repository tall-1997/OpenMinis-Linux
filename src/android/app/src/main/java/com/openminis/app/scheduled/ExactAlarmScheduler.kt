package com.openminis.app.scheduled

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.openminis.app.logging.AppLogger

/**
 * [P2-alarm-dedup] Shared exact-alarm registration for the two alarm owners —
 * [ScheduledTaskManager] and [com.openminis.app.automation.AutomationRuleManager].
 * Both used to carry a verbatim copy of the register/cancel/PendingIntent
 * trio; a single implementation keeps the S+ SecurityException→inexact
 * fallback policy (and its logging) from drifting between them.
 *
 * Each owner supplies:
 *  - [intentFor]: builds the fire Intent (receiver class, action, id extra);
 *  - [requestCodeSalt]: namespace for the 31-bit request-code space, so two
 *    owners hashing the same id never collide ("automation_" for rules; the
 *    scheduled-task manager keeps its historic unsalted raw-id hash);
 *  - [kind]: log noun ("task" / "rule") so registration lines keep their
 *    owner-specific shape.
 */
class ExactAlarmScheduler(
    private val context: Context,
    private val tag: String,
    private val kind: String,
    private val requestCodeSalt: String? = null,
    private val intentFor: (Context, String) -> Intent,
) {

    /**
     * Register one exact alarm. Callers own the next-trigger computation (and
     * their null-trigger side effects); this is purely the OS registration
     * with the exact→inexact fallback.
     */
    fun registerExact(id: String, label: String?, triggerAtMs: Long) {
        val pi = buildPendingIntent(id)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            AppLogger.info(tag, "registered $kind=$id label=\"$label\" triggerAt=$triggerAtMs")
        } catch (e: SecurityException) {
            // S+ users may have revoked SCHEDULE_EXACT_ALARM — fall back
            // to inexact so we still fire eventually rather than dropping.
            AppLogger.warning(tag, "exact-alarm denied for $kind=$id (${e.message}); falling back to inexact")
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } catch (t2: Throwable) {
                AppLogger.error(tag, "inexact fallback failed for $kind=$id: ${t2.message}")
            }
        }
    }

    fun cancel(id: String) {
        val pi = buildPendingIntent(id)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun buildPendingIntent(id: String): PendingIntent {
        val requestCode = ((requestCodeSalt ?: "") + id).hashCode() and 0x7FFFFFFF
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intentFor(context, id), flags)
    }
}
