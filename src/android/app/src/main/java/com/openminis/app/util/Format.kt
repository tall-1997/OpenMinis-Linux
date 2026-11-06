package com.openminis.app.util

/**
 * [T-format-single-source] Shared human-readable formatting helpers.
 *
 * Consolidates private copies that had drifted across the app:
 *   - `formatBytes`: cards/CardRenderers ("1.5 MB"), browser/BrowserUseManager,
 *     crash/CrashFrequencyDetector ("1024KB" style, no decimals below MB —
 *     unified here onto the more informative cards form).
 *   - `formatDuration` (h/m/s words): cards/CardDerivers.
 *   - `formatMediaClock` ("m:ss" clock): offload/MediaPlayerManager,
 *     ui/media/InlineMediaPlayer.
 *   - `slugify`: data/repository/SkillRepository, ui/settings/MinisSkillsBrowserScreen.
 *
 * ui/chat keeps its own `formatDurationCompact` (converged separately in Wave 2).
 */
object Format {

    /** `"1.5 MB"` / `"48.2 KB"` / `"123 B"` (binary units, one decimal). */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1 shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1 shl 10 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** `"1h 02m"` / `"3m 07s"` / `"42s"`. */
    fun formatDuration(seconds: Int): String = when {
        seconds >= 3600 -> String.format("%dh %02dm", seconds / 3600, (seconds % 3600) / 60)
        seconds >= 60 -> String.format("%dm %02ds", seconds / 60, seconds % 60)
        else -> "${seconds}s"
    }

    /**
     * Media position/duration clock: `"0:00"` / `"3:05"` / `"12:59"`.
     * Non-positive input clamps to `"0:00"` — a zero/negative position or a
     * not-yet-reported duration renders as the clock start, never `"0:-2"`.
     */
    fun formatMediaClock(ms: Int): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    /** Lowercase kebab slug — skill ids on disk (`name.lowercase()`, non-[a-z0-9] → `-`). */
    fun slugify(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}
