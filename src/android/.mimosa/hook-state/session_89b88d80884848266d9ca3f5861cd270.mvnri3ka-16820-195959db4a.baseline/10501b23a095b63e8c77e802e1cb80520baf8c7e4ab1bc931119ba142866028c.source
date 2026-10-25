package com.openminis.app.tools

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** Foreground app usage from UsageStats. Opens the system page when access is missing. */
object ScreenTimeTool {
    const val NAME = "get_screen_time"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read this device's per-app foreground screen time. " +
            "Requires Usage access. If it is not granted, the system usage-access page is opened. " +
            "range is today or week. Returns total minutes and a per-app breakdown.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "range" to AgentToolParam("string", "today or week. Default today."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "range"),
    )

    fun execute(argsJson: String, context: Context?): ToolExecutionResult {
        val title = runCatching { JSONObject(argsJson).optString("tool_title", NAME) }.getOrDefault(NAME)
        if (context == null) {
            return ToolExecutionResult("screen time needs an Android context", false, toolTitle = title)
        }
        if (!hasUsageAccess(context)) {
            openUsageSettings(context)
            return ToolExecutionResult(
                "Usage access is not granted. The system usage-access page was opened. Grant it, then retry get_screen_time.",
                success = false,
                toolTitle = title,
            )
        }
        val range = runCatching { JSONObject(argsJson).optString("range", "today") }.getOrDefault("today")
        val zone = ZoneId.systemDefault()
        val end = System.currentTimeMillis()
        val start = if (range.equals("week", ignoreCase = true)) {
            end - 7L * 24 * 60 * 60 * 1000
        } else {
            LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val byPkg = LinkedHashMap<String, Long>()
        foregroundMillis(readUsageEvents(usm, start, end), start, end).forEach { (pkg, ms) ->
            if (ms > 0L) byPkg[pkg] = ms
        }
        if (byPkg.isEmpty()) {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end).orEmpty()
            for (s in stats) {
                val ms = if (Build.VERSION.SDK_INT >= 29) s.totalTimeVisible else s.totalTimeInForeground
                if (ms <= 0L) continue
                byPkg[s.packageName] = (byPkg[s.packageName] ?: 0L) + ms
            }
        }
        val ranked = byPkg.entries.sortedByDescending { it.value }.take(20)
        val totalMin = byPkg.values.sum() / 60_000
        val body = buildString {
            appendLine("screen time ($range), ${ranked.size} apps, about $totalMin min foreground:")
            for (e in ranked) {
                appendLine("- ${e.key}: ${e.value / 60_000} min")
            }
            if (ranked.isEmpty()) append("No foreground usage in this range.")
        }
        return ToolExecutionResult(body.trim(), true, toolTitle = title)
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launch = { runCatching { context.startActivity(intent) } }
        if (Looper.myLooper() == Looper.getMainLooper()) launch() else Handler(Looper.getMainLooper()).post { launch() }
    }

    internal data class UsageEventLite(val packageName: String, val type: Int, val time: Long)

    /**
     * MIUI often returns 0 from aggregated foreground time for a partial day.
     * Resume/pause events are the clock the status bar actually uses.
     * If both families are present, keep only ACTIVITY_* so one transition is not counted twice.
     */
    internal fun foregroundMillis(events: List<UsageEventLite>, start: Long, end: Long): Map<String, Long> {
        if (end <= start) return emptyMap()
        val hasActivity = events.any {
            it.type == UsageEvents.Event.ACTIVITY_RESUMED || it.type == UsageEvents.Event.ACTIVITY_PAUSED
        }
        val startType = if (hasActivity) UsageEvents.Event.ACTIVITY_RESUMED else UsageEvents.Event.MOVE_TO_FOREGROUND
        val endType = if (hasActivity) UsageEvents.Event.ACTIVITY_PAUSED else UsageEvents.Event.MOVE_TO_BACKGROUND
        val openAt = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        for (ev in events) {
            if (ev.time < start || ev.time > end || ev.packageName.isEmpty()) continue
            if (ev.type == startType) {
                openAt.putIfAbsent(ev.packageName, ev.time)
            } else if (ev.type == endType) {
                val began = openAt.remove(ev.packageName) ?: start
                val span = (ev.time.coerceAtMost(end) - began.coerceAtLeast(start)).coerceAtLeast(0L)
                if (span > 0L) totals[ev.packageName] = (totals[ev.packageName] ?: 0L) + span
            }
        }
        for ((pkg, began) in openAt) {
            val span = (end - began.coerceAtLeast(start)).coerceAtLeast(0L)
            if (span > 0L) totals[pkg] = (totals[pkg] ?: 0L) + span
        }
        return totals
    }

    private fun readUsageEvents(usm: UsageStatsManager, start: Long, end: Long): List<UsageEventLite> {
        val raw = usm.queryEvents(start, end) ?: return emptyList()
        val out = ArrayList<UsageEventLite>()
        val ev = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(ev)
            val pkg = ev.packageName ?: continue
            out.add(UsageEventLite(pkg, ev.eventType, ev.timeStamp))
        }
        return out
    }
}
