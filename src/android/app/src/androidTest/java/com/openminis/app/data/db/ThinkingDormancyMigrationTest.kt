package com.openminis.app.data.db

import android.database.sqlite.SQLiteDatabase
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
 * [T-thinking-dormant-restore] (P2 1-8) Instrumented replay of the
 * `sessions.dormant_thinking_override` migration chain: the column must exist
 * and round-trip after MIGRATION_33_34, and MIGRATION_34_33 must land the
 * table EXACTLY on the 33 shape — park column gone, every other column and
 * its data kept, the two indices rebuilt (review P1-1: the 33 build's Room
 * validates the downgraded file with TableInfo equality, so one extra column
 * is a startup crash, not a lost park).
 *
 * The table under test is the full 33.json `sessions` shape (columns +
 * indices): the downgrade rebuild names every surviving column in its
 * INSERT ... SELECT, so the old minimal two-column fixture can no longer
 * replay it.
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
        // The 33.json `sessions` shape the 33 build validates against (createSql
        // copied verbatim; the downgrade migration must land back on exactly
        // this, and the real schema is additionally exercised by the
        // SchemaMigrationDriftTest replay).
        supportDb.execSQL(
            "CREATE TABLE IF NOT EXISTS sessions (" +
                "`id` TEXT NOT NULL, `title` TEXT, `model_id` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "`category` TEXT, `last_message` TEXT, `model_binding` TEXT, " +
                "`source` TEXT, `memory_enabled` INTEGER NOT NULL, `pinned_at` INTEGER, " +
                "`edit_count` INTEGER NOT NULL, `thinking_override` TEXT, `folder_id` TEXT, " +
                "`permission_mode` TEXT, `branch_of` TEXT, `branch_point` TEXT, " +
                "`archived_at` INTEGER, PRIMARY KEY(`id`))",
        )
        supportDb.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_folder_id ON sessions (`folder_id`)")
        supportDb.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_updated_at ON sessions (`updated_at`)")
    }

    @After fun tearDown() {
        helper.close()
        raw.close()
        file.delete()
    }

    @Test fun migration33To34AddsUsableDormantColumn() {
        supportDb.execSQL(
            "INSERT INTO sessions (id, created_at, thinking_override, updated_at) VALUES ('s1', 1, 'HIGH', 1)",
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

    @Test fun migration34To33DropsTheParkColumnAndKeepsEverythingElse() {
        supportDb.execSQL(
            "INSERT INTO sessions (id, title, created_at, thinking_override, updated_at, memory_enabled, edit_count, folder_id) " +
                "VALUES ('s1', 'keep me', 1, 'MEDIUM', 42, 1, 7, 'f1')",
        )
        ThinkingDormancyMigrations.MIGRATION_33_34.migrate(supportDb)
        supportDb.execSQL(
            "UPDATE sessions SET dormant_thinking_override = 'MEDIUM' WHERE id = 's1'",
        )

        // Downgrade: the park column is REMOVED so the 33 build's
        // onValidateSchema sees its exact compiled shape — keeping it was the
        // shipped startup crash (review P1-1).
        ThinkingDormancyMigrations.MIGRATION_34_33.migrate(supportDb)

        val columns = mutableListOf<String>()
        supportDb.query("PRAGMA table_info(sessions)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) columns.add(cursor.getString(nameIdx))
        }
        assertFalse(
            "dormant_thinking_override must be gone after the downgrade",
            "dormant_thinking_override" in columns,
        )
        assertTrue(
            "every surviving column must stay",
            setOf("id", "title", "model_id", "created_at", "updated_at", "thinking_override", "folder_id", "archived_at")
                .all { it in columns },
        )

        // Non-park data survives the rebuild untouched.
        supportDb.query("SELECT title, thinking_override, updated_at, memory_enabled, edit_count, folder_id FROM sessions WHERE id='s1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("keep me", cursor.getString(0))
            assertEquals("the visible thinking level is untouched", "MEDIUM", cursor.getString(1))
            assertEquals(42L, cursor.getLong(2))
            assertEquals(1, cursor.getInt(3))
            assertEquals(7, cursor.getInt(4))
            assertEquals("f1", cursor.getString(5))
        }

        // The rebuilt table carries the 33 indices again (a rebuilt table
        // starts with none).
        val indices = mutableListOf<String>()
        supportDb.query("PRAGMA index_list(sessions)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) indices.add(cursor.getString(nameIdx))
        }
        assertTrue("index_sessions_folder_id must be rebuilt", "index_sessions_folder_id" in indices)
        assertTrue("index_sessions_updated_at must be rebuilt", "index_sessions_updated_at" in indices)
    }
}
