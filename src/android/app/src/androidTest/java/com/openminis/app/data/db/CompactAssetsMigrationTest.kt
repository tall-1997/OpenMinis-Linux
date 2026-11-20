package com.openminis.app.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CompactAssetsMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule val helper = MigrationTestHelper(instrumentation, AppDatabase::class.java)

    @Test
    fun upgradeFrom34KeepsExistingMarkersAndStoresNewAssets() = runBlocking {
        val name = "compact-assets-${UUID.randomUUID()}.db"
        val context = instrumentation.targetContext
        try {
            helper.createDatabase(name, 34).use { db ->
                db.execSQL("""
                    INSERT INTO sessions (id, model_id, created_at, updated_at, memory_enabled, edit_count)
                    VALUES ('s', 'model', 1, 1, 1, 0)
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO compact_markers (id, session_id, summary, first_kept_sort_order,
                        compacted_count, created_at, version, last_compacted_message_id, summary_chunks)
                    VALUES ('old', 's', 'old summary', 2147483647, 2, 1, 2, 'm2', '[{"t":"chunk","a":1}]')
                """.trimIndent())
            }
            helper.runMigrationsAndValidate(name, 35, true, CompactAssetMigrations.MIGRATION_34_35).use { db ->
                db.query("SELECT summary, assets_json FROM compact_markers WHERE id = 'old'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("old summary", cursor.getString(0))
                    assertTrue(cursor.isNull(1))
                }
            }
            val room = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppMigrations.ALL).build()
            try {
                val dao = room.chatDao()
                val old = checkNotNull(dao.latestCompactMarker("s"))
                assertEquals("m2", old.lastCompactedMessageId)
                assertEquals("""[{"t":"chunk","a":1}]""", old.summaryChunks)
                assertNull(old.assetsJson)
                val next = old.copy(id = "new", createdAt = 2, assetsJson = """{"reads":["/root/a.txt"]}""")
                dao.insertCompactMarker(next)
                assertEquals(next, dao.latestCompactMarker("s"))
                dao.deleteCompactMarker(next.id)
                assertEquals(old, dao.latestCompactMarker("s"))
            } finally {
                room.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }
}
