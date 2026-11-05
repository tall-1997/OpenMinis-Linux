package com.openminis.app.scheduled

import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * [P2-schedule-codec] Single implementation of the schedule wire codec shared
 * by the two tool surfaces — the agent-side [com.openminis.app.tools.CronJobTool]
 * and the MCP [com.openminis.app.mcp.server.tools.ScheduleTools]. Both used to
 * carry parallel copies of the parse→fireAt→construct pipeline and of the
 * per-task list row; a change to one silently diverged from the other.
 *
 * The surfaces keep their own argument adaptation and response formatting
 * (compact vs pretty-printed JSON, per-tool error text) — this object owns
 * only the shared mapping.
 */
object ScheduledTaskCodec {

    /**
     * Parse a cron-style schedule ("30m", "2h", "1d", "every 30m", …) and
     * build the [ScheduledTask] it describes, with `fireAtMs` fixed to
     * now + firstDelay and the clock fields derived from that instant.
     * Returns null when [schedule] is unparseable (the caller renders its own
     * error text).
     */
    fun buildTask(
        schedule: String,
        prompt: String,
        name: String,
        targetMode: ScheduledTargetMode = ScheduledTargetMode.NewSession,
        id: String = UUID.randomUUID().toString(),
        now: Long = System.currentTimeMillis(),
    ): ScheduledTask? {
        val parsed = CronScheduler.parseSchedule(schedule) ?: return null
        val fireAt = now + parsed.firstDelayMs
        val cal = Calendar.getInstance().apply { timeInMillis = fireAt }
        val interval = parsed.kind == "interval"
        return ScheduledTask(
            id = id,
            label = name.ifBlank { prompt.take(32) },
            timeOfDayHour = cal.get(Calendar.HOUR_OF_DAY),
            timeOfDayMinute = cal.get(Calendar.MINUTE),
            repeatMode = if (interval) ScheduledRepeatMode.INTERVAL else ScheduledRepeatMode.ONCE,
            prompt = prompt,
            targetMode = targetMode,
            intervalMinutes = parsed.intervalMinutes,
            fireAtMs = fireAt,
            createdAt = now,
        )
    }

    /** The [ScheduledTask] actually persisted (never null — create() dedups). */
    fun createResult(saved: ScheduledTask, requested: ScheduledTask, schedule: String): JSONObject =
        JSONObject().apply {
            put("ok", true)
            put("id", saved.id)
            put("name", saved.label)
            put("schedule", schedule)
            put("fireAtMs", saved.fireAtMs ?: requested.fireAtMs)
            put("repeat", saved.repeatMode.name)
            if (saved.id != requested.id) put("already_existed", true)
        }

    /**
     * One list row. [includeTargetSession] adds the owning-chat field the
     * cronjob surface surfaces for sub-agent attribution (the MCP list omits
     * it, matching its historic shape).
     */
    fun taskListRow(t: ScheduledTask, includeTargetSession: Boolean = false): JSONObject =
        JSONObject().apply {
            put("id", t.id)
            put("name", t.label)
            put("enabled", t.enabled)
            put("repeat", t.repeatMode.name)
            if (t.intervalMinutes > 0) put("intervalMinutes", t.intervalMinutes)
            if (t.fireAtMs != null) put("fireAtMs", t.fireAtMs)
            put("nextTriggerMs", t.nextTriggerMs() ?: JSONObject.NULL)
            put("prompt", t.prompt.take(200))
            if (includeTargetSession) {
                t.targetMode.sessionIdOrNull?.let { put("target_session", it) }
            }
        }
}
