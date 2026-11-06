package com.openminis.app.scheduled

import android.app.NotificationChannel
import android.app.NotificationManager
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
            it.customDays == task.customDays &&
            it.prompt == task.prompt &&
            it.targetMode == task.targetMode &&
            it.enabled == task.enabled
    }

    fun resolveId(idOrPrefix: String): String? = store.resolveId(idOrPrefix)

    fun update(task: ScheduledTask): ScheduledTask {
        cancelAlarm(task.id)
        store.upsert(task)
        if (task.enabled) registerAlarm(task)
        return task
    }

    fun setEnabled(taskId: String, enabled: Boolean) {
        val t = store.get(taskId) ?: return
        val updated = t.copy(enabled = enabled)
        store.upsert(updated)
        if (enabled) registerAlarm(updated) else cancelAlarm(taskId)
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
     * [T-sched-run-now, port upstream 1.16] Now answers whether THIS alarm
     * delivery is a real fire that should run: false when the slot was
     * already taken by "Run now" (or the in-app timer) — the caller skips
     * the run and the next occurrence is armed instead.
     */
    fun rescheduleNext(taskId: String): Boolean {
        val t = store.get(taskId) ?: return false
        if (t.repeatMode == ScheduledRepeatMode.ONCE) {
            // Mark fired ONCE task as disabled — keep row so the user can
            // see its last result, re-enable if they want to re-run.
            store.upsert(t.copy(enabled = false))
            return true
        }
        if (t.repeatMode == ScheduledRepeatMode.INTERVAL) {
            val intervalMs = t.intervalMinutes * 60_000L
            if (intervalMs <= 0L) return false
            val updated = t.copy(fireAtMs = System.currentTimeMillis() + intervalMs)
            store.upsert(updated)
            registerAlarm(updated)
            return true
        }
        // [T-sched-run-now] A slot "Run now" already fired early must not run
        // again from its alarm: the run-now claim recorded the slot
        // ([ScheduledTask.runNowSlotMs]); a delivery inside the late window
        // (Doze can hold an alarm minutes) is that stale registration.
        if (calendarSlotTakenByRunNow(t, System.currentTimeMillis())) {
            AppLogger.info(TAG, "task ${t.id}: slot already run by Run now — not running")
            registerAlarm(t)
            return false
        }
        // [T-scheduled-end-window-autodisable] ScheduledTask's active-window
        // contract says repeating tasks "auto-disable once the end date has
        // fully passed" — but this branch used to just call registerAlarm,
        // whose nextTriggerMs()==null path logs "no next trigger" and gives
        // up. The row stayed enabled=true forever: toggle on, no alarm, and
        // the list card showed the schedule with an empty next segment.
        // Disable in-place (same shape as the ONCE branch) so the row and
        // the card agree that the task is finished. Also covers a CUSTOM
        // task whose day set drained to empty.
        if (t.nextTriggerMs() == null) {
            AppLogger.info(
                TAG,
                "task ${t.id}: no future slot (end of active window) — disabling",
            )
            store.upsert(t.copy(enabled = false))
            return false
        }
        registerAlarm(t)
        return true
    }

    /**
     * [T-sched-run-now, port upstream 1.16] "Run now": take the task's
     * PENDING next run and mark it fired in ONE atomic store step — the same
     * lock and the same transition the alarm's fire uses, so the alarm and
     * "Run now" can never both run the same run. The loser finds the slot
     * taken (recorded in [ScheduledTask.runNowSlotMs]) and the alarm path
     * turns itself away in [rescheduleNext]. The next alarm is re-armed for
     * the run after the taken one.
     *
     * Returns the row to run, or null when there is nothing to take:
     * disabled, no pending slot, or [isRunning] says a run of it is already
     * in progress (checked under the store lock, not only when the menu
     * opened).
     */
    fun claimRunNow(
        taskId: String,
        now: Long = System.currentTimeMillis(),
        isRunning: (String) -> Boolean = { false },
    ): ScheduledTask? {
        val stored = store.update(taskId) { cur ->
            if (isRunning(cur.id)) return@update null
            runNowTransition(cur, now)
        } ?: return null
        if (stored.enabled && stored.nextTriggerMs(now) != null) registerAlarm(stored) else cancelAlarm(taskId)
        AppLogger.info(
            TAG,
            "run now task=$taskId slot=${stored.runNowSlotMs} enabled=${stored.enabled} next=${stored.nextTriggerMs(now)}",
        )
        return stored
    }

    fun markFired(taskId: String, sessionId: String?, resultPreview: String?, ok: Boolean = true) {
        val t = store.get(taskId) ?: return
        val now = System.currentTimeMillis()
        // [T-android-scheduled-tasks-run-records] Prepend a run record
        // (newest-first), capped at MAX_RUN_HISTORY. lastResult* are kept in
        // sync for back-compat but are no longer surfaced in the list UI.
        val run = ScheduledRun(firedAt = now, sessionId = sessionId, preview = resultPreview, ok = ok)
        val history = (listOf(run) + t.runHistory).take(ScheduledTask.MAX_RUN_HISTORY)
        store.upsert(
            t.copy(
                lastFiredAt = now,
                lastResultPreview = resultPreview,
                lastResultSessionId = sessionId,
                runHistory = history,
            ),
        )
    }

    private fun registerAlarm(task: ScheduledTask) {
        val triggerAt = task.nextTriggerMs() ?: run {
            AppLogger.warning(TAG, "task ${task.id} has no next trigger — skipping register")
            return
        }
        alarmScheduler.registerExact(task.id, task.label, triggerAt)
    }

    private fun cancelAlarm(taskId: String) {
        alarmScheduler.cancel(taskId)
    }

    /**
     * [P2-alarm-dedup] The exact/inexact fallback + PendingIntent machinery
     * lives in the shared [ExactAlarmScheduler]. No request-code salt here —
     * the historic raw task-id hash is preserved (the automation owner salts
     * its own space with "automation_").
     */
    private val alarmScheduler = ExactAlarmScheduler(
        context = context,
        tag = TAG,
        kind = "task",
    ) { ctx, taskId ->
        Intent(ctx, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TASK_ID, taskId)
        }
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
         * [T-sched-run-now] Identity of the task's pending run, for a
         * compare-and-set in the in-app timer: the planned slot. null when
         * nothing is pending. Pure, for tests. Lives on the companion (like
         * upstream 1.16) so the timer and the alarm race it statelessly.
         */
        internal fun pendingRunKey(t: ScheduledTask, now: Long): String? =
            if (!t.enabled) null else t.nextTriggerMs(now)?.let { "slot:$it" }

        internal fun runNowTransition(t: ScheduledTask, now: Long): ScheduledTask? {
            if (!t.enabled) return null
            val slot = t.nextTriggerMs(now) ?: return null
            return when (t.repeatMode) {
                ScheduledRepeatMode.ONCE -> t.copy(runNowSlotMs = slot, enabled = false)
                ScheduledRepeatMode.INTERVAL ->
                    t.copy(
                        runNowSlotMs = slot,
                        fireAtMs = slot + (t.intervalMinutes * 60_000L).coerceAtLeast(60_000L),
                    )
                else -> t.copy(runNowSlotMs = slot)
            }
        }

        internal fun calendarSlotTakenByRunNow(t: ScheduledTask, now: Long): Boolean {
            val slot = t.runNowSlotMs ?: return false
            return now >= slot - ALARM_EARLY_SLACK_MS && now <= slot + RUN_NOW_LATE_WINDOW_MS
        }

        const val ACTION_FIRE = "com.openminis.app.scheduled.FIRE"
        const val EXTRA_TASK_ID = "task_id"
        const val CHANNEL_ID = "minis_scheduled_tasks"
        private const val CHANNEL_NAME = "Scheduled Tasks"

        /**
         * [T-sched-run-now, port upstream 1.16] Tolerance for a delivery
         * judged against a slot: exact alarms are not delivered early, so
         * anything well before the slot is a stale registration.
         */
        internal const val ALARM_EARLY_SLACK_MS = 5_000L

        /** [T-sched-run-now] How late an alarm for a taken slot may still arrive. */
        internal const val RUN_NOW_LATE_WINDOW_MS = 30 * 60_000L
    }
}
