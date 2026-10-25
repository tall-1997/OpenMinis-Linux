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

@RunWith(AndroidJUnit4::class)
class MessageSortOrderMigrationTest {
    private lateinit var file: File
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: androidx.sqlite.db.SupportSQLiteDatabase

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "message-sort-order-migration.db")
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
            raw.execSQL("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY)")
            raw.execSQL("""
                CREATE TABLE messages (
                    id TEXT NOT NULL PRIMARY KEY, session_id TEXT NOT NULL, role TEXT NOT NULL,
                    parts_json TEXT NOT NULL, created_at INTEGER NOT NULL, token_usage TEXT,
                    sort_order INTEGER NOT NULL, reasoning_content TEXT,
                    stream_interrupt_count INTEGER NOT NULL DEFAULT 0, updated_at INTEGER,
                    error_info TEXT, model_id TEXT, model_display_name TEXT,
                    provider_type TEXT, provider_instance_id TEXT, body_bytes INTEGER NOT NULL DEFAULT 0,
                    body_ref TEXT, body_sha TEXT, preview TEXT
                )
            """.trimIndent())
            raw.execSQL("CREATE INDEX index_messages_session_id_sort_order ON messages(session_id, sort_order)")
            raw.execSQL("INSERT INTO messages(id,session_id,role,parts_json,created_at,sort_order) VALUES ('z','s','user','z',100,7)")
            raw.execSQL("INSERT INTO messages(id,session_id,role,parts_json,created_at,sort_order) VALUES ('b','s','assistant','b',100,7)")
            raw.execSQL("INSERT INTO messages(id,session_id,role,parts_json,created_at,sort_order) VALUES ('a','s','user','a',50,2)")
            raw.version = 20
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
    }

    @After fun tearDown() {
        helper.close()
        file.delete()
    }

    @Test fun migrationRenumbersDuplicatesDeterministicallyAndEnforcesUniqueCursor() {
        AppDatabase.MIGRATION_20_21.migrate(db)
        db.query("SELECT id, sort_order FROM messages WHERE session_id='s' ORDER BY sort_order").use { c ->
            val rows = mutableListOf<Pair<String, Int>>()
            while (c.moveToNext()) rows += c.getString(0) to c.getInt(1)
            assertEquals(listOf("a" to 0, "b" to 1, "z" to 2), rows)
        }
        db.query("PRAGMA index_list(messages)").use { c ->
            var foundUnique = false
            while (c.moveToNext()) {
                if (c.getString(c.getColumnIndexOrThrow("name")) == "index_messages_session_id_sort_order") {
                    foundUnique = c.getInt(c.getColumnIndexOrThrow("unique")) == 1
                }
            }
            assertTrue("composite sort index must be unique", foundUnique)
        }
        db.execSQL("INSERT INTO messages(id,session_id,role,parts_json,created_at,sort_order) VALUES ('next','s','user','[]',200,3)")
    }
}
