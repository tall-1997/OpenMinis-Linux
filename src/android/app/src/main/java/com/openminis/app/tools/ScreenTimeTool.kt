package com.openminis.app.tools

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
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
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end).orEmpty()
        val byPkg = LinkedHashMap<String, Long>()
        for (s in stats) {
            val ms = s.totalTimeInForeground
            if (ms <= 0L) continue
            byPkg[s.packageName] = (byPkg[s.packageName] ?: 0L) + ms
        }
        val ranked = byPkg.entries.sortedByDescending { it.value }.take(20)
        val totalMin = ranked.sumOf { it.value } / 60_000
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
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Handler(Looper.getMainLooper()).post {
            runCatching { context.startActivity(intent) }
        }
    }
}
