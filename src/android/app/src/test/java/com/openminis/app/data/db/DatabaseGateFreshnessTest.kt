package com.openminis.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-db-gate-freshness] Decision table, stamp IO and gate integration for
 * the startup-gate freshness cache.
 */
class DatabaseGateFreshnessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_700_000_000_000L

    private fun dbFile(name: String = "minis.db", size: Int = 1024): File =
        File(tmp.root, name).apply { writeBytes(ByteArray(size)) }

    private fun stampFor(
        db: File = dbFile(),
        checkedAtMs: Long = now,
        path: String = db.path,
        size: Long = db.length(),
    ): DatabaseGateFreshness.FreshnessStamp =
        DatabaseGateFreshness.FreshnessStamp(checkedAtMs, path, size)

    // -- decision table ------------------------------------------------------

    @Test
    fun `no stamp scans`() {
        assertFalse(DatabaseGateFreshness.isFresh(null, dbFile(), now))
    }

    @Test
    fun `stamp inside window is fresh`() {
        assertTrue(DatabaseGateFreshness.isFresh(stampFor(), dbFile(), now + 60_000))
    }

    @Test
    fun `window edge inclusive`() {
        val at = now + DatabaseGateFreshness.FRESH_WINDOW_MS - 1
        assertTrue(DatabaseGateFreshness.isFresh(stampFor(), dbFile(), at))
    }

    @Test
    fun `window expiry forces a rescan`() {
        val at = now + DatabaseGateFreshness.FRESH_WINDOW_MS
        assertFalse(DatabaseGateFreshness.isFresh(stampFor(), dbFile(), at))
    }

    @Test
    fun `clock rollback treated as stale`() {
        assertFalse(DatabaseGateFreshness.isFresh(stampFor(), dbFile(), now - 1))
    }

    @Test
    fun `path mismatch forces a rescan`() {
        val other = tmp.root.resolve("other.db").path
        assertFalse(DatabaseGateFreshness.isFresh(stampFor(path = other), dbFile(), now))
    }

    @Test
    fun `shrink forces a rescan`() {
        // Stamp larger than the file: the database was replaced, vacuumed,
        // restored or truncated — the safe direction is to re-scan.
        assertFalse(DatabaseGateFreshness.isFresh(stampFor(size = 4096), dbFile(), now))
    }

    @Test
    fun `growth inside the window stays fresh`() {
        // [T-db-gate-freshness-growth] The regression: WAL checkpointing and
        // ordinary writes append pages between launches, so the file is
        // bigger on the next cold start. The cache must still hit —
        // byte-exact equality re-ran the O(size) scan on every launch after
        // any usage and blocked the first session load behind it.
        val db = dbFile()
        DatabaseGateFreshness.recordHealthy(db, tmp.root, now)
        db.appendBytes(ByteArray(4096))
        assertTrue(
            DatabaseGateFreshness.isFresh(
                DatabaseGateFreshness.readStamp(tmp.root), db, now + 60_000,
            ),
        )
    }

    // -- stamp IO --------------------------------------------------------------

    @Test
    fun `record then read round-trips`() {
        val db = dbFile()
        DatabaseGateFreshness.recordHealthy(db, tmp.root, now)
        val stamp = DatabaseGateFreshness.readStamp(tmp.root)
        assertNotNull(stamp)
        assertEquals(now, stamp!!.checkedAtMs)
        assertEquals(db.path, stamp.dbPath)
        assertEquals(db.length(), stamp.dbSizeBytes)
    }

    @Test
    fun `corrupt stamp file reads null`() {
        tmp.newFile("db_gate_freshness.json").writeText("not json at all")
        assertNull(DatabaseGateFreshness.readStamp(tmp.root))
    }

    @Test
    fun `missing stamp file reads null`() {
        assertNull(DatabaseGateFreshness.readStamp(tmp.root))
    }

    @Test
    fun `invalidate removes the stamp`() {
        DatabaseGateFreshness.recordHealthy(dbFile(), tmp.root, now)
        DatabaseGateFreshness.invalidate(tmp.root)
        assertNull(DatabaseGateFreshness.readStamp(tmp.root))
    }

    // -- gate integration --------------------------------------------------------

    /** fun-interface fake that counts how many real scans happened. */
    private class CountingChecker(private val result: String?) : DatabaseHealthCheck.IntegrityChecker {
        var calls = 0
        override fun quickCheck(dbFile: File): String? {
            calls++
            return result
        }
    }

    @Test
    fun `fresh verdict skips the real scan`() {
        val db = dbFile()
        DatabaseGateFreshness.recordHealthy(db, tmp.root, now)
        val checker = CountingChecker(null)
        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(db, tmp.root, checker, now)
        assertEquals(DatabaseHealthCheck.Outcome.Healthy, outcome)
        assertEquals(0, checker.calls)
    }

    @Test
    fun `grown database skips the real scan`() {
        // [T-db-gate-freshness-growth] Gate-level regression: the exact
        // cold-start scenario — database scanned healthy on launch N, grown
        // by a day of writes, opened on launch N+1 — must not pay the
        // O(size) quick_check again before the first session load.
        val db = dbFile()
        DatabaseGateFreshness.recordHealthy(db, tmp.root, now)
        db.appendBytes(ByteArray(4096))
        val checker = CountingChecker(null)
        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(db, tmp.root, checker, now + 60_000)
        assertEquals(DatabaseHealthCheck.Outcome.Healthy, outcome)
        assertEquals(0, checker.calls)
    }

    @Test
    fun `passing scan writes a stamp for next launch`() {
        val db = dbFile()
        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(db, tmp.root, CountingChecker(null), now)
        assertEquals(DatabaseHealthCheck.Outcome.Healthy, outcome)
        assertTrue(DatabaseGateFreshness.isFresh(DatabaseGateFreshness.readStamp(tmp.root), db, now + 1))
    }

    @Test
    fun `failing scan invalidates and quarantines`() {
        val db = dbFile()
        // Stamp from beyond the freshness window so the gate actually reaches
        // the checker (a fresh stamp would legitimately skip it).
        DatabaseGateFreshness.recordHealthy(db, tmp.root, now - DatabaseGateFreshness.FRESH_WINDOW_MS - 1)
        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            db, tmp.root, CountingChecker("corrupt page"), now,
        )
        assertTrue(outcome is DatabaseHealthCheck.Outcome.Quarantined)
        assertNull(DatabaseGateFreshness.readStamp(tmp.root))
    }

    @Test
    fun `missing database invalidates and falls to fresh install`() {
        DatabaseGateFreshness.recordHealthy(dbFile(), tmp.root, now)
        val missing = File(tmp.root, "gone.db")
        val outcome = DatabaseHealthCheck.ensureHealthyOrQuarantined(
            missing, tmp.root, CountingChecker(null), now,
        )
        assertEquals(DatabaseHealthCheck.Outcome.FreshInstall, outcome)
        assertNull(DatabaseGateFreshness.readStamp(tmp.root))
    }
}
