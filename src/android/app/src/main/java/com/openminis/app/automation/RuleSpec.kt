package com.openminis.app.automation

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * [T-android-automation-rules] Conditional-trigger automation model, A-batch
 * semantics after jackwener/DeepseekHarness-Android's TriggerHub
 * (core/model/Rule.kt + core/trigger/): "when [trigger] (and all
 * [conditions]) then [action]".
 *
 * Deliberately NOT kotlinx.serialization: every other persisted model in this
 * app (ScheduledTask, backup payloads) round-trips through org.json, and the
 * agent-facing `automation_rule_*` tools hand the model raw JSON anyway.
 * Schema lives here so the Room entity and the tools stay thin layers.
 *
 * v1 scope: geofence triggers are intentionally NOT supported (stored specs
 * with "type":"geofence" fail validation; see [RuleValidation]). Steps actions
 * are stored and shown in run records but not executed yet.
 */

/**
 * What fires the rule. One per rule.
 *
 *  - [Time] aligns with the existing scheduled-task fields: a daily/weekly
 *    wall-clock slot (`at` + optional ISO `days`, Mon=1..Sun=7, empty=every
 *    day), a `everyMinutes` interval (>=15, Android background-alarm floor),
 *    or a one-shot `onceAtEpochMs`.
 *  - Everything else arrives as a runtime event from [RuleMonitorService]
 *    (Wi-Fi / Bluetooth / power / battery / screen) or from the notification
 *    listener forward (NotificationReceived). AppOpened needs an
 *    accessibility hook that is not wired in v1 — the funnel exists
 *    ([AutomationEvents.appOpened]) so wiring it later needs no schema change.
 */
sealed class Trigger {

    data class Time(
        val at: String? = null,
        val days: List<Int> = emptyList(),
        val everyMinutes: Long = 0,
        val onceAtEpochMs: Long? = null,
    ) : Trigger()

    data class WifiConnect(val ssid: String? = null) : Trigger()
    data class BluetoothConnect(val deviceName: String? = null) : Trigger()

    /** Fired when external power is connected (charger / dock / USB). */
    object PowerConnected : Trigger()

    /**
     * Fires when the level crosses DOWN through [percent] while discharging;
     * re-arms once the level rises back above the threshold.
     */
    data class BatteryLow(val percent: Int) : Trigger()

    object ScreenOn : Trigger()
    object ScreenOff : Trigger()
    object ScreenUnlock : Trigger()

    data class NotificationReceived(val packageName: String? = null, val keyword: String? = null) : Trigger()
    data class AppOpened(val packageName: String) : Trigger()

    /** Parse result for a discriminator this build does not know. */
    data class Unknown(val rawType: String) : Trigger()

    /** Discriminator written into the `type` field of the trigger JSON. */
    val typeKey: String
        get() = when (this) {
            is Time -> "time"
            is WifiConnect -> "wifi_connect"
            is BluetoothConnect -> "bluetooth_connect"
            is PowerConnected -> "power_connected"
            is BatteryLow -> "battery_low"
            is ScreenOn -> "screen_on"
            is ScreenOff -> "screen_off"
            is ScreenUnlock -> "screen_unlock"
            is NotificationReceived -> "notification"
            is AppOpened -> "app_opened"
            is Unknown -> "unknown"
        }

    fun toJson(): JSONObject = when (this) {
        is Time -> JSONObject().apply {
            put("type", typeKey)
            if (at != null) put("at", at)
            if (days.isNotEmpty()) put("days", JSONArray(days))
            if (everyMinutes > 0) put("everyMinutes", everyMinutes)
            if (onceAtEpochMs != null) put("onceAtEpochMs", onceAtEpochMs)
        }
        is WifiConnect -> JSONObject().apply {
            put("type", typeKey)
            if (ssid != null) put("ssid", ssid)
        }
        is BluetoothConnect -> JSONObject().apply {
            put("type", typeKey)
            if (deviceName != null) put("deviceName", deviceName)
        }
        is BatteryLow -> JSONObject().put("type", typeKey).put("percent", percent)
        is NotificationReceived -> JSONObject().apply {
            put("type", typeKey)
            if (packageName != null) put("packageName", packageName)
            if (keyword != null) put("keyword", keyword)
        }
        is AppOpened -> JSONObject().put("type", typeKey).put("packageName", packageName)
        // PowerConnected / ScreenOn / ScreenOff / ScreenUnlock: type-only.
        else -> JSONObject().put("type", typeKey)
    }

    companion object {
        fun fromJson(o: JSONObject): Trigger = when (val type = o.optString("type")) {
            "time" -> Time(
                at = if (o.has("at")) o.optString("at", null) else null,
                days = o.optJSONArray("days")?.let { arr ->
                    buildList { for (i in 0 until arr.length()) add(arr.optInt(i)) }
                } ?: emptyList(),
                everyMinutes = o.optLong("everyMinutes", 0),
                onceAtEpochMs = if (o.has("onceAtEpochMs")) o.optLong("onceAtEpochMs") else null,
            )
            "wifi_connect" -> WifiConnect(
                ssid = if (o.has("ssid")) o.optString("ssid", null) else null,
            )
            "bluetooth_connect" -> BluetoothConnect(
                deviceName = if (o.has("deviceName")) o.optString("deviceName", null) else null,
            )
            "power_connected" -> PowerConnected
            "battery_low" -> BatteryLow(o.optInt("percent", 20))
            "screen_on" -> ScreenOn
            "screen_off" -> ScreenOff
            "screen_unlock" -> ScreenUnlock
            "notification" -> NotificationReceived(
                packageName = if (o.has("packageName")) o.optString("packageName", null) else null,
                keyword = if (o.has("keyword")) o.optString("keyword", null) else null,
            )
            "app_opened" -> AppOpened(o.optString("packageName"))
            // Unknown discriminator surfaces as a validation problem via the
            // sentinel below instead of throwing at parse time.
            else -> Unknown(type)
        }
    }
}

/** Extra gates that must ALL hold at fire time, on top of the trigger. */
sealed class Condition {

    /**
     * Wall-clock window. `from` > `to` wraps midnight (22:00..06:00). Both
     * inclusive on the minute grid.
     */
    data class TimeRange(val from: String, val to: String) : Condition()

    /** ISO day set (Mon=1..Sun=7); empty list never holds (validation rejects it). */
    data class DaysOfWeek(val days: List<Int>) : Condition()

    data class BatteryAtLeast(val percent: Int) : Condition()
    data class Charging(val charging: Boolean) : Condition()

    val typeKey: String
        get() = when (this) {
            is TimeRange -> "time_range"
            is DaysOfWeek -> "days_of_week"
            is BatteryAtLeast -> "battery_at_least"
            is Charging -> "charging"
        }

    fun toJson(): JSONObject = when (this) {
        is TimeRange -> JSONObject().put("type", typeKey).put("from", from).put("to", to)
        is DaysOfWeek -> JSONObject().put("type", typeKey).put("days", JSONArray(days))
        is BatteryAtLeast -> JSONObject().put("type", typeKey).put("percent", percent)
        is Charging -> JSONObject().put("type", typeKey).put("charging", charging)
    }

    companion object {
        fun fromJson(o: JSONObject): Condition = when (o.optString("type")) {
            "time_range" -> TimeRange(o.optString("from"), o.optString("to"))
            "days_of_week" -> DaysOfWeek(
                o.optJSONArray("days")?.let { arr ->
                    buildList { for (i in 0 until arr.length()) add(arr.optInt(i)) }
                } ?: emptyList(),
            )
            "battery_at_least" -> BatteryAtLeast(o.optInt("percent", 0))
            "charging" -> Charging(o.optBoolean("charging", true))
            else -> DaysOfWeek(emptyList()) // unknown → holds nothing, validation flags it
        }
    }
}

/** What the rule does when it fires. */
sealed class RuleAction {

    /**
     * Run an unattended chat with [prompt] — the same headless path the
     * scheduled tasks use (see [AutomationAgentRunner]). The run may only use
     * [RuleSpec.allowedTools]; enforcement of that gate lives in the tool
     * dispatch layer.
     */
    data class Agent(val prompt: String) : RuleAction()

    /**
     * Fixed tool calls without a model. v1 stores + displays these only —
     * execution is a TODO and firing records a `skipped_steps_todo` run.
     */
    data class Steps(val steps: List<Step>) : RuleAction()

    val typeKey: String get() = when (this) {
        is Agent -> "agent"
        is Steps -> "steps"
    }

    fun toJson(): JSONObject = when (this) {
        is Agent -> JSONObject().put("type", "agent").put("prompt", prompt)
        is Steps -> JSONObject().put("type", "steps").put(
            "steps",
            JSONArray().apply { steps.forEach { put(it.toJson()) } },
        )
    }

    companion object {
        fun fromJson(o: JSONObject): RuleAction? = when (o.optString("type")) {
            "agent" -> Agent(o.optString("prompt"))
            "steps" -> Steps(
                o.optJSONArray("steps")?.let { arr ->
                    buildList {
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { add(Step.fromJson(it)) }
                        }
                    }
                } ?: emptyList(),
            )
            else -> null
        }
    }
}

data class Step(val tool: String, val argsJson: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tool", tool)
        put("argsJson", argsJson)
    }

    companion object {
        fun fromJson(o: JSONObject): Step = Step(
            tool = o.optString("tool"),
            // Accept both the documented argsJson string and a raw args object.
            argsJson = if (o.has("argsJson")) o.optString("argsJson")
            else o.optJSONObject("args")?.toString() ?: "{}",
        )
    }
}

/** The validated, normalized payload persisted in automation_rules.spec_json. */
data class RuleSpec(
    val name: String,
    val trigger: Trigger,
    val conditions: List<Condition> = emptyList(),
    val action: RuleAction,
    /** Tools the unattended run may use; anything else would be refused. */
    val allowedTools: List<String> = emptyList(),
    /**
     * Tool-gate mode for the freshly created session, same tokens as
     * ScheduledTask.newSessionPermissionMode ("YOYO" | "ASK"); null = app
     * default ASK. Normalized through PermissionMode.sessionDefault at fire
     * time so an unknown token can never widen the gate.
     */
    val permissionMode: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("trigger", trigger.toJson())
        put("conditions", JSONArray().apply { conditions.forEach { put(it.toJson()) } })
        put("action", action.toJson())
        if (allowedTools.isNotEmpty()) put("allowedTools", JSONArray(allowedTools))
        if (permissionMode != null) put("permissionMode", permissionMode)
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun fromJson(json: String): RuleSpec? = runCatching {
            val o = JSONObject(json)
            RuleSpec(
                name = o.optString("name"),
                trigger = Trigger.fromJson(o.getJSONObject("trigger")),
                conditions = o.optJSONArray("conditions")?.let { arr ->
                    buildList {
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { add(Condition.fromJson(it)) }
                        }
                    }
                } ?: emptyList(),
                action = RuleAction.fromJson(o.getJSONObject("action")) ?: return null,
                allowedTools = o.optJSONArray("allowedTools")?.let { arr ->
                    buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
                } ?: emptyList(),
                permissionMode = if (o.has("permissionMode")) o.optString("permissionMode", null) else null,
            )
        }.getOrNull()
    }
}

/**
 * Short static-English trigger summary for list rows — same convention as
 * [com.openminis.app.ui.scheduled.formatScheduleSummary] (the localized
 * status line carries the rest).
 */
fun Trigger.summarize(): String = when (this) {
    is Trigger.Time -> when {
        onceAtEpochMs != null -> "Once"
        everyMinutes > 0 -> {
            val m = everyMinutes
            when {
                m % 1440L == 0L -> "Every ${m / 1440L}d"
                m % 60L == 0L -> "Every ${m / 60L}h"
                else -> "Every ${m}m"
            }
        }
        else -> {
            val days = if (days.isEmpty()) "" else " " + days.sorted().joinToString(",") { dowShort(it) }
            "Daily$days ${at ?: "--:--"}"
        }
    }
    is Trigger.WifiConnect -> ssid?.let { "Wi-Fi $it" } ?: "Wi-Fi connected"
    is Trigger.BluetoothConnect -> deviceName?.let { "BT $it" } ?: "Bluetooth connected"
    is Trigger.PowerConnected -> "Power connected"
    is Trigger.BatteryLow -> "Battery low $percent%"
    is Trigger.ScreenOn -> "Screen on"
    is Trigger.ScreenOff -> "Screen off"
    is Trigger.ScreenUnlock -> "Screen unlock"
    is Trigger.NotificationReceived -> buildString {
        append("Notification")
        packageName?.let { append(" $it") }
        keyword?.let { append(" \"$it\"") }
    }
    is Trigger.AppOpened -> "App opened $packageName"
    is Trigger.Unknown -> "Unknown (${rawType})"
}

internal fun dowShort(isoDay: Int): String = when (isoDay) {
    1 -> "Mon"
    2 -> "Tue"
    3 -> "Wed"
    4 -> "Thu"
    5 -> "Fri"
    6 -> "Sat"
    7 -> "Sun"
    else -> "?"
}

/** ISO 1..7 (Mon..Sun) → java.util.Calendar day-of-week. */
internal fun isoDayToCalendar(isoDay: Int): Int =
    if (isoDay == 7) Calendar.SUNDAY else isoDay + 1

/**
 * Parse a lenient 24-hour "H:mm"/"HH:mm" wall-clock string into total minutes
 * since midnight, or null when malformed. Mirrors the DSH RuleTime contract
 * without java.time so it stays trivially testable on the JVM.
 */
fun parseWallClock(raw: String?): Pair<Int, Int>? {
    val s = raw?.trim()?.replace('：', ':') ?: return null
    val m = Regex("""^(\d{1,2}):(\d{2})$""").find(s) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: return null
    val min = m.groupValues[2].toIntOrNull() ?: return null
    if (h !in 0..23 || min !in 0..59) return null
    return h to min
}

fun formatWallClock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)
