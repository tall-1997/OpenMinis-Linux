package com.openminis.app.scheduled

import java.util.Calendar

/**
 * Natural-language schedule parser for the agent `cronjob` tool.
 *
 * Adapted from XINCODE-Public CronScheduler
 * (GPL-3.0-or-later, https://github.com/kusesad-1122/XINCODE-Public).
 * OpenMinis fires via AlarmManager [ScheduledTask], not WorkManager.
 */
object CronScheduler {

    data class Parsed(
        val kind: String,
        val intervalMinutes: Long,
        val firstDelayMs: Long,
    )

    /** Parse `"7m"`/`"90s"`/`"1h30m"` (once) and `"every 15m"`/`"every 1h30m"` (interval). */
    fun parseSchedule(spec: String): Parsed? {
        val s = spec.trim().lowercase()
        val recurring = s.startsWith("every ")
        val body = if (recurring) s.removePrefix("every ").trim() else s
        // One or more <number><unit> chunks, whitespace tolerated: "7m", "90s",
        // "1h30m", "2 h 15 m". A trailing tail ("30m later") rejects so a mangled
        // spec never silently fires at a partial duration.
        val compact = body.filter { !it.isWhitespace() }
        val chunks = Regex("""(\d+)([smhd])""").findAll(compact).toList()
        if (chunks.isEmpty() || chunks.sumOf { it.value.length } != compact.length) return null
        var seconds = 0L
        for (c in chunks) {
            val n = c.groupValues[1].toLongOrNull() ?: return null
            val unitSec = when (c.groupValues[2]) {
                "s" -> 1L
                "m" -> 60L
                "h" -> 3_600L
                "d" -> 86_400L
                else -> return null
            }
            seconds += n * unitSec
            if (seconds < 0) return null // Long overflow guard
        }
        if (seconds <= 0) return null
        // The persisted repeat cadence is whole minutes; sub-minute durations
        // round up (45s repeats ~1m out) while the once-delay keeps seconds.
        val minutes = maxOf(1L, (seconds + 59) / 60)
        val firstDelayMs = seconds * 1000L
        return if (recurring) Parsed("interval", minutes, firstDelayMs)
        else Parsed("once", 0, firstDelayMs)
    }

    // ── [T-android-cron-5-field] Standard 5-field cron expressions ────────

    /**
     * A parsed cron expression: per-field value sets in CRON's own domains
     * (minute 0-59, hour 0-23, day-of-month 1-31, month 1-12, day-of-week
     * 0-6 with 0=Sunday — the classic vixie-cron convention, NOT ISO).
     */
    data class CronFields(
        val minutes: Set<Int>,
        val hours: Set<Int>,
        val daysOfMonth: Set<Int>,
        val months: Set<Int>,
        val daysOfWeek: Set<Int>,
    )

    /** Parse `"0 9 * * 1-5"` (and star, star-slash-N steps, ranges, lists per field). */
    fun parseCronExpression(spec: String): CronFields? {
        val parts = spec.trim().split(Regex("\\s+"))
        if (parts.size != 5) return null
        val minutes = parseCronField(parts[0], 0, 59) ?: return null
        val hours = parseCronField(parts[1], 0, 23) ?: return null
        val dom = parseCronField(parts[2], 1, 31) ?: return null
        val months = parseCronField(parts[3], 1, 12) ?: return null
        val dow = parseCronField(parts[4], 0, 6) ?: return null
        return CronFields(minutes, hours, dom, months, dow)
    }

    private fun parseCronField(field: String, min: Int, max: Int): Set<Int>? {
        val out = mutableSetOf<Int>()
        for (part in field.split(",")) {
            val m = Regex("^(\\*|(\\d+)-(\\d+)|(\\d+))(/(\\d+))?$").find(part) ?: return null
            val whole = m.groupValues[1]
            val rangeLo = m.groupValues[2]
            val rangeHi = m.groupValues[3]
            val single = m.groupValues[4]
            val stepGroup = m.groupValues[6]
            val step = stepGroup.toIntOrNull() ?: 1
            if (step <= 0) return null
            val lo: Int
            val hi: Int
            when {
                whole == "*" -> { lo = min; hi = max }
                rangeLo.isNotEmpty() && rangeHi.isNotEmpty() -> { lo = rangeLo.toInt(); hi = rangeHi.toInt() }
                else -> {
                    val v = single.toIntOrNull() ?: return null
                    if (stepGroup.isNotEmpty()) { lo = v; hi = max } else { lo = v; hi = v }
                }
            }
            if (lo < min || hi > max || lo > hi) return null
            var v = lo
            while (v <= hi) {
                out.add(v)
                v += step
            }
        }
        if (out.isEmpty()) return null
        return out
    }

    /**
     * Next fire time strictly after [afterMs] for a 5-field cron spec, local
     * time. Minute-resolution scan bounded at one year (covers every legal
     * spec). Null on an unparseable spec or no match within the bound —
     * callers treat null as "disable the task", never as "fire now".
     */
    fun nextCronFire(spec: String, afterMs: Long): Long? {
        val f = parseCronExpression(spec) ?: return null
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = afterMs
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, 1)
        }
        // Calendar.DAY_OF_WEEK: 1=Sunday..7=Saturday. CRON dow: 0=Sunday..6.
        val dowOf = { c: java.util.Calendar -> c.get(Calendar.DAY_OF_WEEK) - 1 }
        val domRestricted = f.daysOfMonth.size != 31
        val dowRestricted = f.daysOfWeek.size != 7
        var safety = 366 * 24 * 60
        while (safety-- > 0) {
            if (cal.get(Calendar.MONTH) + 1 !in f.months) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                continue
            }
            val domOk = cal.get(Calendar.DAY_OF_MONTH) in f.daysOfMonth
            val dowOk = dowOf(cal) in f.daysOfWeek
            // Classic cron semantics: DOM and DOW are OR when BOTH restricted.
            val dayOk = when {
                domRestricted && dowRestricted -> domOk || dowOk
                domRestricted -> domOk
                dowRestricted -> dowOk
                else -> true
            }
            if (!dayOk) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                continue
            }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            if (hour !in f.hours) {
                val nextHour = f.hours.filter { it > hour }.minOrNull()
                if (nextHour == null) {
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                } else {
                    cal.set(Calendar.HOUR_OF_DAY, nextHour)
                    cal.set(Calendar.MINUTE, 0)
                }
                continue
            }
            val minute = cal.get(Calendar.MINUTE)
            if (minute in f.minutes) return cal.timeInMillis
            val nextMinute = f.minutes.filter { it > minute }.minOrNull()
            if (nextMinute == null) {
                cal.add(Calendar.HOUR_OF_DAY, 1)
                if (cal.get(Calendar.HOUR_OF_DAY) !in f.hours) continue
                cal.set(Calendar.MINUTE, f.minutes.min())
                continue
            }
            cal.set(Calendar.MINUTE, nextMinute)
            return cal.timeInMillis
        }
        return null
    }
}
