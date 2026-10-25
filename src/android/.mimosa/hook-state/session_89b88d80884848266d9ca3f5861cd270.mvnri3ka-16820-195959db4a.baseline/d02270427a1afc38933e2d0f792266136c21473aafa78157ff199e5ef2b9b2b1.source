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
class SessionGoalMigrationTest {
    private lateinit var file: File
    private lateinit var raw: SQLiteDatabase
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var supportDb: androidx.sqlite.db.SupportSQLiteDatabase

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "session-goal-migration-test.db")
        file.delete()
        raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.version = 19
        raw.close()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(19) {
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

    @Test fun migration19To20CreatesUsablePersistentGoalsTable() {
        AppDatabase.MIGRATION_19_20.migrate(supportDb)
        supportDb.execSQL(
            "INSERT INTO session_goals (session_id, objective, status, tokens_used, elapsed_ms, blocked_count, created_at, updated_at) " +
                "VALUES ('session','ship feature','active',12,500,0,1,2)",
        )
        supportDb.query("SELECT objective, tokens_used FROM session_goals WHERE session_id='session'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ship feature", cursor.getString(0))
            assertEquals(12, cursor.getLong(1))
        }
    }

    @Test fun migration20To19KeepsGoalData() {
        AppDatabase.MIGRATION_19_20.migrate(supportDb)
        supportDb.execSQL(
            "INSERT INTO session_goals (session_id, objective, status, tokens_used, elapsed_ms, blocked_count, created_at, updated_at) " +
                "VALUES ('session','keep me','active',0,0,0,1,1)",
        )
        AppDatabase.MIGRATION_20_19.migrate(supportDb)
        supportDb.query("SELECT objective FROM session_goals WHERE session_id='session'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("keep me", cursor.getString(0))
        }
    }
}
