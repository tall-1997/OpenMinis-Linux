package com.openminis.app.data.db

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * [T-db-gate-freshness] Freshness cache for the database startup gate.
 *
 * `PRAGMA quick_check` is O(database size): on every cold start the gate
 * re-scans the whole database file before Room may open it, so the scan is
 * the cold-start tax once the library grows. Corruption is rare (WAL mode,
 * framework-checked writes), so a recent healthy verdict can stand in for
 * the re-scan: within [FRESH_WINDOW_MS] and only while the very same file
 * (path + size) is still on disk, the gate skips straight to Healthy.
 *
 * Safety story stays layered:
 *  - a corruption that appears inside the window costs nothing until SQLite
 *    itself trips over it at open / first-query time, where the degraded
 *    paths already live;
 *  - quarantine, a failed check and a missing database file all invalidate
 *    the stamp (and a quarantined database is renamed away, so path+size can
 *    never match again anyway);
 *  - a missing or unreadable stamp simply means "scan again" — the safe
 *    direction.
 */
internal object DatabaseGateFreshness {

    private const val FILE_NAME = "db_gate_freshness.json"

    /** How long a healthy verdict stands in for a re-scan. */
    internal const val FRESH_WINDOW_MS: Long = 24 * 60 * 60 * 1000L

    /** A persisted healthy verdict. */
    internal data class FreshnessStamp(
        val checkedAtMs: Long,
        val dbPath: String,
        val dbSizeBytes: Long,
    )

    /**
     * Pure decision: does [stamp] describe a fresh, healthy verdict for
     * exactly the database file currently at [dbFile]?
     */
    internal fun isFresh(stamp: FreshnessStamp?, dbFile: File, nowMs: Long): Boolean {
        if (stamp == null) return false
        if (nowMs - stamp.checkedAtMs !in 0 until FRESH_WINDOW_MS) return false
        if (stamp.dbPath != dbFile.path) return false
        if (stamp.dbSizeBytes != dbFile.length()) return false
        return true
    }

    /** Best-effort read; any damage here means "scan again", never "skip". */
    internal fun readStamp(filesDir: File): FreshnessStamp? {
        val file = File(filesDir, FILE_NAME)
        if (!file.isFile) return null
        return runCatching {
            val o = JSONObject(file.readText())
            FreshnessStamp(
                checkedAtMs = o.getLong("checkedAtMs"),
                dbPath = o.getString("dbPath"),
                dbSizeBytes = o.getLong("dbSizeBytes"),
            )
        }.getOrNull()
    }

    /**
     * tmp+rename atomic write with fsync, mirroring the quarantine's
     * durability discipline; losing a stamp only costs one extra scan.
     */
    internal fun recordHealthy(dbFile: File, filesDir: File, nowMs: Long) {
        runCatching {
            val tmp = File(filesDir, "$FILE_NAME.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(
                    JSONObject()
                        .put("checkedAtMs", nowMs)
                        .put("dbPath", dbFile.path)
                        .put("dbSizeBytes", dbFile.length())
                        .toString()
                        .toByteArray(Charsets.UTF_8),
                )
                out.fd.sync()
            }
            if (!tmp.renameTo(File(filesDir, FILE_NAME))) {
                tmp.delete()
            }
        }
    }

    internal fun invalidate(filesDir: File) {
        runCatching { File(filesDir, FILE_NAME).delete() }
    }
}
