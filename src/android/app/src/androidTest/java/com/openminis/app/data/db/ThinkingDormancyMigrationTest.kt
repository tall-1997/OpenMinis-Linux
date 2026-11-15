package com.openminis.app.data.db

import android.database.sqlite.SQLiteDatabase
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
 * [T-thinking-dormant-restore] (P2 1-8) Instrumented replay of the
 * `sessions.dormant_thinking_override` migration chain: the column must exist
 * and round-trip after MIGRATION_33_34, and the downgrade no-op must keep the
 * data on disk for a version round-trip (the keep-compatible-downgrade policy
 * every additive column here follows).
 */
@RunWith(AndroidJUnit4::class)
class ThinkingDormancyMigrationTest {
    private lateinit var file: File
    private lateinit var raw: SQLiteDatabase
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var supportDb: androidx.sqlite.db.SupportSQLiteDatabase

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "thinking-dormancy-migration-test.db")
        file.delete()
        raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.version = 33
        raw.close()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(33) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        supportDb = helper.writableDatabase
        // Minimal sessions shape the migration targets (id + the pre-34
        // columns the INSERT touches); the real schema is exercised by the
        // SchemaMigrationDriftTest replay.
        supportDb.execSQL(
            "CREATE TABLE IF NOT EXISTS sessions (" +
                "id TEXT NOT NULL PRIMARY KEY, thinking_override TEXT, updated_at INTEGER NOT NULL DEFAULT 0)",
        )
    }

    @After fun tearDown() {
        helper.close()
        raw.close()
        file.delete()
    }

    @Test fun migration33To34AddsUsableDormantColumn() {
        supportDb.execSQL(
            "INSERT INTO sessions (id, thinking_override, updated_at) VALUES ('s1', 'HIGH', 1)",
        )
        ThinkingDormancyMigrations.MIGRATION_33_34.migrate(supportDb)
        supportDb.execSQL(
            "UPDATE sessions SET dormant_thinking_override = 'HIGH' WHERE id = 's1'",
        )
        supportDb.query("SELECT thinking_override, dormant_thinking_override FROM sessions WHERE id='s1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("HIGH", cursor.getString(0))
            assertEquals("the parked level round-trips", "HIGH", cursor.getString(1))
        }
    }

    @Test fun migration34To33KeepsDormantDataOnDisk() {
        supportDb.execSQL(
            "INSERT INTO sessions (id, thinking_override, updated_at) VALUES ('s1', 'MEDIUM', 1)",
        )
        ThinkingDormancyMigrations.MIGRATION_33_34.migrate(supportDb)
        supportDb.execSQL(
            "UPDATE sessions SET dormant_thinking_override = 'MEDIUM' WHERE id = 's1'",
        )
        // Downgrade is a deliberate no-op: the older build ignores the column,
        // and the round-trip back up must not have lost the park.
        ThinkingDormancyMigrations.MIGRATION_34_33.migrate(supportDb)
        supportDb.query("SELECT dormant_thinking_override FROM sessions WHERE id='s1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("MEDIUM", cursor.getString(0))
        }
    }
}
