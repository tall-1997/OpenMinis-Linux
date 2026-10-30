package com.openminis.app.automation

import org.json.JSONObject
import java.util.Calendar

/**
 * [T-android-automation-rules] Environment snapshot a fire-time check runs
 * against. Pure data so [Condition.holds] and [matches] are unit-testable on
 * the JVM without Android or Room — the manager builds one per event.
 */
data class RuleEnv(
    val nowMs: Long,
    /** Sticky-battery snapshot, null when unavailable. */
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
) {
    companion object {
        fun at(nowMs: Long): RuleEnv = RuleEnv(nowMs = nowMs)
    }
}

/**
 * Wall-clock ms of the next firing for a Time trigger, aligned with the
 * existing scheduled-task anchoring: repeating slots anchor to [anchorMs]
 * (lastFiredAt ?: createdAt) so re-syncing on every app start or edit does
 * not keep pushing the next run into the future, and a slot missed by more
 * than one period staggers to now+60s instead of firing immediately.
 *
 * Returns null when the trigger has no future occurrence (one-shot already
 * past, malformed `at`, `days` that never match within a week).
 */
fun Trigger.Time.nextTriggerMs(nowMs: Long, anchorMs: Long): Long? {
    onceAtEpochMs?.let { return it.takeIf { at -> at > nowMs } }
    if (everyMinutes > 0) {
        val period = everyMinutes * 60_000L
        val due = anchorMs + period
        return if (due > nowMs) due else nowMs + 60_000L
    }
    val parsed = parseWallClock(at) ?: return null
    val wantedCalDays = days.map { isoDayToCalendar(it) }.toSet()
    val cal = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, parsed.first)
        set(Calendar.MINUTE, parsed.second)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    var safety = 8
    while (safety-- > 0) {
        if (cal.timeInMillis > nowMs &&
            (days.isEmpty() || cal.get(Calendar.DAY_OF_WEEK) in wantedCalDays)
        ) {
            return cal.timeInMillis
        }
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }
    return null
}

/**
 * `days` are ISO (Mon=1..Sun=7); Calendar.DAY_OF_WEEK is Sun=1..Sat=7. The
 * candidate day is converted from the Calendar value back to ISO.
 */
private fun isoDayOfCalendar(cal: Calendar): Int = when (cal.get(Calendar.DAY_OF_WEEK)) {
    Calendar.MONDAY -> 1
    Calendar.TUESDAY -> 2
    Calendar.WEDNESDAY -> 3
    Calendar.THURSDAY -> 4
    Calendar.FRIDAY -> 5
    Calendar.SATURDAY -> 6
    else -> 7
}

/**
 * Whether [this] condition holds at [env]. Unparseable stored values make the
 * condition false, never a crash (same policy as the DSH TriggerHub).
 */
fun Condition.holds(env: RuleEnv): Boolean {
    return when (this) {
        is Condition.TimeRange -> {
            val now = minutesOfDay(env.nowMs)
            val from = parseWallClock(from)?.let { it.first * 60 + it.second } ?: return false
            val to = parseWallClock(to)?.let { it.first * 60 + it.second } ?: return false
            if (from <= to) now in from..to else now >= from || now <= to
        }
        is Condition.DaysOfWeek -> {
            if (days.isEmpty()) false
            else days.contains(isoDayOf(env.nowMs))
        }
        is Condition.BatteryAtLeast -> (env.batteryPercent ?: return false) >= percent
        is Condition.Charging -> env.charging == charging
    }
}

internal fun minutesOfDay(nowMs: Long): Int {
    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}

internal fun isoDayOf(nowMs: Long): Int {
    val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
    return isoDayOfCalendar(cal)
}

/**
 * Raw device events, as seen by receivers/services — the funnel into
 * [AutomationRuleManager.onEvent]. Shape mirrors DSH's DeviceEvent.
 */
sealed class AutomationEvent {
    data class Time(val ruleId: String) : AutomationEvent()
    data class Wifi(val connected: Boolean, val ssid: String?) : AutomationEvent()
    data class Bluetooth(val connected: Boolean, val deviceName: String?) : AutomationEvent()
    data class Power(val connected: Boolean) : AutomationEvent()

    /**
     * [previous] is the last level the monitor saw (null right after it
     * started) — BatteryLow matches only the downward crossing while
     * discharging, so the sticky replay on registration must not fire it.
     */
    data class Battery(val percent: Int, val previous: Int?, val charging: Boolean) : AutomationEvent()
    data class Screen(val event: String) : AutomationEvent() // on | off | unlock
    data class Notification(val packageName: String, val title: String, val text: String) : AutomationEvent()
    data class AppOpened(val packageName: String) : AutomationEvent()

    /** Human fragment used in the automation prompt preamble + run previews. */
    fun describe(): String = when (this) {
        is Time -> "scheduled time"
        is Wifi -> if (connected) "Wi-Fi connected${ssid?.let { " ($it)" } ?: ""}" else "Wi-Fi disconnected"
        is Bluetooth -> if (connected) {
            "Bluetooth connected${deviceName?.let { " ($it)" } ?: ""}"
        } else {
            "Bluetooth disconnected"
        }
        is Power -> if (connected) "power connected" else "power disconnected"
        is Battery -> "battery at $percent%"
        is Screen -> when (event) {
            "on" -> "screen turned on"
            "off" -> "screen turned off"
            else -> "screen unlocked"
        }
        is Notification -> "notification from $packageName: ${title.take(120)} — ${text.take(500)}"
        is AppOpened -> "app opened: $packageName"
    }
}

/**
 * Does the trigger match the event? Time matches only its own alarm; Wifi /
 * Bluetooth / Notification filters are null-or-equal on the optional field;
 * BatteryLow is the downward threshold crossing while discharging.
 */
fun matches(trigger: Trigger, event: AutomationEvent): Boolean = when (trigger) {
    is Trigger.Time -> event is AutomationEvent.Time // rule-id equality checked by the caller
    is Trigger.WifiConnect -> event is AutomationEvent.Wifi && event.connected &&
        (trigger.ssid == null || trigger.ssid.equals(event.ssid, ignoreCase = true))
    is Trigger.BluetoothConnect -> event is AutomationEvent.Bluetooth && event.connected &&
        (trigger.deviceName == null || event.deviceName?.contains(trigger.deviceName, ignoreCase = true) == true)
    is Trigger.PowerConnected -> event is AutomationEvent.Power && event.connected
    is Trigger.BatteryLow -> event is AutomationEvent.Battery && !event.charging &&
        event.previous != null && event.previous > trigger.percent && event.percent <= trigger.percent
    is Trigger.ScreenOn -> event is AutomationEvent.Screen && event.event == "on"
    is Trigger.ScreenOff -> event is AutomationEvent.Screen && event.event == "off"
    is Trigger.ScreenUnlock -> event is AutomationEvent.Screen && event.event == "unlock"
    is Trigger.NotificationReceived -> event is AutomationEvent.Notification &&
        (trigger.packageName == null || trigger.packageName == event.packageName) &&
        (trigger.keyword == null || "${event.title} ${event.text}".contains(trigger.keyword, ignoreCase = true))
    is Trigger.AppOpened -> event is AutomationEvent.AppOpened && event.packageName == trigger.packageName
    is Trigger.Unknown -> false
}

/** Compact JSON of the event for run-record previews. */
fun AutomationEvent.toPreviewJson(): String = when (this) {
    is AutomationEvent.Time -> JSONObject().put("kind", "time").put("ruleId", ruleId).toString()
    is AutomationEvent.Wifi -> JSONObject().put("kind", "wifi").put("connected", connected)
        .put("ssid", ssid ?: JSONObject.NULL).toString()
    is AutomationEvent.Bluetooth -> JSONObject().put("kind", "bluetooth").put("connected", connected)
        .put("deviceName", deviceName ?: JSONObject.NULL).toString()
    is AutomationEvent.Power -> JSONObject().put("kind", "power").put("connected", connected).toString()
    is AutomationEvent.Battery -> JSONObject().put("kind", "battery").put("percent", percent)
        .put("charging", charging).toString()
    is AutomationEvent.Screen -> JSONObject().put("kind", "screen").put("event", event).toString()
    is AutomationEvent.Notification -> JSONObject().put("kind", "notification")
        .put("packageName", packageName).put("title", title.take(120)).toString()
    is AutomationEvent.AppOpened -> JSONObject().put("kind", "app_opened").put("packageName", packageName).toString()
}
