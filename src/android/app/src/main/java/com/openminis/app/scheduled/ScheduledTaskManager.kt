package com.openminis.app.scheduled

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.openminis.app.logging.AppLogger

/**
 * [T-android-scheduled-tasks-design] Schedules / cancels AlarmManager
 * entries for [ScheduledTask] rows held in [ScheduledTaskStore]. Pairs with
 * [ScheduledTaskAlarmReceiver] which fires on the trigger and hands the
 * task off to [ScheduledAgentRunner].
 *
 * Repeating tasks (DAILY / WEEKDAYS / CUSTOM) are NOT scheduled via
 * AlarmManager.setRepeating — that primitive is inexact on Android 19+
 * and Doze makes it worse. Instead the receiver re-schedules the next
 * occurrence after every fire, giving Doze-tolerant precision.
 */
class ScheduledTaskManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val store = ScheduledTaskStore(context)

    init { ensureNotificationChannel() }

    /** Read-only access to persistence for callers that only need data. */
    fun store(): ScheduledTaskStore = store

    fun list(): List<ScheduledTask> = store.all()
    fun get(taskId: String): ScheduledTask? = store.get(taskId)

    fun create(task: ScheduledTask): ScheduledTask {
        findIdentical(task)?.let { return it }
        store.upsert(task)
        if (task.enabled) registerAlarm(task)
        return task
    }

    /**
     * A retry of the same label, time, prompt and repeat must not mint a second
     * id after the caller deleted the id that was returned.
     */
    fun findIdentical(task: ScheduledTask): ScheduledTask? = store.all().firstOrNull {
        it.label == task.label &&
            it.timeOfDayHour == task.timeOfDayHour &&
            it.timeOfDayMinute == task.timeOfDayMinute &&
            it.repeatMode == task.repeatMode &&
            // [T-android-scheduled-triggers] Two tasks can share a label, a
            // prompt and a calendar repeat and still be different schedules —
            // "every 10m" and "every 30m" both carry repeatMode ONCE now. The
            // trigger payload has to be part of "identical", or creating the
            // second one silently returns the first.
            it.triggerKind == task.triggerKind &&
            it.delaySec == task.delaySec &&
            it.intervalSec == task.intervalSec &&
            it.maxFires == task.maxFires &&
            it.onCompletionOf == task.onCompletionOf &&
            it.customDays == task.customDays &&
            it.prompt == task.prompt &&
            it.targetMode == task.targetMode &&
            it.enabled == task.enabled
    }

    fun resolveId(idOrPrefix: String): String? = store.resolveId(idOrPrefix)

    fun update(task: ScheduledTask): ScheduledTask {
        cancelAlarm(task.id)
        // [T-android-scheduled-triggers] The editor has no controls for the
        // relative triggers — the assistant creates them, minis-scheduled shows
        // them. Rebuilding the row from the editor's fields alone would turn
        // "every 10m ×6" into a daily calendar task and wipe its run history;
        // merge what the editor does not own from the row stored right now.
        val stored = store.update(task.id) { prev -> mergeBookkeeping(task, prev) }
        if (stored == null) return task
        if (stored.enabled) registerAlarm(stored)
        return stored
    }

    fun setEnabled(taskId: String, enabled: Boolean) {
        // [T-android-scheduled-triggers] Re-enabling a relative trigger starts
        // it over: "after 30m" counts from now again and an interval gets its
        // full count back. A calendar task simply resumes its schedule.
        val stored = store.update(taskId) { t ->
            when {
                !enabled -> t.copy(enabled = false)
                t.usesAnchor -> t.copy(
                    enabled = true,
                    anchorMs = System.currentTimeMillis(),
                    triggeredCount = 0,
                )
                t.triggerKind == ScheduledTriggerKind.ON_COMPLETION ->
                    t.copy(enabled = true, triggeredCount = 0)
                else -> t.copy(enabled = true)
            }
        } ?: return
        if (enabled) registerAlarm(stored) else cancelAlarm(taskId)
    }

    fun delete(taskId: String): Boolean {
        val id = store.resolveId(taskId) ?: return false
        // One cancel is enough: cancelAlarm is idempotent (PendingIntent
        // equality), the second back-to-back call was pure noise.
        cancelAlarm(id)
        val removed = store.delete(id)
        return removed && store.get(id) == null
    }

    /**
     * Re-register every enabled task with AlarmManager. Called from
     * [com.openminis.app.offload.AlarmReceiver]'s BOOT_COMPLETED branch
     * so persisted tasks survive a device reboot.
     */
    fun rescheduleAll() {
        val tasks = store.all()
        if (tasks.isEmpty()) {
            AppLogger.info(TAG, "rescheduleAll: no tasks")
            return
        }
        var count = 0
        for (t in tasks) {
            if (!t.enabled) continue
            registerAlarm(t)
            count++
        }
        AppLogger.info(TAG, "rescheduleAll: re-registered $count enabled task(s)")
    }

    /**
     * Called by the receiver after firing — for repeating tasks, schedule
     * the next occurrence. ONCE tasks get disabled in-place (enabled=false)
     * so they linger in the list with their lastResult* metadata visible.
     *
     * @return whether this alarm delivery is the task's fire. A relative
     * trigger is checked and claimed in one atomic step, so a duplicate
     * delivery of the same fire returns false and must not run: the row has
     * already moved past its slot. Calendar deliveries always return true.
     */
    fun rescheduleNext(taskId: String): Boolean {
        val t = store.get(taskId) ?: return false
        if (t.usesAnchor) {
            // [T-android-scheduled-triggers] Counted HERE, when the alarm fires
            // and before the run starts, so the next slot never depends on a run
            // that may take minutes to record itself.
            //
            // [T-android-scheduled-fire-claim] Checked, counted and re-anchored
            // in ONE atomic step. A cold start driven by this very alarm also
            // runs rescheduleAll, which can read the row before it is counted,
            // see it overdue and arm it for "now" again — a second delivery of
            // the same fire. Now the second delivery finds the fire already
            // taken (the anchor has moved on), re-arms the real next slot the
            // stale registration replaced (same PendingIntent) and does not run.
            val now = System.currentTimeMillis()
            var arm = false
            val stored = store.update(taskId) { cur ->
                if (!cur.enabled || !relativeAlarmDue(cur, now)) return@update null
                val (next, a) = afterAlarmFire(cur, now) ?: return@update null
                arm = a
                next
            }
            if (stored == null) {
                val cur = store.get(taskId)
                AppLogger.info(
                    TAG,
                    "alarm for task=$taskId is not a due fire (enabled=${cur?.enabled}) — not running",
                )
                if (cur != null && cur.enabled) registerAlarm(cur)
                return false
            }
            if (arm) registerAlarm(stored)
            return true
        }
        // Event-driven: no alarm of its own. Nothing to advance here.
        if (t.triggerKind == ScheduledTriggerKind.ON_COMPLETION) return false
        if (t.repeatMode == ScheduledRepeatMode.ONCE) {
            // Mark fired ONCE task as disabled — keep row so the user can
            // see its last result, re-enable if they want to re-run.
            store.upsert(t.copy(enabled = false))
            return true
        }
        registerAlarm(t)
        return true
    }

    fun markFired(taskId: String, sessionId: String?, resultPreview: String?, ok: Boolean = true) {
        val now = System.currentTimeMillis()
        // [T-android-scheduled-tasks-run-records] Prepend a run record
        // (newest-first), capped at MAX_RUN_HISTORY. lastResult* are kept in
        // sync for back-compat but are no longer surfaced in the list UI.
        val run = ScheduledRun(firedAt = now, sessionId = sessionId, preview = resultPreview, ok = ok)
        // [T-android-scheduled-fire-claim] Atomic: a run finishing minutes
        // after its fire must not write back a count / anchor that the next
        // fire has moved on since.
        store.update(taskId) { t ->
            t.copy(
                lastFiredAt = now,
                lastResultPreview = resultPreview,
                lastResultSessionId = sessionId,
                runHistory = (listOf(run) + t.runHistory).take(ScheduledTask.MAX_RUN_HISTORY),
                fireCount = t.firesSoFar + 1,
            )
        }
    }

    private fun registerAlarm(task: ScheduledTask) {
        val triggerAt = task.nextTriggerMs() ?: run {
            AppLogger.warning(TAG, "task ${task.id} has no next trigger — skipping register")
            return
        }
        val pi = buildPendingIntent(task.id)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            AppLogger.info(TAG, "registered task=${task.id} label=\"${task.label}\" triggerAt=$triggerAt")
        } catch (e: SecurityException) {
            // S+ users may have revoked SCHEDULE_EXACT_ALARM — fall back
            // to inexact so we still fire eventually rather than dropping.
            AppLogger.warning(
                TAG,
                "exact-alarm denied for task=${task.id} (${e.message}); falling back to inexact",
            )
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (t2: Throwable) {
                AppLogger.error(TAG, "inexact fallback failed for task=${task.id}: ${t2.message}")
            }
        }
    }

    private fun cancelAlarm(taskId: String) {
        val pi = buildPendingIntent(taskId)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun buildPendingIntent(taskId: String): PendingIntent {
        val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val requestCode = taskId.hashCode() and 0x7FFFFFFF
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun ensureNotificationChannel() {
        // minSdk 26 (O): the SDK_INT guard was unreachable.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Posts when a scheduled task finishes running."
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ScheduledTaskManager"

        /**
         * [T-android-scheduled-fire-claim] Tolerance for an alarm delivered a
         * moment before its slot (clock granularity). Exact alarms are not
         * delivered early, so anything well before the slot is a stale
         * registration, not the fire.
         */
        private const val ALARM_EARLY_SLACK_MS = 5_000L

        /**
         * [T-android-scheduled-fire-claim] Whether an alarm delivered at [now]
         * is [t]'s pending fire: the slot has come, and a --after has not fired
         * / an interval has count left. Pure, for tests.
         */
        internal fun relativeAlarmDue(t: ScheduledTask, now: Long): Boolean {
            val anchor = t.anchorMs ?: t.createdAt
            return when (t.triggerKind) {
                ScheduledTriggerKind.AFTER ->
                    (t.triggeredCount ?: 0) < 1 &&
                        anchor + (t.delaySec ?: 0L) * 1000 <= now + ALARM_EARLY_SLACK_MS
                ScheduledTriggerKind.INTERVAL -> {
                    val iv = t.intervalSec ?: return false
                    (t.maxFires == null || (t.triggeredCount ?: 0) < t.maxFires) &&
                        anchor + iv * 1000 <= now + ALARM_EARLY_SLACK_MS
                }
                ScheduledTriggerKind.ON_COMPLETION, ScheduledTriggerKind.CALENDAR -> false
            }
        }

        /**
         * [T-android-scheduled-triggers] What a relative trigger's row becomes
         * when its alarm fires at [now], and whether to arm the next one. Pure,
         * for tests; null for kinds the alarm does not drive.
         *  - AFTER: fired once -> disabled (kept in the list, like ONCE).
         *  - INTERVAL: counted and re-anchored to [now]; the last of --count
         *    disables it instead of arming another.
         */
        internal fun afterAlarmFire(
            t: ScheduledTask,
            now: Long,
        ): Pair<ScheduledTask, Boolean>? = when (t.triggerKind) {
            ScheduledTriggerKind.AFTER -> t.copy(enabled = false, triggeredCount = 1) to false
            ScheduledTriggerKind.INTERVAL -> {
                val n = (t.triggeredCount ?: 0) + 1
                val next = t.copy(triggeredCount = n, anchorMs = now)
                if (t.maxFires != null && n >= t.maxFires) next.copy(enabled = false) to false
                else next to true
            }
            ScheduledTriggerKind.ON_COMPLETION, ScheduledTriggerKind.CALENDAR -> null
        }

        /**
         * [T-android-scheduled-triggers] [incoming] with the fields its writer
         * does not own taken from [prev]. Pure, for tests.
         *
         * The editor rebuilds a row from its own form fields, so run history,
         * the fire count and a relative trigger's whole payload are absent from
         * what it saves. Taking them from the stored row is what stops "save a
         * renamed label" from deleting the history or silently demoting an
         * interval to a daily calendar task.
         */
        internal fun mergeBookkeeping(incoming: ScheduledTask, prev: ScheduledTask): ScheduledTask {
            val keepTrigger = incoming.isCalendar && !prev.isCalendar
            val effectiveKind = if (keepTrigger) prev.triggerKind else incoming.triggerKind
            return incoming.copy(
                runHistory = incoming.runHistory.ifEmpty { prev.runHistory },
                fireCount = incoming.fireCount ?: prev.fireCount,
                triggerKind = effectiveKind,
                delaySec = if (keepTrigger) prev.delaySec else incoming.delaySec,
                intervalSec = if (keepTrigger) prev.intervalSec else incoming.intervalSec,
                maxFires = if (keepTrigger) prev.maxFires else incoming.maxFires,
                onCompletionOf = if (keepTrigger) prev.onCompletionOf else incoming.onCompletionOf,
                // Only when both rows are the same kind: converting a task to a
                // calendar row must not inherit a countdown that no longer means
                // anything.
                anchorMs = incoming.anchorMs ?: prev.anchorMs.takeIf { effectiveKind == prev.triggerKind },
                triggeredCount = incoming.triggeredCount
                    ?: prev.triggeredCount.takeIf { effectiveKind == prev.triggerKind },
            )
        }

        const val ACTION_FIRE = "com.openminis.app.scheduled.FIRE"
        const val EXTRA_TASK_ID = "task_id"
        const val CHANNEL_ID = "minis_scheduled_tasks"
        private const val CHANNEL_NAME = "Scheduled Tasks"
    }
}
