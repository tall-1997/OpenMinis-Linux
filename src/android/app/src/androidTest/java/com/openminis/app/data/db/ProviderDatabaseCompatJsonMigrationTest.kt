package com.openminis.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [T-compat-flags-persist] Instrumented replay of the `compat_json` migration
 * chain (ThinkingDormancyMigrationTest pattern): the column must exist and
 * round-trip after MIGRATION_7_8, and a pre-8 database must still read its
 * rows with the column reported NULL (no data rewrite, no provider drop).
 */
@RunWith(AndroidJUnit4::class)
class ProviderDatabaseCompatJsonMigrationTest {
    private lateinit var file: File
    private lateinit var raw: SQLiteDatabase
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var supportDb: SupportSQLiteDatabase

    /** The pre-8 (v7) shape of provider_model_entries, the table the migration targets. */
    private val v7EntriesDdl =
        "CREATE TABLE IF NOT EXISTS provider_model_entries (" +
            "id TEXT NOT NULL PRIMARY KEY, provider_instance_id TEXT NOT NULL, " +
            "base_model_json TEXT NOT NULL, overrides_json TEXT, is_custom INTEGER NOT NULL, " +
            "is_hidden INTEGER NOT NULL, sort_order INTEGER NOT NULL, user_modified_at INTEGER)"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "provider-compat-migration-test.db")
        file.delete()
        raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.version = 7
        raw.close()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        supportDb = helper.writableDatabase
        supportDb.execSQL(v7EntriesDdl)
    }

    @After
    fun tearDown() {
        helper.close()
        raw.close()
        file.delete()
    }

    @Test
    fun migration7To8AddsUsableCompatColumn() {
        supportDb.execSQL(
            "INSERT INTO provider_model_entries " +
                "(id, provider_instance_id, base_model_json, is_custom, is_hidden, sort_order) " +
                "VALUES ('inst-1/gpt', 'inst-1', '{}', 0, 0, 0)",
        )
        ProviderDatabase.MIGRATION_7_8.migrate(supportDb)
        // The new column is writable and round-trips a serialized CompatFlags blob.
        val blob = """{"maxTokensField":"MAX_TOKENS","supportsUsageInStreaming":false}"""
        supportDb.execSQL(
            "UPDATE provider_model_entries SET compat_json = ? WHERE id = 'inst-1/gpt'",
            arrayOf(blob),
        )
        supportDb
            .query("SELECT base_model_json, compat_json FROM provider_model_entries WHERE id='inst-1/gpt'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{}", cursor.getString(0))
                assertEquals("the compat blob round-trips", blob, cursor.getString(1))
            }
    }

    @Test
    fun preMigrationRowsReadNullCompat() {
        supportDb.execSQL(
            "INSERT INTO provider_model_entries " +
                "(id, provider_instance_id, base_model_json, is_custom, is_hidden, sort_order) " +
                "VALUES ('inst-1/claude', 'inst-1', '{}', 0, 0, 0)",
        )
        ProviderDatabase.MIGRATION_7_8.migrate(supportDb)
        // Existing rows keep their data and read NULL for the overlay: exactly
        // the pre-migration state (family defaults apply, nothing rewritten).
        supportDb
            .query("SELECT base_model_json, compat_json FROM provider_model_entries WHERE id='inst-1/claude'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{}", cursor.getString(0))
                // NULL = no overlay (family defaults apply), the exact
                // pre-migration state — nothing was rewritten.
                assertTrue("compat_json must read NULL on pre-8 rows", cursor.isNull(1))
            }
    }
}
