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

    /** Parse `"30m"`/`"2h"`/`"1d"` (once) and `"every 30m"`/`"every 2h"` (interval). */
    fun parseSchedule(spec: String): Parsed? {
        val s = spec.trim().lowercase()
        val recurring = s.startsWith("every ")
        val body = if (recurring) s.removePrefix("every ").trim() else s
        val m = Regex("""^(\d+)\s*([mhd])$""").find(body) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val unitMin = when (m.groupValues[2]) {
            "m" -> 1L
            "h" -> 60L
            "d" -> 1440L
            else -> return null
        }
        val minutes = n * unitMin
        if (minutes <= 0) return null
        val firstDelayMs = minutes * 60_000L
        return if (recurring) Parsed("interval", minutes, firstDelayMs)
        else Parsed("once", 0, firstDelayMs)
    }
}
