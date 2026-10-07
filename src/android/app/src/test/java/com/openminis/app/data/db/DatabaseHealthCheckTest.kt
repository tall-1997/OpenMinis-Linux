package com.openminis.app.data.db

import java.io.File
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
}
