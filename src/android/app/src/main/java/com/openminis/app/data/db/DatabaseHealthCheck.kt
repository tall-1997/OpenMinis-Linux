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
 *
 * ## All-or-nothing
 *
 * Quarantine is a multi-file operation on a filesystem that can run out of
 * space halfway through, so it is written as two strictly separated phases:
 *
 *  1. MOVE. Either every existing file lands in the quarantine directory, or
 *     every file already moved is renamed back. A move that cannot be undone
 *     leaves a marker plus the quarantine directory itself as evidence, and
 *     [recoverOrphanedQuarantine] reads that evidence on the next launch so a
 *     stranded database is never mistaken for "no database at all".
 *  2. REPORT. Purely diagnostic. By the time it runs the user's bytes are
 *     already safe, so a failure here is recorded but still reported as a
 *     successful quarantine — losing the report must never lose the data, and
 *     must never make the next launch think this is a fresh install.
 */
object DatabaseHealthCheck {

    private const val TAG = "DbHealth"

    const val REPORT_FILE = "startup_failure_report.json"
    const val QUARANTINE_PREFIX = "minis-db-corrupt-"

    /**
     * Breadcrumb left behind when a quarantine could not be rolled back.
     *
     * The quarantine directory contents are the authoritative evidence — they
     * already exist on disk and survive even when this file cannot be written
     * (which is the exact situation, a full disk, that causes the failure in
     * the first place). The marker only adds the reason and the timestamp.
     */
    const val ROLLBACK_MARKER_FILE = "minis-db-quarantine-rollback-failed.json"

    /** Reports older than this are treated as stale and not surfaced again. */
    const val REPORT_VALIDITY_MS = 7L * 24 * 60 * 60 * 1000

    /** `StartupFailureReport.kind` values. */
    const val KIND_CORRUPTION = "database_corruption"
    const val KIND_ROLLBACK_FAILED = "database_quarantine_rollback_failed"
    const val KIND_INCOMPLETE = "database_quarantine_incomplete"

    /** Sidecar files SQLite may keep next to the main database file. */
    private val SIDECARS = listOf("-wal", "-shm", "-journal")

    private const val STAMP_PATTERN = "yyyyMMdd-HHmmss"

    /**
     * The rename primitive, replaceable so unit tests can provoke the one
     * failure a healthy filesystem will not produce on demand: a file that
     * moves INTO quarantine fine but cannot be moved back out. Production code
     * never assigns to this.
     */
    @Volatile
    internal var renameFile: (File, File) -> Boolean = { src, dst -> src.renameTo(dst) }

    /** Restores [renameFile] to the real [File.renameTo]. Call from test teardown. */
    internal fun resetRenameFileForTest() {
        renameFile = { src, dst -> src.renameTo(dst) }
    }

    sealed class Outcome {
        /** Database file exists and passed the integrity check. */
        data object Healthy : Outcome()

        /** No database file at all — first launch (or a prior uninstall). */
        data object FreshInstall : Outcome()

        /**
         * Check failed; the corrupt files were moved to [quarantineDirName]
         * (relative to filesDir) and a fresh empty database will be created.
         *
         * [reportWritten] is false when the move completed but the failure
         * report could not be persisted. The quarantine itself is COMPLETE —
         * the data is safe — and the next launch rebuilds the report from the
         * quarantine directory (see [recoverOrphanedQuarantine]).
         */
        data class Quarantined(
            val quarantineDirName: String,
            val reportWritten: Boolean = true,
        ) : Outcome()

        /**
         * Check failed AND quarantine failed — the caller must not proceed to
         * open the database through the normal path.
         */
        data class QuarantineFailed(val reason: String) : Outcome()

        /**
         * An EARLIER launch left the database half-quarantined: the main file
         * is missing from its normal path, but a copy survives inside
         * [quarantineDirName].
         *
         * Deliberately NOT [FreshInstall] and deliberately NOT
         * [QuarantineFailed]: there is nothing left to protect on this boot
         * (the bytes are already parked in quarantine), so throwing would only
         * trap the user in a startup crash loop with no way to read the dialog
         * that explains where their data went. The report has been rebuilt, so
         * `MainActivity` surfaces it and the user can decide what to do.
         */
        data class QuarantineIncomplete(
            val quarantineDirName: String,
            val reason: String,
        ) : Outcome()
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
        if (!dbFile.isFile) {
            // NOT automatically a fresh install. A previous launch may have
            // moved the database into quarantine and then failed to move it
            // back, or may have moved it successfully and failed to write the
            // report. Either way the quarantine directory is the evidence.
            return recoverOrphanedQuarantine(dbFile, filesDir, nowMs) ?: Outcome.FreshInstall
        }

        val detail = runCatching { checker.quickCheck(dbFile) }
            .getOrElse { "checker crashed: ${it.javaClass.simpleName}" }
        if (detail == null) return Outcome.Healthy

        AppLogger.error(TAG, "[T-db-gate] quick_check FAILED for ${dbFile.name}: $detail")
        val stamp = SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date(nowMs))
        val quarantineDir = File(filesDir, "$QUARANTINE_PREFIX$stamp")

        // -- Phase 1: MOVE. All of it, or none of it. ----------------------
        val moved = mutableListOf<String>()
        try {
            if (!quarantineDir.mkdirs() && !quarantineDir.isDirectory) {
                throw IllegalStateException("cannot create ${quarantineDir.name}")
            }
            for (f in listOf(dbFile) + SIDECARS.map { File(dbFile.path + it) }) {
                if (!f.exists()) continue
                val target = File(quarantineDir, f.name)
                if (!renameFile(f, target) || !target.isFile) {
                    throw IllegalStateException("cannot move ${f.name} into quarantine")
                }
                moved += f.name
            }
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            AppLogger.error(
                TAG,
                "[T-db-gate] quarantine FAILED ($reason) after moving ${moved.size} file(s) - rolling back",
            )
            return rollBack(quarantineDir, dbFile, filesDir, moved, reason, detail, nowMs)
        }

        // -- Phase 2: REPORT. Diagnostic only; the bytes are already safe. --
        val reportWritten = runCatching {
            writeReport(
                filesDir,
                StartupFailureReport(
                    kind = KIND_CORRUPTION,
                    dbName = dbFile.name,
                    detail = detail,
                    quarantineDir = quarantineDir.name,
                    movedFiles = moved,
                    occurredAtMs = nowMs,
                ),
            )
        }.onFailure {
            // Quarantine still SUCCEEDED. Reporting it as failed would be the
            // lie that turns a preserved database into an unexplained reset,
            // and the next launch rebuilds the report from the directory.
            AppLogger.error(
                TAG,
                "[T-db-gate] report NOT written (${it.javaClass.simpleName}: ${it.message}); " +
                    "${quarantineDir.name} is the evidence",
            )
        }.isSuccess

        fsyncDirectory(filesDir)
        fsyncDirectory(quarantineDir)
        AppLogger.error(
            TAG,
            "[T-db-gate] quarantined ${moved.size} files -> ${quarantineDir.name} (report=$reportWritten)",
        )
        return Outcome.Quarantined(quarantineDir.name, reportWritten)
    }

    /**
     * Undo a partially completed quarantine, then classify what is left.
     *
     * Restore order matters: sidecars go back FIRST and the main database file
     * LAST. If the process dies halfway through the rollback, the main file is
     * then still inside quarantine — a state [recoverOrphanedQuarantine]
     * recognises. The inverse (main file restored, WAL still missing) would
     * look like a perfectly healthy database and be opened with silently lost
     * transactions.
     */
    private fun rollBack(
        quarantineDir: File,
        dbFile: File,
        filesDir: File,
        moved: List<String>,
        reason: String,
        detail: String,
        nowMs: Long,
    ): Outcome {
        val parent = dbFile.parentFile ?: filesDir
        // Sidecars first, main database last (see the KDoc above).
        val order = moved.filter { it != dbFile.name } + moved.filter { it == dbFile.name }
        val stranded = mutableListOf<String>()
        for (name in order) {
            val src = File(quarantineDir, name)
            val dst = File(parent, name)
            val ok = runCatching { renameFile(src, dst) }.getOrDefault(false)
            if (!ok || !dst.isFile) stranded += name
        }
        fsyncDirectory(parent)

        if (stranded.isEmpty()) {
            // Nothing left behind: drop the now-empty directory so it cannot be
            // mistaken for a real quarantine later.
            runCatching { quarantineDir.delete() }
            fsyncDirectory(filesDir)
            AppLogger.error(TAG, "[T-db-gate] rollback complete, database is back at ${dbFile.name}")
            return Outcome.QuarantineFailed("rolled back after: $reason")
        }

        // Something could not be restored. Leave loud, durable evidence behind
        // instead of pretending the rollback worked.
        writeRollbackMarker(
            filesDir = filesDir,
            quarantineDirName = quarantineDir.name,
            reason = reason,
            strandedFiles = stranded,
            occurredAtMs = nowMs,
            notified = false,
        )
        fsyncDirectory(quarantineDir)

        if (dbFile.isFile) {
            // The main file made it home; only sidecars are stranded. Room will
            // see a database again (still corrupt — that is why we are here),
            // so the caller must not open it silently, but nothing was lost and
            // the next launch retries the whole quarantine from scratch.
            AppLogger.error(TAG, "[T-db-gate] rollback partial, sidecars stranded: $stranded")
            return Outcome.QuarantineFailed(
                "rolled back ${dbFile.name}; stranded in ${quarantineDir.name}: ${stranded.joinToString()}",
            )
        }

        // Worst case: the main database file is still inside quarantine. The
        // data is NOT gone, but the on-disk state is the dangerous middle
        // state, so best-effort write a report too — a full disk may reject it,
        // which is exactly why recoverOrphanedQuarantine also reads the
        // directory listing rather than trusting this file.
        //
        // QuarantineIncomplete, not QuarantineFailed: AppDatabase throws on the
        // latter, which would crash-loop the user past the very dialog that
        // explains where their data went. There is nothing left to guard here —
        // the bytes are already parked in quarantine and Room will build an
        // empty database either way — so the correct move is to boot and tell
        // them, loudly, that this was not a fresh install.
        AppLogger.error(
            TAG,
            "[T-db-gate] rollback FAILED, ${dbFile.name} stranded in ${quarantineDir.name}",
        )
        val incompleteReason = "quarantine of a corrupt database failed ($reason) and the rollback " +
            "could not restore ${dbFile.name}; the copy survives in ${quarantineDir.name}. " +
            "Integrity check said: $detail"
        runCatching {
            writeReport(
                filesDir,
                StartupFailureReport(
                    kind = KIND_ROLLBACK_FAILED,
                    dbName = dbFile.name,
                    detail = incompleteReason,
                    quarantineDir = quarantineDir.name,
                    movedFiles = stranded,
                    occurredAtMs = nowMs,
                ),
            )
        }.onFailure {
            AppLogger.error(TAG, "[T-db-gate] rollback report NOT written: ${it.message}")
        }
        return Outcome.QuarantineIncomplete(quarantineDir.name, incompleteReason)
    }

    /**
     * Detect the middle state a failed rollback can leave behind: [dbFile] is
     * absent from its normal path, yet a copy of it sits in one of our
     * quarantine directories. Returns null only when there is genuinely no
     * database anywhere — a real fresh install.
     *
     * The directory listing is the primary evidence, the marker only supplies
     * the human-readable reason and timestamp: the marker is written
     * best-effort and can fail on the very full disk that caused the original
     * failure, while the renamed files are already there.
     */
    private fun recoverOrphanedQuarantine(dbFile: File, filesDir: File, nowMs: Long): Outcome? {
        val marker = File(filesDir, ROLLBACK_MARKER_FILE)
        val markerInfo = if (marker.isFile) {
            runCatching { JSONObject(marker.readText()) }.getOrNull()
        } else {
            null
        }
        val markerDirName = markerInfo?.optString("quarantine_dir")?.takeIf { it.isNotBlank() }

        val dirs = listQuarantineDirs(filesDir)
        val dir = markerDirName?.let { name -> dirs.firstOrNull { it.name == name } }
            ?: dirs.firstOrNull { File(it, dbFile.name).isFile }
            ?: return null

        val present = dir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()
        if (present.isEmpty() && markerDirName == null) return null

        val reason = markerInfo?.optString("reason")?.takeIf { it.isNotBlank() }
            ?: "an earlier launch moved ${dbFile.name} into ${dir.name} and did not finish"
        val occurredAt = dirStampToMillis(dir.name)
            ?: markerInfo?.optLong("occurred_at_ms")?.takeIf { it > 0L }
            ?: nowMs

        // Rebuild the report only while it is still news. Once the user has
        // seen and dismissed it (marker flipped to notified, report cleared)
        // re-writing it would nag on every single launch until the copy is
        // deleted, and clearReport would become meaningless.
        val alreadyNotified = markerInfo?.optBoolean("notified", false) == true
        val existing = pendingReport(filesDir, nowMs)
        if (!alreadyNotified && (existing == null || existing.quarantineDir != dir.name)) {
            val written = runCatching {
                writeReport(
                    filesDir,
                    StartupFailureReport(
                        kind = KIND_INCOMPLETE,
                        dbName = dbFile.name,
                        detail = reason,
                        quarantineDir = dir.name,
                        movedFiles = present,
                        occurredAtMs = occurredAt,
                    ),
                )
            }.onFailure {
                AppLogger.error(TAG, "[T-db-gate] could not rebuild report: ${it.message}")
            }.isSuccess
            if (written) {
                writeRollbackMarker(filesDir, dir.name, reason, present, occurredAt, notified = true)
            }
        }

        AppLogger.error(
            TAG,
            "[T-db-gate] orphaned quarantine recovered: ${dir.name} holds ${present.size} file(s)",
        )
        return Outcome.QuarantineIncomplete(dir.name, reason)
    }

    private fun writeRollbackMarker(
        filesDir: File,
        quarantineDirName: String,
        reason: String,
        strandedFiles: List<String>,
        occurredAtMs: Long,
        notified: Boolean,
    ) {
        runCatching {
            val json = JSONObject().apply {
                put("format", "minis.db-quarantine-rollback-failed")
                put("format_version", 1)
                put("quarantine_dir", quarantineDirName)
                put("reason", reason)
                put("stranded_files", JSONArray(strandedFiles))
                put("occurred_at_ms", occurredAtMs)
                put("notified", notified)
            }
            File(filesDir, ROLLBACK_MARKER_FILE).writeText(json.toString())
            fsyncDirectory(filesDir)
        }.onFailure {
            // Not fatal: the quarantine directory itself is still the evidence.
            AppLogger.error(TAG, "[T-db-gate] rollback marker NOT written: ${it.message}")
        }
    }

    /** Timestamp embedded in a quarantine directory name, or null if unparseable. */
    private fun dirStampToMillis(dirName: String): Long? {
        val stamp = dirName.removePrefix(QUARANTINE_PREFIX)
        if (stamp == dirName || stamp.isEmpty()) return null
        return runCatching {
            SimpleDateFormat(STAMP_PATTERN, Locale.US).parse(stamp)?.time
        }.getOrNull()
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
        val deleted = File(filesDir, dirName).deleteRecursively()
        if (deleted) {
            // The marker exists only to describe THIS directory. Leaving it
            // behind would make the next launch "recover" a quarantine that the
            // user already deleted on purpose.
            val marker = File(filesDir, ROLLBACK_MARKER_FILE)
            val pointsHere = marker.isFile && runCatching {
                JSONObject(marker.readText()).optString("quarantine_dir") == dirName
            }.getOrDefault(false)
            if (pointsHere) marker.delete()
        }
        return deleted
    }

    internal fun fsyncDirectory(dir: File) {
        runCatching {
            FileOutputStream(dir).use { it.fd.sync() }
        }
    }
}
