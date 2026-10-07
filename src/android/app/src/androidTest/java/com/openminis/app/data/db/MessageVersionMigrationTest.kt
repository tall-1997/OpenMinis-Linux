package com.openminis.app.data.db

import android.database.sqlite.SQLiteDatabase
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
 * [T-msg-version-archive] Migration tests for the message_versions table.
 *
 * ## Why the schema is built by hand, not by MigrationTestHelper
 *
 * `MigrationTestHelper` replays exported schema JSON, which is the right tool in
 * principle and IS wired up (`app/build.gradle.kts` points the androidTest
 * assets at `schemas/`). But it opens the database at a version by RUNNING every
 * registered migration up to it, and `AppDatabase`'s chain mutates rows in ways
 * a bare file cannot satisfy without a matching seed — so the helper is fiddly
 * to drive for one table. The sibling [MessageAttributionMigrationTest] settled
 * on the same shape for the same reason: create the starting version's tables
 * from the committed schema, then invoke the one migration under test.
 *
 * The v21 column definitions below are copied VERBATIM from
 * `schemas/com.openminis.app.data.db.AppDatabase/21.json` (sessions, messages,
 * and their indices). They are NOT invented: message_versions has a foreign key
 * into `sessions(id) ON DELETE CASCADE`, so the cascade case only exercises real
 * behaviour if `sessions` is the real table. The earlier version of this file
 * left onCreate/onUpgrade empty — zero tables — so every `INSERT INTO sessions`
 * died with "no such table"; it also named a `memory_is_global` column that has
 * never existed in any schema. Both are fixed here.
 */
@RunWith(AndroidJUnit4::class)
class MessageVersionMigrationTest {
    private lateinit var file: File
    private lateinit var raw: SQLiteDatabase
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var supportDb: SupportSQLiteDatabase

    /**
     * The real v21 schema for the two tables message_versions depends on, taken
     * from 21.json. Only sessions + messages are needed: message_versions'
     * foreign key points at sessions, and messages is included because the task
     * asks for it and it is what an upgraded database actually contains.
     */
    private fun createV21Schema(d: SQLiteDatabase) {
        d.execSQL(
            "CREATE TABLE IF NOT EXISTS `sessions` (" +
                "`id` TEXT NOT NULL, " +
                "`title` TEXT, " +
                "`model_id` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "`category` TEXT, " +
                "`last_message` TEXT, " +
                "`model_binding` TEXT, " +
                "`source` TEXT, " +
                "`memory_enabled` INTEGER NOT NULL, " +
                "`pinned_at` INTEGER, " +
                "`edit_count` INTEGER NOT NULL, " +
                "`thinking_override` TEXT, " +
                "`folder_id` TEXT, " +
                "`permission_mode` TEXT, " +
                "PRIMARY KEY(`id`))",
        )
        d.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_folder_id` ON `sessions` (`folder_id`)")
        d.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_updated_at` ON `sessions` (`updated_at`)")

        d.execSQL(
            "CREATE TABLE IF NOT EXISTS `messages` (" +
                "`id` TEXT NOT NULL, " +
                "`session_id` TEXT NOT NULL, " +
                "`role` TEXT NOT NULL, " +
                "`parts_json` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`token_usage` TEXT, " +
                "`sort_order` INTEGER NOT NULL, " +
                "`reasoning_content` TEXT, " +
                "`stream_interrupt_count` INTEGER NOT NULL, " +
                "`updated_at` INTEGER, " +
                "`error_info` TEXT, " +
                "`model_id` TEXT, " +
                "`model_display_name` TEXT, " +
                "`provider_type` TEXT, " +
                "`provider_instance_id` TEXT, " +
                "`body_bytes` INTEGER NOT NULL, " +
                "`body_ref` TEXT, " +
                "`body_sha` TEXT, " +
                "`preview` TEXT, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        d.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_session_id_sort_order` " +
                "ON `messages` (`session_id`, `sort_order`)",
        )
    }

    /** A minimal valid v21 sessions row (all NOT NULL columns supplied). */
    private fun insertSession(id: String) {
        supportDb.execSQL(
            "INSERT INTO sessions (id, title, model_id, created_at, updated_at, memory_enabled, edit_count) " +
                "VALUES ('$id','t','m',1,1,0,0)",
        )
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "message-version-migration-test.db")
        file.delete()
        // Build the v21 schema in a raw handle, stamp the version, close it.
        raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        createV21Schema(raw)
        raw.version = 21
        raw.close()

        // Reopen through the Support wrapper so Migration.migrate() — which
        // takes a SupportSQLiteDatabase — runs against the SAME file. The file's
        // user_version is already 21 and the callback asks for 21, so neither
        // onCreate nor onUpgrade fires and the tables survive untouched.
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        supportDb = helper.writableDatabase
        // SQLite defaults foreign_keys to OFF; Room turns it on. Required for
        // the ON DELETE CASCADE assertions to mean anything.
        supportDb.execSQL("PRAGMA foreign_keys=ON")
    }

    @After
    fun tearDown() {
        runCatching { helper.close() }
        runCatching { raw.close() }
        file.delete()
    }

    @Test
    fun migration21To22CreatesUsableMessageVersionsTable() {
        AppDatabase.MIGRATION_21_22.migrate(supportDb)
        // The table must exist before anything is inserted into it.
        val tableExists = supportDb.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='message_versions'",
        ).use { cursor -> cursor.moveToFirst() }
        assertTrue("MIGRATION_21_22 must create message_versions", tableExists)
        insertSession("s1")
        supportDb.execSQL(
            "INSERT INTO message_versions (id, message_id, session_id, version_index, role, " +
                "parts_json, body_ref, body_sha, reasoning_content, error_info, model_id, " +
                "model_display_name, source_created_at, archived_at) " +
                "VALUES ('v1','msg1','s1',1,'assistant','[]',NULL,NULL,NULL,NULL,'gpt','GPT',1,2)",
        )
        supportDb.query(
            "SELECT message_id, version_index, model_id FROM message_versions WHERE id='v1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("msg1", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals("gpt", cursor.getString(2))
        }
    }

    @Test
    fun migration21To22CascadesOnSessionDelete() {
        AppDatabase.MIGRATION_21_22.migrate(supportDb)
        insertSession("s1")
        supportDb.execSQL(
            "INSERT INTO message_versions (id, message_id, session_id, version_index, role, " +
                "parts_json, body_ref, body_sha, reasoning_content, error_info, model_id, " +
                "model_display_name, source_created_at, archived_at) " +
                "VALUES ('v1','msg1','s1',1,'assistant','[]',NULL,NULL,NULL,NULL,NULL,NULL,1,2)",
        )
        supportDb.execSQL("DELETE FROM sessions WHERE id='s1'")
        supportDb.query(
            "SELECT COUNT(*) FROM message_versions WHERE session_id='s1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration22To21DropsTable() {
        AppDatabase.MIGRATION_21_22.migrate(supportDb)
        AppDatabase.MIGRATION_22_21.migrate(supportDb)
        val exists = supportDb.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='message_versions'",
        ).use { cursor -> cursor.moveToFirst() }
        assertFalse(exists)
    }
}
