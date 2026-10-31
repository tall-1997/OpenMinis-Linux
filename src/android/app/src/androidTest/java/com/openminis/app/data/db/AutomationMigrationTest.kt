package com.openminis.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [T-conditional-automation] Migration tests for the automation_rules /
 * automation_runs pair and their history index.
 *
 * The v23 history index (`index_automation_runs_rule_id_fired_at`) is the
 * load-bearing piece here: MIGRATION_22_23 creates it by hand, and Room's
 * post-upgrade validation crashes the whole app with
 * "Migration didn't properly handle: automation_runs" if the same index is
 * not declared on [com.openminis.app.automation.AutomationRunEntity]. That
 * declaration is asserted by SchemaMigrationDriftTest at unit-test time;
 * this test asserts the migration side actually builds the table shape Room
 * expects, including the index and its column order.
 *
 * The starting v22 database is intentionally EMPTY: MIGRATION_22_23 is purely
 * additive and neither new table references any pre-existing table, so there
 * is no dependency to seed (the sibling MessageVersionMigrationTest seeds
 * sessions/messages because message_versions has a real foreign key — this
 * pair has none).
 */
@RunWith(AndroidJUnit4::class)
class AutomationMigrationTest {
    private lateinit var file: File
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var supportDb: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "automation-migration-test.db")
        file.delete()
        // A real v22 database has no automation tables at all. Open an empty
        // file and stamp it as v22, then let the Support wrapper attach to it
        // so Migration.migrate() — which takes a SupportSQLiteDatabase — runs
        // against the same file.
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.version = 22
        raw.close()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(22) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        supportDb = helper.writableDatabase
    }

    @After
    fun tearDown() {
        runCatching { helper.close() }
        file.delete()
    }

    private fun tableExists(name: String): Boolean =
        supportDb.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='$name'",
        ).use { it.moveToFirst() }

    private fun columnNames(table: String): Set<String> {
        val cols = mutableSetOf<String>()
        supportDb.query("PRAGMA table_info(`$table`)").use { c ->
            val idx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) cols.add(c.getString(idx))
        }
        return cols
    }

    private fun indexOn(table: String, name: String): Pair<List<String>, Boolean>? {
        supportDb.query("PRAGMA index_list(`$table`)").use { c ->
            while (c.moveToNext()) {
                if (c.getString(c.getColumnIndexOrThrow("name")) != name) continue
                val unique = c.getInt(c.getColumnIndexOrThrow("unique")) == 1
                val cols = mutableListOf<String>()
                supportDb.query("PRAGMA index_info(`$name`)").use { ic ->
                    val cidx = ic.getColumnIndexOrThrow("name")
                    while (ic.moveToNext()) cols.add(ic.getString(cidx))
                }
                return cols to unique
            }
        }
        return null
    }

    @Test
    fun migration22To23CreatesBothTables() {
        AppDatabase.MIGRATION_22_23.migrate(supportDb)
        assertTrue("MIGRATION_22_23 must create automation_rules", tableExists("automation_rules"))
        assertTrue("MIGRATION_22_23 must create automation_runs", tableExists("automation_runs"))
        val rules = columnNames("automation_rules")
        for (col in listOf("id", "name", "spec_json", "enabled", "created_at", "updated_at", "last_fired_at")) {
            assertTrue("automation_rules.$col missing", col in rules)
        }
        val runs = columnNames("automation_runs")
        for (col in listOf("run_id", "rule_id", "fired_at", "outcome", "session_id", "preview")) {
            assertTrue("automation_runs.$col missing", col in runs)
        }
    }

    @Test
    fun migration22To23BuildsTheHistoryIndexRoomExpects() {
        AppDatabase.MIGRATION_22_23.migrate(supportDb)
        val (cols, unique) = indexOn("automation_runs", "index_automation_runs_rule_id_fired_at")
            ?: error("index_automation_runs_rule_id_fired_at not created by MIGRATION_22_23")
        assertEquals(
            "index column order must match the entity declaration",
            listOf("rule_id", "fired_at"),
            cols,
        )
        assertFalse("history index must not be unique", unique)
    }

    @Test
    fun migration22To23ThenInsertAndQueryRoundTrip() {
        AppDatabase.MIGRATION_22_23.migrate(supportDb)
        supportDb.execSQL(
            "INSERT INTO automation_runs (rule_id, fired_at, outcome, session_id, preview) " +
                "VALUES ('r1', 100, 'fired', 's1', 'p')",
        )
        supportDb.query(
            "SELECT rule_id, fired_at, outcome FROM automation_runs WHERE rule_id='r1'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("r1", c.getString(0))
            assertEquals(100, c.getInt(1))
            assertEquals("fired", c.getString(2))
        }
    }

    @Test
    fun migration23To22KeepsRows() {
        AppDatabase.MIGRATION_22_23.migrate(supportDb)
        supportDb.execSQL(
            "INSERT INTO automation_runs (rule_id, fired_at, outcome) VALUES ('r1', 100, 'fired')",
        )
        // MIGRATION_23_22 is intentionally a no-op: older builds ignore extra
        // tables, and the rows must survive a version round trip.
        AppDatabase.MIGRATION_23_22.migrate(supportDb)
        supportDb.query(
            "SELECT COUNT(*) FROM automation_runs WHERE rule_id='r1'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }
}
