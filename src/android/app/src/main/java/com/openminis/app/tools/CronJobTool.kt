package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.scheduled.CronScheduler
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTriggerKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * Agent-facing cron wrapper over [ScheduledTaskManager] (AlarmManager).
 *
 * Adapted from XINCODE-Public CronJobTool
 * (GPL-3.0-or-later, https://github.com/kusesad-1122/XINCODE-Public).
 */
object CronJobTool {
    const val NAME = "cronjob"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Create, list, or remove AlarmManager scheduled tasks that run an agent prompt. " +
            "Prefer this for delay/interval schedules (30m, 2h, every 2h). Clock-of-day HH:MM tasks " +
            "can also be managed via minis-scheduled. Schedules: '30m'/'2h'/'1d' (once) or " +
            "'every 30m'/'every 2h'/'every 1d' (repeating).",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user (e.g. 'Schedule nightly review'). Use the same language as the user.",
            ),
            "action" to AgentToolParam(
                "string",
                "create, list, or remove.",
                enumValues = listOf("create", "list", "remove"),
            ),
            "name" to AgentToolParam("string", "Short label for create."),
            "schedule" to AgentToolParam(
                "string",
                "For create: '30m', '2h', '1d', or 'every 30m' / 'every 2h' / 'every 1d'.",
            ),
            "prompt" to AgentToolParam("string", "Agent prompt to run when the task fires."),
            "id" to AgentToolParam("string", "Task id or a unique id prefix for remove (from list)."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "name", "schedule", "prompt", "id"),
    )

    fun execute(argsJson: String, context: Context): ToolExecutionResult {
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            return ToolExecutionResult("Error: invalid JSON arguments", false)
        }
        val action = args.optString("action").trim().lowercase()
        val manager = ScheduledTaskManager(context)
        return when (action) {
            "list" -> listJobs(manager)
            "create" -> createJob(manager, args)
            "remove" -> removeJob(manager, args)
            else -> ToolExecutionResult("Error: action must be create, list, or remove", false)
        }
    }

    private fun listJobs(manager: ScheduledTaskManager): ToolExecutionResult {
        val tasks = manager.list()
        val arr = JSONArray()
        for (t in tasks) {
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("name", t.label)
                    put("enabled", t.enabled)
                    put("trigger", t.triggerKind.name.lowercase())
                    // `repeat` only ever described calendar rows; an interval has
                    // no calendar repeat and printing "ONCE" for it would lie.
                    if (t.isCalendar) put("repeat", t.repeatMode.name)
                    t.intervalSec?.let { put("intervalSec", it) }
                    t.maxFires?.let { put("maxFires", it) }
                    put("firesSoFar", t.firesSoFar)
                    put("nextTriggerMs", t.nextTriggerMs() ?: JSONObject.NULL)
                    put("prompt", t.prompt.take(200))
                },
            )
        }
        return ToolExecutionResult(
            JSONObject().put("count", tasks.size).put("tasks", arr).toString(),
            true,
        )
    }

    private fun createJob(manager: ScheduledTaskManager, args: JSONObject): ToolExecutionResult {
        val name = args.optString("name").trim()
        val schedule = args.optString("schedule").trim()
        val prompt = args.optString("prompt").trim()
        if (prompt.isEmpty()) return ToolExecutionResult("Error: prompt is required for create", false)
        val parsed = CronScheduler.parseSchedule(schedule)
            ?: return ToolExecutionResult(
                "Error: schedule must look like '30m', '2h', '1d', or 'every 30m'/'every 2h'/'every 1d'",
                false,
            )
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now + parsed.firstDelayMs }
        val interval = parsed.kind == "interval"
        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            label = name.ifBlank { prompt.take(32) },
            timeOfDayHour = cal.get(Calendar.HOUR_OF_DAY),
            timeOfDayMinute = cal.get(Calendar.MINUTE),
            repeatMode = ScheduledRepeatMode.ONCE,
            prompt = prompt,
            // [T-android-scheduled-triggers] Both shapes are relative triggers:
            // "30m" fires once after 30m (AFTER), "every 30m" fires every 30m
            // (INTERVAL). repeatMode stays ONCE — it only ever described a
            // calendar row, and the hour/minute above are just a sane default
            // for if the task is later edited into one.
            triggerKind = if (interval) {
                ScheduledTriggerKind.INTERVAL
            } else {
                ScheduledTriggerKind.AFTER
            },
            intervalSec = if (interval) parsed.intervalMinutes * 60L else null,
            delaySec = if (interval) null else parsed.firstDelayMs / 1000L,
            anchorMs = now,
            triggeredCount = 0,
            createdAt = now,
        )
        val saved = manager.create(task)
        return ToolExecutionResult(
            JSONObject().apply {
                put("ok", true)
                put("id", saved.id)
                put("name", saved.label)
                put("schedule", schedule)
                put("trigger", saved.triggerKind.name.lowercase())
                put("nextTriggerMs", saved.nextTriggerMs() ?: JSONObject.NULL)
                if (saved.intervalSec != null) put("intervalSec", saved.intervalSec)
                if (saved.id != task.id) put("already_existed", true)
            }.toString(),
            true,
        )
    }

    private fun removeJob(manager: ScheduledTaskManager, args: JSONObject): ToolExecutionResult {
        val raw = args.optString("id").trim()
        if (raw.isEmpty()) return ToolExecutionResult("Error: id is required for remove", false)
        val id = manager.resolveId(raw)
        if (id == null) {
            val hits = if (raw.length >= 4) manager.list().filter { it.id.startsWith(raw) } else emptyList()
            if (hits.size > 1) {
                return ToolExecutionResult(
                    "Error: id prefix $raw matches ${hits.size} tasks: " + hits.joinToString { it.id },
                    false,
                )
            }
            return ToolExecutionResult("Error: no scheduled task with id $raw", false)
        }
        if (!manager.delete(id) || manager.get(id) != null) {
            return ToolExecutionResult("Error: scheduled task $id was not deleted", false)
        }
        return ToolExecutionResult(JSONObject().put("ok", true).put("id", id).toString(), true)
    }
}
