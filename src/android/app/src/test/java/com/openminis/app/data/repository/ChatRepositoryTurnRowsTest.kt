package com.openminis.app.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-atomic-tool-turn] The assistant(tool_use) row and the user(tool_result)
 * row are one logical turn: compact pairing and receipt reconciliation can
 * only walk through a CLOSED round. appendTurnRows must commit both rows in
 * one transaction — a crash after the first insert must leave neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatRepositoryTurnRowsTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private val sid = "turn-pair-session"
    private val dao get() = db.chatDao()

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = ChatRepository(dao, db.goalDao(), db = db)
        dao.insertSession(ChatSessionEntity(id = sid, modelId = "model", createdAt = 1L, updatedAt = 1L))
    }

    @After
    fun tearDown() = db.close()

    private fun assistantRow() = PendingTurnRow(
        role = "assistant",
        partsJson = """[{"type":"toolUse","value":{"id":"call_1","name":"shell","input":"{}"}}]""",
        tokenUsage = """{"inputTokens":10}""",
    )

    private fun resultRow() = PendingTurnRow(
        role = "user",
        partsJson = """[{"type":"toolResult","value":{"toolUseId":"call_1","name":"shell","output":"ok","success":true}}]""",
    )

    @Test
    fun toolTurnRowsCommitWithOneTimestampAndConsecutiveOrder() = runBlocking {
        val rows = repo.appendTurnRows(sid, listOf(assistantRow(), resultRow()))
        assertEquals(2, rows.size)
        assertEquals("assistant", rows[0].role)
        assertEquals("user", rows[1].role)
        assertEquals(rows[0].createdAt, rows[1].createdAt)
        assertEquals(rows[0].sortOrder + 1, rows[1].sortOrder)
        val tail = dao.loadMessagesTailRaw(sid, 10, 0)
        assertEquals(listOf("assistant", "user"), tail.sortedBy { it.sortOrder }.map { it.role })
        assertTrue(dao.getSession(sid)!!.updatedAt >= rows[0].createdAt)
    }

    @Test
    fun failingResultInsertRollsBackTheAssistantRow() = runBlocking {
        // SQLite FAIL alone does not undo the row inserted before the trigger
        // fires — only the enclosing Room transaction does. Simulating a crash
        // between the two inserts proves neither row survives.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_result_row AFTER INSERT ON messages " +
                "WHEN NEW.role = 'user' AND NEW.parts_json LIKE '%toolResult%' " +
                "BEGIN SELECT RAISE(FAIL, 'simulated crash after assistant row'); END",
        )
        val failure = runCatching { repo.appendTurnRows(sid, listOf(assistantRow(), resultRow())) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("simulated crash"))
        assertEquals(0, dao.messageCountForSession(sid))
    }

    @Test
    fun emptyRequestPersistsNothing() = runBlocking {
        assertEquals(emptyList<Any>(), repo.appendTurnRows(sid, emptyList()))
        assertEquals(0, dao.messageCountForSession(sid))
    }
}
