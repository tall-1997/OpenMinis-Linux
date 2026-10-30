package com.openminis.app.automation

/**
 * [T-android-automation-rules] Validate + normalize a [RuleSpec] before it is
 * stored. Returns EVERY problem at once (model-readable English) so the
 * `automation_rule_create` tool can report the full list in one round trip.
 *
 * Normalizations applied on success: times rewritten as zero-padded "HH:mm",
 * blank optional strings dropped, allowedTools trimmed / de-duplicated and
 * filtered to [knownTools] (unknown names are silently dropped, not errors —
 * the model may hallucinate tool names and a partial whitelist still gates),
 * permissionMode normalized to the session-persistence tokens.
 */
object RuleValidation {

    class RuleSpecException(val problems: List<String>) :
        Exception(problems.joinToString("; "))

    data class Result(val spec: RuleSpec?, val problems: List<String>)

    /**
     * @param knownTools tool names this build actually exposes; the
     *   allowedTools whitelist is filtered down to these.
     * @param nowMs used for the one-shot "in the past" check.
     */
    fun validate(spec: RuleSpec, knownTools: Set<String>, nowMs: Long): Result {
        val p = mutableListOf<String>()

        if (spec.name.isBlank()) p += "name must not be empty"

        val trigger = when (val t = spec.trigger) {
            is Trigger.Time -> {
                val set = listOfNotNull(
                    t.at?.takeIf { it.isNotBlank() },
                    t.everyMinutes.takeIf { it > 0 },
                    t.onceAtEpochMs,
                )
                if (set.size != 1) {
                    p += "time trigger needs exactly one of \"at\" (HH:mm), " +
                        "\"everyMinutes\" (>=15) or \"onceAtEpochMs\""
                }
                if (t.days.any { it !in 1..7 }) {
                    p += "trigger.days must be numbers 1..7 (Mon=1 … Sun=7), got ${t.days}"
                }
                if (t.everyMinutes > 0 && t.everyMinutes < 15) {
                    p += "everyMinutes must be at least 15 (Android limits background alarms), got ${t.everyMinutes}"
                }
                if (t.onceAtEpochMs != null && t.onceAtEpochMs <= nowMs) {
                    p += "onceAtEpochMs is in the past"
                }
                if (t.days.isNotEmpty() && t.at.isNullOrBlank()) {
                    p += "\"days\" only applies together with \"at\""
                }
                val at = t.at?.takeIf { it.isNotBlank() }?.let { raw ->
                    parseWallClock(raw)?.let { formatWallClock(it.first, it.second) } ?: run {
                        p += "trigger.at '$raw' is not a time; use 24-hour \"HH:mm\" such as \"07:30\" (00:00–23:59)"
                        raw
                    }
                }
                t.copy(at = at)
            }
            is Trigger.WifiConnect -> t.copy(ssid = t.ssid?.trim()?.takeIf { it.isNotEmpty() })
            is Trigger.BluetoothConnect -> t.copy(deviceName = t.deviceName?.trim()?.takeIf { it.isNotEmpty() })
            is Trigger.BatteryLow -> t.also {
                if (it.percent !in 1..99) p += "battery_low.percent must be 1..99, got ${it.percent}"
            }
            is Trigger.NotificationReceived -> t.copy(
                packageName = t.packageName?.trim()?.takeIf { it.isNotEmpty() },
                keyword = t.keyword?.trim()?.takeIf { it.isNotEmpty() },
            )
            is Trigger.AppOpened -> t.also {
                if (it.packageName.isBlank()) p += "app_opened.packageName must not be empty"
            }
            // [T-android-automation-rules] v1 scope: no geofencing.
            is Trigger.Unknown -> {
                p += if (t.rawType == "geofence") {
                    "geofence triggers are not supported in this version"
                } else {
                    "unknown trigger type \"${t.rawType}\""
                }
                t
            }
            else -> t
        }

        val conditions = spec.conditions.map { c ->
            when (c) {
                is Condition.TimeRange -> {
                    val from = parseWallClock(c.from)?.let { formatWallClock(it.first, it.second) } ?: run {
                        p += "time_range.from '${c.from}' is not a time; use 24-hour \"HH:mm\""
                        c.from
                    }
                    val to = parseWallClock(c.to)?.let { formatWallClock(it.first, it.second) } ?: run {
                        p += "time_range.to '${c.to}' is not a time; use 24-hour \"HH:mm\""
                        c.to
                    }
                    val f = parseWallClock(from)
                    val t2 = parseWallClock(to)
                    if (f != null && t2 != null &&
                        (f.first * 60 + f.second) >= (t2.first * 60 + t2.second)
                    ) {
                        // [T-android-automation-rules] Inverted (or zero-width)
                        // windows are rejected outright — overnight windows
                        // would silently never fire with a from>to reading,
                        // and the two-rule workaround is explicit.
                        p += "time_range.from must be earlier than to (overnight windows are not supported; create two rules)"
                    }
                    Condition.TimeRange(from, to)
                }
                is Condition.DaysOfWeek -> {
                    if (c.days.isEmpty()) p += "days_of_week condition needs at least one day (1..7, Mon=1)"
                    if (c.days.any { it !in 1..7 }) {
                        p += "days_of_week must be numbers 1..7 (Mon=1 … Sun=7), got ${c.days}"
                    }
                    c
                }
                is Condition.BatteryAtLeast -> c.also {
                    if (it.percent !in 0..100) p += "battery_at_least.percent must be 0..100, got ${it.percent}"
                }
                is Condition.Charging -> c
            }
        }

        val action = when (val a = spec.action) {
            is RuleAction.Agent -> {
                if (a.prompt.isBlank()) p += "agent action needs a non-empty prompt"
                a
            }
            is RuleAction.Steps -> {
                if (a.steps.isEmpty()) p += "steps action needs at least one step"
                a.steps.filter { it.tool.isBlank() }.takeIf { it.isNotEmpty() }?.let {
                    p += "every step needs a tool name"
                }
                a
            }
        }

        val allowed = spec.allowedTools
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it in knownTools }
            .distinct()

        val permissionMode = spec.permissionMode?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            if (raw.equals("YOYO", ignoreCase = true)) "YOYO" else "ASK"
        }

        if (p.isNotEmpty()) return Result(null, p)
        return Result(
            RuleSpec(
                name = spec.name.trim(),
                trigger = trigger,
                conditions = conditions,
                action = action,
                allowedTools = allowed,
                permissionMode = permissionMode,
            ),
            emptyList(),
        )
    }
}
