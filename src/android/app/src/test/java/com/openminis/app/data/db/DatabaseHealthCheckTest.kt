package com.openminis.app.data.db

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseHealthCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun restoreRenameHook() {
        // Every failure-injection test below swaps out the rename primitive;
        // leaking it into the next test would make unrelated cases fail in
        // confusing ways.
        DatabaseHealthCheck.resetRenameFileForTest()
    }

    private fun fakeDb(filesDir: File, sidecars: Boolean = true): File {
        val dbDir = File(filesDir, "databases").apply { mkdirs() }
        val db = File(dbDir, "minis.db").apply { writeBytes(ByteArray(4096) { 7 }) }
        if (sidecars) {
            File(db.path + "-wal").writeBytes(ByteArray(128) { 1 })
            File(db.path + "-shm").writeBytes(ByteArray(64) { 2 })
        }
        return db
    }

    @Test
    fun `missing database file is a fresh install, checker never runs`() {
        var checkerRan = false
        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            dbFile = File(tmp.root, "databases/minis.db"),
            filesDir = tmp.root,
            checker = { checkerRan = true; null },
        )
        assertEquals(DatabaseHealthCheck.Outcome.FreshInstall, outcome)
        assertFalse(checkerRan)
    }

    @Test
    fun `healthy database passes through untouched and writes no report`() {
        val filesDir = tmp.root
        val db = fakeDb(filesDir)
        val before = db.readBytes()

        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            dbFile = db, filesDir = filesDir, checker = { null },
        )

        assertEquals(DatabaseHealthCheck.Outcome.Healthy, outcome)
        assertTrue(db.isFile)
        assertTrue(db.readBytes().contentEquals(before))
        assertNull(DatabaseHealthCheck.pendingReport(filesDir))
        assertTrue(DatabaseHealthCheck.listQuarantineDirs(filesDir).isEmpty())
    }

    @Test
    fun `corrupt database is quarantined whole - db plus wal and shm - and a report is written`() {
        val filesDir = tmp.root
        val db = fakeDb(filesDir)
        val dbBytes = db.readBytes()
        val walBytes = File(db.path + "-wal").readBytes()

        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            dbFile = db, filesDir = filesDir,
            checker = { "page 4: checksum mismatch" },
            nowMs = 1_760_000_000_000L,
        )

        assertTrue(outcome is DatabaseHealthCheck.Outcome.Quarantined)
        val dirName = (outcome as DatabaseHealthCheck.Outcome.Quarantined).quarantineDirName
        // Original location is empty so Room creates a fresh database.
        assertFalse(db.exists())
        assertFalse(File(db.path + "-wal").exists())
        assertFalse(File(db.path + "-shm").exists())
        // The user's bytes survive intact in quarantine.
        val qdir = File(filesDir, dirName)
        assertTrue(File(qdir, "minis.db").readBytes().contentEquals(dbBytes))
        assertTrue(File(qdir, "minis.db-wal").readBytes().contentEquals(walBytes))
        assertTrue(File(qdir, "minis.db-shm").isFile)

        val report = DatabaseHealthCheck.pendingReport(filesDir, nowMs = 1_760_000_000_001L)
        assertNotNull(report)
        report!!
        assertEquals("database_corruption", report.kind)
        assertEquals("page 4: checksum mismatch", report.detail)
        assertEquals(dirName, report.quarantineDir)
        assertEquals(listOf("minis.db", "minis.db-wal", "minis.db-shm"), report.movedFiles)
    }

    @Test
    fun `checker crash is treated as corruption - quarantine still protects the user`() {
        val filesDir = tmp.root
        val db = fakeDb(filesDir, sidecars = false)

        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            dbFile = db, filesDir = filesDir,
            checker = { throw RuntimeException("native exploded") },
        )

        assertTrue(outcome is DatabaseHealthCheck.Outcome.Quarantined)
        assertFalse(db.exists())
        val report = DatabaseHealthCheck.pendingReport(filesDir)!!
        assertTrue(report.detail.startsWith("checker crashed:"))
        assertEquals(listOf("minis.db"), report.movedFiles)
    }

    @Test
    fun `report round-trips through json and expires after the validity window`() {
        val filesDir = tmp.root
        val report = DatabaseHealthCheck.StartupFailureReport(
            kind = "database_corruption",
            dbName = "minis.db",
            detail = "d",
            quarantineDir = "minis-db-corrupt-20261007-120000",
            movedFiles = listOf("minis.db"),
            occurredAtMs = 1_000_000L,
        )
        DatabaseHealthCheck.writeReport(filesDir, report)

        val within = DatabaseHealthCheck.pendingReport(filesDir, nowMs = 1_000_000L + 1000)
        assertEquals(report, within)

        val stale = DatabaseHealthCheck.pendingReport(
            filesDir,
            nowMs = 1_000_000L + DatabaseHealthCheck.REPORT_VALIDITY_MS + 1,
        )
        assertNull(stale)

        DatabaseHealthCheck.clearReport(filesDir)
        assertNull(DatabaseHealthCheck.pendingReport(filesDir, nowMs = 1_000_000L))
    }

    @Test
    fun `unparseable report file is ignored, not crashed on`() {
        val filesDir = tmp.root
        File(filesDir, DatabaseHealthCheck.REPORT_FILE).writeText("{ not json")
        assertNull(DatabaseHealthCheck.pendingReport(filesDir))
    }

    @Test
    fun `deleteQuarantine removes the directory and refuses path traversal`() {
        val filesDir = tmp.root
        val q = File(filesDir, "minis-db-corrupt-20261007-120000").apply { mkdirs() }
        File(q, "minis.db").writeText("x")
        val sentinel = File(filesDir, "keep.me").apply { writeText("y") }

        assertTrue(DatabaseHealthCheck.deleteQuarantine(filesDir, q.name))
        assertFalse(q.exists())

        assertFalse(DatabaseHealthCheck.deleteQuarantine(filesDir, "../keep.me"))
        assertFalse(DatabaseHealthCheck.deleteQuarantine(filesDir, "not-a-quarantine-dir"))
        assertTrue(sentinel.isFile)
    }

    @Test
    fun `listQuarantineDirs only matches our prefix, newest first`() {
        val filesDir = tmp.root
        File(filesDir, "minis-db-corrupt-20261001-000000").mkdirs()
        File(filesDir, "minis-db-corrupt-20261007-000000").mkdirs()
        File(filesDir, "unrelated-dir").mkdirs()
        File(filesDir, "minis-db-corrupt-file").writeText("not a dir")

        val dirs = DatabaseHealthCheck.listQuarantineDirs(filesDir)
        assertEquals(
            listOf("minis-db-corrupt-20261007-000000", "minis-db-corrupt-20261001-000000"),
            dirs.map { it.name },
        )
    }

    // -- [T-db-gate] all-or-nothing quarantine -----------------------------

    /**
     * Bug 1 core case: a sidecar refuses to move, so the quarantine is
     * abandoned and EVERYTHING already moved is renamed back. The main
     * database must be byte-identical at its original path, and a re-run of
     * the gate must NOT report FreshInstall (the failure that erased history).
     */
    @Test
    fun `sidecar move failure rolls the main database back - next launch is not a fresh install`() {
        val filesDir = tmp.root
        val db = fakeDb(filesDir)
        val dbBytes = db.readBytes()

        DatabaseHealthCheck.renameFile = { src, dst ->
            if (src.name == "minis.db-wal") false else src.renameTo(dst)
        }

        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            dbFile = db, filesDir = filesDir, checker = { "page 4: checksum mismatch" },
        )

        assertTrue(outcome is DatabaseHealthCheck.Outcome.QuarantineFailed)
        assertTrue("main db must be restored", db.isFile)
        assertTrue("restored bytes must be intact", db.readBytes().contentEquals(dbBytes))
        // Rollback left nothing behind, so the empty quarantine dir is removed.
        assertTrue(DatabaseHealthCheck.listQuarantineDirs(filesDir).isEmpty())

        // The whole point: a re-run sees a real database, never FreshInstall.
        DatabaseHealthCheck.resetRenameFileForTest()
        val relaunch = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            dbFile = db, filesDir = filesDir, checker = { null },
        )
        assertEquals(DatabaseHealthCheck.Outcome.Healthy, relaunch)
    }

    /**
     * Bug 1 requirement 3: the files moved into quarantine successfully but
     * the report could not be written (models a full disk). That is still a
     * SUCCESSFUL quarantine — the data is safe — flagged reportWritten=false,
     * and the next launch rebuilds the report from the directory contents.
     */
    @Test
    fun `report write failure after a completed move is still a successful quarantine - next launch rebuilds the prompt`() {
        val filesDir = tmp.root
        val db = fakeDb(filesDir)
        val dbBytes = db.readBytes()

        // Occupy the report's tmp path with a DIRECTORY so writeText inside
        // writeReport throws (same shape as ENOSPC / EISDIR).
        File(filesDir, DatabaseHealthCheck.REPORT_FILE + ".tmp").mkdirs()

        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            dbFile = db, filesDir = filesDir, checker = { "page 4: checksum mismatch" },
            nowMs = 1_760_000_000_000L,
        )

        assertTrue(outcome is DatabaseHealthCheck.Outcome.Quarantined)
        val q = outcome as DatabaseHealthCheck.Outcome.Quarantined
        assertFalse("report write failed, must be recorded", q.reportWritten)
        // Data survived the move into quarantine.
        assertFalse(db.exists())
        assertTrue(
            File(filesDir, q.quarantineDirName + "/minis.db").readBytes().contentEquals(dbBytes),
        )
        // No report exists yet — the write failed.
        assertNull(DatabaseHealthCheck.pendingReport(filesDir, nowMs = 1_760_000_000_001L))

        // Next launch with the obstruction gone: the gate must recognise the
        // stranded database and rebuild the prompt from the directory alone.
        File(filesDir, DatabaseHealthCheck.REPORT_FILE + ".tmp").deleteRecursively()
        val relaunch = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            dbFile = db, filesDir = filesDir, checker = { "corrupt" },
            nowMs = 1_760_000_000_002L,
        )
        assertTrue(relaunch is DatabaseHealthCheck.Outcome.QuarantineIncomplete)

        val report = DatabaseHealthCheck.pendingReport(filesDir, nowMs = 1_760_000_000_003L)
        assertNotNull("report must be rebuilt for the dialog", report)
        assertEquals(q.quarantineDirName, report!!.quarantineDir)
        assertEquals(DatabaseHealthCheck.KIND_INCOMPLETE, report.kind)
    }

    /**
     * Bug 1 requirement 2: the rollback ITSELF fails and the main database is
     * left inside quarantine. This must never look like a fresh install — it
     * leaves a report AND a rollback marker, and the next launch still reports
     * QuarantineIncomplete.
     */
    @Test
    fun `rollback that cannot restore the main database leaves durable evidence - never a fresh install`() {
        val filesDir = tmp.root
        val db = fakeDb(filesDir)

        DatabaseHealthCheck.renameFile = { src, dst ->
            val srcInQ = src.parentFile?.name?.startsWith(DatabaseHealthCheck.QUARANTINE_PREFIX) == true
            val dstInQ = dst.parentFile?.name?.startsWith(DatabaseHealthCheck.QUARANTINE_PREFIX) == true
            when {
                // Phase 1: the wal refuses to move in, forcing a rollback.
                dstInQ && src.name == "minis.db-wal" -> false
                // Rollback: the main db cannot be moved back out — stranded.
                srcInQ && src.name == "minis.db" -> false
                else -> src.renameTo(dst)
            }
        }

        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            dbFile = db, filesDir = filesDir, checker = { "page 4: checksum mismatch" },
            nowMs = 1_760_000_000_000L,
        )

        assertTrue(outcome is DatabaseHealthCheck.Outcome.QuarantineIncomplete)
        val qi = outcome as DatabaseHealthCheck.Outcome.QuarantineIncomplete
        // Data is parked in quarantine, not lost.
        assertFalse(db.exists())
        assertTrue(File(filesDir, qi.quarantineDirName + "/minis.db").isFile)
        // Durable evidence: a report AND the rollback marker.
        val report = DatabaseHealthCheck.pendingReport(filesDir, nowMs = 1_760_000_000_001L)
        assertNotNull(report)
        assertEquals(DatabaseHealthCheck.KIND_ROLLBACK_FAILED, report!!.kind)
        assertTrue(File(filesDir, DatabaseHealthCheck.ROLLBACK_MARKER_FILE).isFile)

        // Even on a healthy filesystem afterwards, the state is recognised.
        DatabaseHealthCheck.resetRenameFileForTest()
        val relaunch = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            dbFile = db, filesDir = filesDir, checker = { "corrupt" },
            nowMs = 1_760_000_000_002L,
        )
        assertTrue(relaunch is DatabaseHealthCheck.Outcome.QuarantineIncomplete)
    }

    /**
     * A database missing from its home but present in a quarantine directory,
     * with NO report and NO marker, is recovered from the directory listing
     * alone — the evidence that survives even a totally full disk.
     */
    @Test
    fun `stranded database with no report is recovered from the quarantine directory alone`() {
        val filesDir = tmp.root
        val nowMs = 1_760_000_000_000L
        val db = File(File(filesDir, "databases").apply { mkdirs() }, "minis.db") // absent
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date(nowMs))
        val qdir = File(filesDir, DatabaseHealthCheck.QUARANTINE_PREFIX + stamp).apply { mkdirs() }
        File(qdir, "minis.db").writeBytes(ByteArray(64) { 9 })

        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            dbFile = db, filesDir = filesDir, checker = { null }, nowMs = nowMs,
        )

        assertTrue(outcome is DatabaseHealthCheck.Outcome.QuarantineIncomplete)
        val report = DatabaseHealthCheck.pendingReport(filesDir, nowMs = nowMs + 1000)
        assertNotNull(report)
        assertEquals(qdir.name, report!!.quarantineDir)
    }

    /**
     * Guard against over-eager recovery: a quarantine directory that does NOT
     * contain the database (or an unrelated leftover) is still a genuine
     * FreshInstall.
     */
    @Test
    fun `quarantine directory without the database is still a genuine fresh install`() {
        val filesDir = tmp.root
        val db = File(File(filesDir, "databases").apply { mkdirs() }, "minis.db")
        File(filesDir, DatabaseHealthCheck.QUARANTINE_PREFIX + "20261007-120000").mkdirs()

        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            dbFile = db, filesDir = filesDir, checker = { null },
        )
        assertEquals(DatabaseHealthCheck.Outcome.FreshInstall, outcome)
    }
}
