package com.openminis.app.scheduled

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-scheduled-new-session-permission / T-scheduled-new-session-folder]
 * Drives [ScheduledAgentRunner.persistNewSessionRow] — the exact persistence
 * path a NewSession fire takes — against a REAL in-memory Room database, so
 * the assertions cover what actually landed on the session row:
 *
 *  - permission mode: "YOYO" task → row YOYO; null/ASK task → row ASK
 *    (ChatViewModel restores the gate from this column, so a widened token
 *    here would silently grant full-auto to unattended runs),
 *  - folder: task folder that exists → filed (folder_id written); folder
 *    dissolved after save → left unfiled (folder_id null), not a ghost id,
 *  - source tag + model binding behave as before.
 */
@RunWith(RobolectricTestRunner::class)
// Plain android.app.Application, NOT MinisApp — same rationale as
// MessageSearchIntegrationTest (no sandbox wiring under Robolectric).
@Config(sdk = [35], application = android.app.Application::class)
class ScheduledNewSessionPersistenceTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ChatRepository(
            dao = db.chatDao(),
            goalDao = db.goalDao(),
            filesDir = null,
            db = db,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun task(
        permission: String? = null,
        folderId: String? = null,
        binding: String? = null,
    ) = ScheduledTask(
        id = "task-1",
        label = "Nightly",
        timeOfDayHour = 3,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.DAILY,
        prompt = "p",
        modelBinding = binding,
        newSessionPermissionMode = permission,
        newSessionFolderId = folderId,
    )

    private fun insertFolder(id: String) = runBlocking {
        val now = System.currentTimeMillis()
        db.chatDao().insertFolder(
            FolderEntity(id = id, name = "WS $id", createdAt = now, updatedAt = now),
        )
    }

    @Test
    fun yoyoTaskWritesYoyoOntoSessionRow() = runBlocking {
        val session = ScheduledAgentRunner.persistNewSessionRow(
            repo, context, task(permission = "YOYO"), seedModelId = "m1", bindingToWrite = null,
        )
        assertEquals("YOYO", session.permissionMode)
        val reloaded = repo.getSession(session.id)!!
        assertEquals("YOYO", reloaded.permissionMode)
        assertNull(reloaded.folderId)
        assertEquals("scheduled", reloaded.source)
    }

    @Test
    fun defaultTaskWritesAskOntoSessionRow() = runBlocking {
        val session = ScheduledAgentRunner.persistNewSessionRow(
            repo, context, task(permission = null), seedModelId = "m1", bindingToWrite = null,
        )
        assertEquals("ASK", session.permissionMode)
    }

    @Test
    fun askTaskWritesAskOntoSessionRow() = runBlocking {
        val session = ScheduledAgentRunner.persistNewSessionRow(
            repo, context, task(permission = "ASK"), seedModelId = "m1", bindingToWrite = null,
        )
        assertEquals("ASK", session.permissionMode)
    }

    @Test
    fun existingFolderIsFiledViaRepositoryPath() = runBlocking {
        insertFolder("ws-1")
        val session = ScheduledAgentRunner.persistNewSessionRow(
            repo, context, task(folderId = "ws-1"), seedModelId = "m1", bindingToWrite = null,
        )
        val reloaded = repo.getSession(session.id)!!
        assertEquals("ws-1", reloaded.folderId)
        // Filing goes through setFolderForSessions, so the SessionWorkspace
        // owner map is primed the same way the chat screen primes it.
        assertEquals("ws-1", com.openminis.app.sandbox.SessionWorkspace.folderIdFor(session.id))
    }

    @Test
    fun dissolvedFolderDegradesToUnfiled() = runBlocking {
        // No insertFolder — the group was deleted after the task was saved.
        val session = ScheduledAgentRunner.persistNewSessionRow(
            repo, context, task(folderId = "ghost"), seedModelId = "m1", bindingToWrite = null,
        )
        val reloaded = repo.getSession(session.id)!!
        assertNull(reloaded.folderId)
    }

    @Test
    fun bindingStillWrittenThrough() = runBlocking {
        val binding = """{"type":"group","groupId":"g-1"}"""
        val session = ScheduledAgentRunner.persistNewSessionRow(
            repo, context, task(binding = binding), seedModelId = "m1", bindingToWrite = binding,
        )
        val reloaded = repo.getSession(session.id)!!
        assertEquals(binding, reloaded.modelBinding)
    }

    @Test
    fun folderAndPermissionCombineOnOneRow() = runBlocking {
        insertFolder("ws-2")
        val session = ScheduledAgentRunner.persistNewSessionRow(
            repo, context,
            task(permission = "YOYO", folderId = "ws-2"),
            seedModelId = "m1", bindingToWrite = null,
        )
        val reloaded: ChatSessionEntity = repo.getSession(session.id)!!
        assertEquals("YOYO", reloaded.permissionMode)
        assertEquals("ws-2", reloaded.folderId)
    }
}
