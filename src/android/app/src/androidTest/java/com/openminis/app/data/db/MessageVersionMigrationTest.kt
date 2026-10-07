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

/** [T-msg-version-archive] Migration tests for the message_versions table. */
@RunWith(AndroidJUnit4::class)
class MessageVersionMigrationTest {
    private lateinit var file: File
    private lateinit var raw: SQLiteDatabase
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var supportDb: androidx.sqlite.db.SupportSQLiteDatabase

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "message-version-migration-test.db")
        file.delete()
        raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.version = 21
        raw.close()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        supportDb = helper.writableDatabase
    }

    @After fun tearDown() {
        helper.close()
        raw.close()
        file.delete()
    }

    @Test fun migration21To22CreatesUsableMessageVersionsTable() {
        AppDatabase.MIGRATION_21_22.migrate(supportDb)
        supportDb.execSQL(
            "INSERT INTO sessions (id, title, model_id, created_at, updated_at, last_message, memory_enabled, memory_is_global, pinned_at, folder_id, category, source) " +
                "VALUES ('s1','t','m',1,1,NULL,0,0,NULL,NULL,NULL,NULL)",
        )
        supportDb.execSQL(
            "INSERT INTO message_versions (id, message_id, session_id, version_index, role, parts_json, body_ref, body_sha, reasoning_content, error_info, model_id, model_display_name, source_created_at, archived_at) " +
                "VALUES ('v1','msg1','s1',1,'assistant','[]',NULL,NULL,NULL,NULL,'gpt','GPT',1,2)",
        )
        supportDb.query("SELECT message_id, version_index, model_id FROM message_versions WHERE id='v1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("msg1", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals("gpt", cursor.getString(2))
        }
    }

    @Test fun migration21To22CascadesOnSessionDelete() {
        AppDatabase.MIGRATION_21_22.migrate(supportDb)
        supportDb.execSQL("PRAGMA foreign_keys=ON")
        supportDb.execSQL(
            "INSERT INTO sessions (id, title, model_id, created_at, updated_at, last_message, memory_enabled, memory_is_global, pinned_at, folder_id, category, source) " +
                "VALUES ('s1','t','m',1,1,NULL,0,0,NULL,NULL,NULL,NULL)",
        )
        supportDb.execSQL(
            "INSERT INTO message_versions (id, message_id, session_id, version_index, role, parts_json, body_ref, body_sha, reasoning_content, error_info, model_id, model_display_name, source_created_at, archived_at) " +
                "VALUES ('v1','msg1','s1',1,'assistant','[]',NULL,NULL,NULL,NULL,NULL,NULL,1,2)",
        )
        supportDb.execSQL("DELETE FROM sessions WHERE id='s1'")
        supportDb.query("SELECT COUNT(*) FROM message_versions WHERE session_id='s1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test fun migration22To21DropsTable() {
        AppDatabase.MIGRATION_21_22.migrate(supportDb)
        AppDatabase.MIGRATION_22_21.migrate(supportDb)
        val exists = supportDb.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='message_versions'",
        ).use { cursor -> cursor.moveToFirst() }
        assertFalse(exists)
    }
}
