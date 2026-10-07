package com.openminis.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-db-startup-integrity-gate] Startup integrity gate for `minis.db`.
 *
 * Modeled on cuplivo's `business_startup_gate` / `database_installation_gate`
 * / `startup_failure_report` trio: before Room opens the database we run a
 * `PRAGMA quick_check`; when the file is corrupt we DO NOT let Room hit it
 * (Room would either crash the process or, with a destructive fallback,
 * silently erase the user's history). Instead the corrupt files are moved
 * aside into a timestamped quarantine directory, a failure report is
 * persisted, and the app boots on a fresh empty database. On next launch the
 * user is told what happened and where their data copy lives, and can delete
 * the copy themselves.
 *
 * The decision logic takes plain [File]s so it is unit-testable on the JVM
 * with a fake [IntegrityChecker]; only [AndroidIntegrityChecker] and the
 * thin [Context] adapters touch Android.
 */
object DatabaseHealthCheck {

    private const val TAG = "DbHealth"

    const val REPORT_FILE = "startup_failure_report.json"
    const val QUARANTINE_PREFIX = "minis-db-corrupt-"
    /** Reports older than this are treated as stale and not surfaced again. */
    const val REPORT_VALIDITY_MS = 7L * 24 * 60 * 60 * 1000

    /** Sidecar files SQLite may keep next to the main database file. */
    private val SIDECARS = listOf("-wal", "-shm", "-journal")

    sealed class Outcome {
        /** Database file exists and passed the integrity check. */
        data object Healthy : Outcome()

        /** No database file at all — first launch (or a prior uninstall). */
        data object FreshInstall : Outcome()

        /**
         * Check failed; the corrupt files were moved to [quarantineDirName]
         * (relative to filesDir) and a fresh empty database will be created.
         */
        data class Quarantined(val quarantineDirName: String) : Outcome()

        /**
         * Check failed AND quarantine failed — the caller must not proceed to
         * open the database through the normal path.
         */
        data class QuarantineFailed(val reason: String) : Outcome()
    }

    /** Runs `PRAGMA quick_check`. Returns null when healthy, detail otherwise. */
    fun interface IntegrityChecker {
        /** @return null when the file passes; a short detail string otherwise. */
        fun quickCheck(dbFile: File): String?
    }

    /** Production checker backed by the platform SQLite. */
    class AndroidIntegrityChecker : IntegrityChecker {
        override fun quickCheck(dbFile: File): String? {
            var db: SQLiteDatabase? = null
            return try {
                db = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
                val rows = mutableListOf<String>()
                db.rawQuery("PRAGMA quick_check", null)?.use { c ->
                    while (c.moveToNext() && rows.size < 8) {
                        rows.add(c.getString(0) ?: "")
                    }
                }
                if (rows.isEmpty() || rows.all { it.equals("ok", ignoreCase = true) }) {
                    null
                } else {
                    rows.joinToString("; ").take(500)
                }
            } catch (e: Exception) {
                "open/quick_check threw ${e.javaClass.simpleName}: ${e.message}".take(500)
            } finally {
                runCatching { db?.close() }
            }
        }
    }

    // -- Thin Context adapters --------------------------------------------

    fun ensureHealthyOrQuarantined(
        context: Context,
        dbName: String = "minis.db",
        checker: IntegrityChecker = AndroidIntegrityChecker(),
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome = ensureHealthyOrQuarantined(
        dbFile = context.getDatabasePath(dbName),
        filesDir = context.filesDir,
        checker = checker,
        nowMs = nowMs,
    )

    fun writeReport(context: Context, report: StartupFailureReport) =
        writeReport(context.filesDir, report)

    fun pendingReport(context: Context, nowMs: Long = System.currentTimeMillis()): StartupFailureReport? =
        pendingReport(context.filesDir, nowMs)

    fun clearReport(context: Context) = clearReport(context.filesDir)

    fun listQuarantineDirs(context: Context): List<File> = listQuarantineDirs(context.filesDir)

    fun deleteQuarantine(context: Context, dirName: String): Boolean =
        deleteQuarantine(context.filesDir, dirName)

    // -- The gate (pure file logic, JVM-testable) --------------------------

    /**
     * The gate itself. Call BEFORE opening Room.
     *
     * Order of operations on corruption is deliberate: rename first (a
     * rename within one filesystem is atomic and never destroys the user's
     * bytes), report second (best-effort diagnostics), fsync the directories
     * last so the rename survives a power loss.
     */
    fun ensureHealthyOrQuarantined(
        dbFile: File,
        filesDir: File,
        checker: IntegrityChecker,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome {
        if (!dbFile.isFile) return Outcome.FreshInstall

        val detail = runCatching { checker.quickCheck(dbFile) }
            .getOrElse { "checker crashed: ${it.javaClass.simpleName}" }
        if (detail == null) return Outcome.Healthy

        AppLogger.error(TAG, "[T-db-gate] quick_check FAILED for ${dbFile.name}: $detail")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        val quarantineDir = File(filesDir, "$QUARANTINE_PREFIX$stamp")
        return try {
            if (!quarantineDir.mkdirs() && !quarantineDir.isDirectory) {
                throw IllegalStateException("cannot create ${quarantineDir.name}")
            }
            val moved = mutableListOf<String>()
            for (f in listOf(dbFile) + SIDECARS.map { File(dbFile.path + it) }) {
                if (!f.exists()) continue
                val target = File(quarantineDir, f.name)
                if (!f.renameTo(target)) {
                    throw IllegalStateException("cannot move ${f.name} into quarantine")
                }
                moved += f.name
            }
            writeReport(
                filesDir,
                StartupFailureReport(
                    kind = "database_corruption",
                    dbName = dbFile.name,
                    detail = detail,
                    quarantineDir = quarantineDir.name,
                    movedFiles = moved,
                    occurredAtMs = nowMs,
                ),
            )
            fsyncDirectory(filesDir)
            fsyncDirectory(quarantineDir)
            AppLogger.error(TAG, "[T-db-gate] quarantined ${moved.size} files -> ${quarantineDir.name}")
            Outcome.Quarantined(quarantineDir.name)
        } catch (e: Exception) {
            AppLogger.error(TAG, "[T-db-gate] quarantine FAILED: ${e.message}")
            Outcome.QuarantineFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    // -- Failure report ----------------------------------------------------

    data class StartupFailureReport(
        val kind: String,
        val dbName: String,
        val detail: String,
        val quarantineDir: String?,
        val movedFiles: List<String>,
        val occurredAtMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("format", "minis.startup-failure-report")
            put("format_version", 1)
            put("kind", kind)
            put("db_name", dbName)
            put("detail", detail)
            put("quarantine_dir", quarantineDir)
            put("moved_files", JSONArray(movedFiles))
            put("occurred_at_ms", occurredAtMs)
        }

        companion object {
            fun fromJson(obj: JSONObject): StartupFailureReport? = runCatching {
                val moved = mutableListOf<String>()
                val arr = obj.optJSONArray("moved_files")
                if (arr != null) for (i in 0 until arr.length()) moved += arr.optString(i)
                StartupFailureReport(
                    kind = obj.optString("kind"),
                    dbName = obj.optString("db_name"),
                    detail = obj.optString("detail"),
                    quarantineDir = obj.optString("quarantine_dir").takeIf { it.isNotBlank() },
                    movedFiles = moved,
                    occurredAtMs = obj.optLong("occurred_at_ms"),
                )
            }.getOrNull()
        }
    }

    private fun reportFile(filesDir: File): File = File(filesDir, REPORT_FILE)

    fun writeReport(filesDir: File, report: StartupFailureReport) {
        val tmp = File(filesDir, "$REPORT_FILE.tmp")
        tmp.writeText(report.toJson().toString())
        if (!tmp.renameTo(reportFile(filesDir))) {
            reportFile(filesDir).writeText(report.toJson().toString())
            tmp.delete()
        }
        fsyncDirectory(filesDir)
    }

    /**
     * The pending report, or null when none exists / it is unparseable / it
     * is older than [REPORT_VALIDITY_MS] (stale reports must not nag the
     * user forever after they already saw the reset state).
     */
    fun pendingReport(filesDir: File, nowMs: Long = System.currentTimeMillis()): StartupFailureReport? {
        val f = reportFile(filesDir)
        if (!f.isFile) return null
        val report = runCatching { StartupFailureReport.fromJson(JSONObject(f.readText())) }.getOrNull()
            ?: return null
        if (nowMs - report.occurredAtMs > REPORT_VALIDITY_MS) return null
        return report
    }

    fun clearReport(filesDir: File) {
        reportFile(filesDir).delete()
    }

    // -- Quarantine housekeeping ------------------------------------------

    fun listQuarantineDirs(filesDir: File): List<File> =
        filesDir.listFiles { f ->
            f.isDirectory && f.name.startsWith(QUARANTINE_PREFIX)
        }?.sortedByDescending { it.name } ?: emptyList()

    fun deleteQuarantine(filesDir: File, dirName: String): Boolean {
        // The name comes from our own listing/report; refuse anything that
        // could walk out of filesDir anyway.
        if (dirName.contains('/') || dirName.contains("..") ||
            !dirName.startsWith(QUARANTINE_PREFIX)
        ) return false
        return File(filesDir, dirName).deleteRecursively()
    }

    internal fun fsyncDirectory(dir: File) {
        runCatching {
            FileOutputStream(dir).use { it.fd.sync() }
        }
    }
}
