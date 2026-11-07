package com.openminis.app.scheduled

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
}
