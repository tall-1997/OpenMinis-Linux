package com.openminis.app.diagnostics

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * Records when a process launch begins and when the previous launch ended
 * cleanly. Lets us distinguish "the user backgrounded and we got cleaned
 * up by MIUI / LMK" (no clean-exit marker; previous run's last tick was
 * recent, in foreground) from "we crashed" (ACRA / native handler already
 * wrote a `.log` file) or "normal launch after explicit exit" (clean-exit
 * marker present).
 *
 * The beacon is one small JSON-ish line per launch under
 * `filesDir/logs/launch-beacon.log`. We don't roll it — entries are tiny
 * and a long history helps spot MIUI-kill patterns over days.
 *
 * Bug 2 in the v1.8-dev MIUI feedback report ("app crash" with no
 * crash-*.log file): adding this beacon means the next launch can log
 * "previous run had no clean exit and no crash report — likely LMK or
 * MIUI background cleanup", which is what we want to confirm.
 */
object LaunchCycleBeacon {

    private const val FILE_NAME = "launch-beacon.log"
    private const val TAG = "LaunchBeacon"

    /**
     * [T-android-render-breaker] True when the PREVIOUS app cycle ended in
     * crash_or_stall (set once at [recordLaunch]). Consumed by the chat Resume
     * banner: in the ANR-restart loop the banner was an unguarded "continue"
     * button that re-entered the exact load that killed the previous cycle —
     * with this flag it warns and requires a confirming second tap.
     */
    @Volatile
    var lastCycleWasCrash: Boolean = false
        private set

    /**
     * [T-android-larky-longsession-followup] Snapshot of `restartCount` from
     * the most recent [recordLaunch] call (the (launches − clean_exits) tail
     * count computed in the crash_or_stall branch). Stored verbatim so the
     * launch resolver can gate on it without re-reading the beacon file.
     *
     * Only updated when the prior verdict starts with "crash_or_stall" — for
     * any other verdict (clean_exit / silent_kill / first_launch /
     * no_prior_launch) this stays at 0, which means [shouldForceHomeOnLaunch]
     * naturally returns false for users who didn't actually crash.
     *
     * Diagnostic only. A silent_kill does not clear [RouteFuse] and does not
     * re-enable automatic entry. There is no restart-count threshold.
     */
    @Volatile
    var lastRestartCount: Int = 0
        private set

    /**
     * One unhealthy previous cycle is enough. A silent kill, a stall, or a
     * watchdog reboot with no tombstone is not given three free retries, and
     * clearing a counter does not re-enable automatic entry.
     */
    fun shouldForceHomeOnLaunch(): Boolean = lastCycleWasCrash

    fun recordLaunch(context: Context) {
        val file = beaconFile(context)
        val previousTail = readTail(file)
        val now = System.currentTimeMillis()
        val nowIso = isoLocal(now)

        // Inspect previous record to classify the prior cycle.
        val previousVerdict = classifyPrevious(previousTail, context, now)
        lastCycleWasCrash = previousVerdict.startsWith("crash_or_stall")
        AppLogger.info(TAG, "launch verdict for previous cycle: $previousVerdict")

        // [T-android-perf-logging] When the previous cycle ended in
        // crash_or_stall, surface a structured Perf line so a low-memory
        // ANR repro can be correlated against a recovery loop. restartCount
        // = how many of the recent launch records are themselves preceded by
        // crash artefacts (i.e. consecutive bad cycles) — a climbing count is
        // the signature of "recovery keeps re-crashing on the same session".
        if (previousVerdict.startsWith("crash_or_stall")) {
            val restartCount = countRecentCrashLaunches(previousTail, context)
            // [T-android-larky-longsession-followup] Snapshot the count so
            // shouldForceHomeOnLaunch() can gate on it without re-reading
            // the beacon. Cleared by the path above whenever the previous
            // cycle is anything other than crash_or_stall (the var stays at
            // its initialization value of 0 unless this branch overrides).
            lastRestartCount = restartCount
            val lastSessionId = readLastSessionId(context)
            AppLogger.warning(
                TAG,
                "[Perf][LongCtx] step=launchBeacon.crashOrStall " +
                    "reason=${previousVerdict.substringBefore(' ')} " +
                    "detail=${previousVerdict.substringAfter('(', "").substringBefore(')')} " +
                    "restartCount=$restartCount lastSessionId=$lastSessionId",
            )
        } else {
            // Explicit reset for clarity: any verdict that's not
            // crash_or_stall resets the gate count. Important when a user
            // who previously hit the breaker has a single non-crash cycle
            // (clean_exit / silent_kill) — they should be back to normal
            // auto-recovery on the very next launch.
            lastRestartCount = 0
        }

        appendLine(file, "[$nowIso] launch pid=${android.os.Process.myPid()}")
    }

    /**
     * [T-android-perf-logging] Count launch records in the beacon tail that
     * sit between crash/stall artefacts — a rough "consecutive bad cycles"
     * gauge. Cheap heuristic: number of `launch` lines minus the number
     * followed by a `clean_exit`. Not exact, but a rising value across
     * launches is what flags a recovery loop.
     */
    private fun countRecentCrashLaunches(tail: String, context: Context): Int {
        val lines = tail.split('\n').filter { it.isNotBlank() }
        val launches = lines.count { it.contains(" launch ") }
        val cleanExits = lines.count { it.contains(" clean_exit ") }
        return (launches - cleanExits).coerceAtLeast(0)
    }

    /**
     * [T-android-perf-logging] Best-effort last-opened session id, read from
     * the most recent stall-*.log header if present (the HangDetector writes
     * the session id into its stall report). Returns "unknown" when no stall
     * artefact carries one — keeps the log line populated without throwing.
     */
    private fun readLastSessionId(context: Context): String {
        return try {
            val logsDir = File(context.filesDir, "logs")
            val stall = logsDir.listFiles()
                ?.filter { it.name.startsWith("stall-") }
                ?.maxByOrNull { it.lastModified() }
                ?: return "unknown"
            val head = stall.bufferedReader().use { it.readText().take(2000) }
            Regex("session[=:]\\s*([0-9a-fA-F-]{8,})").find(head)?.groupValues?.get(1) ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
    }

    fun recordCleanExit(context: Context) {
        val now = System.currentTimeMillis()
        appendLine(beaconFile(context), "[${isoLocal(now)}] clean_exit pid=${android.os.Process.myPid()}")
    }

    private fun beaconFile(context: Context): File =
        File(File(context.filesDir, "logs").also { it.mkdirs() }, FILE_NAME)

    /** Last ~16 KB of the beacon log — enough to find the previous launch line. */
    private fun readTail(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        return try {
            val len = file.length()
            val start = (len - 16 * 1024).coerceAtLeast(0)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "tail read failed: ${t.message}")
            ""
        }
    }

    private fun classifyPrevious(tail: String, context: Context, now: Long): String {
        if (tail.isBlank()) return "first_launch"
        val lines = tail.split('\n').filter { it.isNotBlank() }
        val lastLaunch = lines.lastOrNull { it.contains(" launch ") } ?: return "no_prior_launch"
        val lastCleanExit = lines.lastOrNull { it.contains(" clean_exit ") }
        // Order matters: if a clean_exit line follows the most recent launch,
        // the previous cycle ended cleanly.
        val cleanExitAfterLaunch = lastCleanExit != null &&
            lines.indexOf(lastCleanExit) > lines.indexOf(lastLaunch)
        if (cleanExitAfterLaunch) return "clean_exit"

        // Look for crash artefacts produced between previous launch and now.
        val logsDir = File(context.filesDir, "logs")
        val previousLaunchMs = parseTs(lastLaunch) ?: return "ambiguous_no_timestamp"
        val crashLogs = logsDir.listFiles()?.filter { f ->
            val name = f.name
            (name.startsWith("crash-") || name.startsWith("native-crash-") || name.startsWith("stall-")) &&
                f.lastModified() in previousLaunchMs..now
        }.orEmpty()
        if (crashLogs.isNotEmpty()) {
            return "crash_or_stall (${crashLogs.joinToString { it.name }})"
        }
        // No crash, no clean exit — likely OOM / LMK / MIUI background cleanup.
        val ageMs = now - previousLaunchMs
        return "silent_kill (uptime_was=${ageMs}ms)"
    }

    private fun parseTs(line: String): Long? {
        // Lines look like "[2026-05-13T01:23:45.678] launch pid=…"
        val open = line.indexOf('[')
        val close = line.indexOf(']')
        if (open != 0 || close <= 0) return null
        val iso = line.substring(1, close)
        return com.openminis.app.util.IsoTime.parseLocalMillis(iso)
    }

    private fun isoLocal(ms: Long): String =
        com.openminis.app.util.IsoTime.formatLocalMillis(ms)

    private fun appendLine(file: File, line: String) {
        try {
            file.appendText(line + "\n")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "append failed: ${t.message}")
        }
    }
}
