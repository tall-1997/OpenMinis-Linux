package com.openminis.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-m9-supervisor-experience] ProjectExperienceDao against a REAL in-memory
 * Room database: upsert folding, session scoping, fingerprint updates and
 * deletes all hit real SQLite so primary-key and index behavior is exercised
 * rather than assumed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProjectExperienceDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(sessionId: String, toolName: String, used: Int = 1, success: Int = 1) =
        ProjectExperienceEntity(
            id = ProjectExperienceCore.idFor(sessionId, toolName),
            sessionId = sessionId,
            toolName = toolName,
            usedCount = used,
            successCount = success,
            failureCount = used - success,
            firstUsedAtMs = 1_000L,
            lastUsedAtMs = 2_000L,
            fingerprint = "",
        )

    @Test
    fun upsert_foldsIntoExistingRow() = runBlocking {
        db.projectExperienceDao().upsert(row("s", "file_write", used = 1, success = 1))
        db.projectExperienceDao().upsert(row("s", "file_write", used = 2, success = 1))
        val all = db.projectExperienceDao().selectBySession("s")
        assertEquals(1, all.size)
        assertEquals(2, all[0].usedCount)
        assertEquals(1, all[0].successCount)
        assertEquals(1, all[0].failureCount)
    }

    @Test
    fun selectBySession_scopesToSessionOnly() = runBlocking {
        val dao = db.projectExperienceDao()
        dao.upsert(row("s1", "file_write"))
        dao.upsert(row("s2", "shell_execute"))
        assertEquals(1, dao.selectBySession("s1").size)
        assertEquals(1, dao.selectBySession("s2").size)
        assertEquals(0, dao.selectBySession("s3").size)
    }

    @Test
    fun selectBySession_newestFirst() = runBlocking {
        val dao = db.projectExperienceDao()
        val old = row("s", "file_write").copy(lastUsedAtMs = 1_000L)
        val fresh = row("s", "file_read").copy(lastUsedAtMs = 9_000L)
        dao.upsert(old)
        dao.upsert(fresh)
        val all = dao.selectBySession("s")
        assertEquals(listOf("file_read", "file_write"), all.map { it.toolName })
    }

    @Test
    fun updateFingerprint_rotatesStoredValue() = runBlocking {
        val dao = db.projectExperienceDao()
        val base = row("s", "shell_execute")
        dao.upsert(base)
        dao.updateFingerprint(base.id, "7|9000")
        val all = dao.selectBySession("s")
        assertEquals("7|9000", all.single().fingerprint)
    }

    @Test
    fun deleteBySession_removesOnlyThatSession() = runBlocking {
        val dao = db.projectExperienceDao()
        dao.upsert(row("s1", "file_write"))
        dao.upsert(row("s1", "file_read"))
        dao.upsert(row("s2", "file_write"))
        dao.deleteBySession("s1")
        assertEquals(0, dao.selectBySession("s1").size)
        assertEquals(1, dao.selectBySession("s2").size)
    }

    @Test
    fun selectBySession_emptySession_returnsEmpty() = runBlocking {
        assertEquals(0, db.projectExperienceDao().selectBySession("ghost").size)
    }

    @Test
    fun upsert_preservesOriginalIdKey() = runBlocking {
        val dao = db.projectExperienceDao()
        dao.upsert(row("s", "file_write"))
        val fetched = dao.selectBySession("s").single()
        assertEquals(ProjectExperienceCore.idFor("s", "file_write"), fetched.id)
    }
}
