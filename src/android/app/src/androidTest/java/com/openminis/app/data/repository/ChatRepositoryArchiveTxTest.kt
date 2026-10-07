package com.openminis.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * [T-archive-truncate-txn] Behaviour tests for the archive-then-truncate path.
 *
 * The old deleteMessagesAfter logged "archiving failed (delete proceeds)" and
 * dropped the doomed rows anyway — the archive was decorative exactly when it
 * mattered. These tests pin the two fail-closed properties:
 *
 *  1. archive + truncate happen (Room transaction, db handle passed in), and
 *  2. an archiving failure ABORTS the truncation — a row too large for the
 *     CursorWindow must survive, not vanish unarchived.
 *
 * Plus [T-archive-trimmed-parts]: updateMessageParts (the rerun-from-tool-block
 * cut) archives the pre-cut parts before rewriting the row in place.
 */
@RunWith(AndroidJUnit4::class)
class ChatRepositoryArchiveTxTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.databaseBuilder(context, AppDatabase::class.java, "archive-tx-${UUID.randomUUID()}.db").build()
        repo = ChatRepository(
            db.chatDao(),
            db.goalDao(),
            context.filesDir,
            db.messageVersionDao(),
            db,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedSession(sessionId: String) {
        db.chatDao().insertSession(
            ChatSessionEntity(id = sessionId, modelId = "test", createdAt = 1, updatedAt = 1),
        )
    }

    private suspend fun seedMessage(sessionId: String, id: String, sortOrder: Int, parts: String) {
        db.chatDao().appendMessage(
            MessageEntity(
                id = id,
                sessionId = sessionId,
                role = "assistant",
                partsJson = parts,
                createdAt = sortOrder.toLong(),
                sortOrder = sortOrder,
            ),
        )
    }

    @Test
    fun successfulArchiveAndTruncateAreTransactional() = runBlocking {
        seedSession("s1")
        seedMessage("s1", "m0", 0, """[{"type":"text","value":"kept"}]""")
        seedMessage("s1", "m1", 1, """[{"type":"text","value":"doomed-1"}]""")
        seedMessage("s1", "m2", 2, """[{"type":"text","value":"doomed-2"}]""")

        repo.deleteMessagesAfter("s1", 1)

        assertEquals("only the pre-cutoff row survives", 1, db.chatDao().countMessagesBeforeSort("s1", 9999))
        assertEquals("both doomed rows are archived", 2, db.messageVersionDao().countForSession("s1"))
        val v1 = db.messageVersionDao().versionsFor("m1").single()
        assertEquals(1, v1.versionIndex)
        assertEquals("""[{"type":"text","value":"doomed-1"}]""", v1.partsJson)
        assertEquals("m1", v1.messageId)
        assertEquals("s1", v1.sessionId)
        val v2 = db.messageVersionDao().versionsFor("m2").single()
        assertEquals(1, v2.versionIndex)
    }

    @Test
    fun oversizedRowAbortsTruncationInsteadOfLosingIt() = runBlocking {
        // A parts_json larger than the 2MB CursorWindow makes the full-column
        // rowsFrom read throw — the safe outcome is a KEPT message, not an
        // unarchived delete.
        seedSession("s2")
        seedMessage("s2", "m0", 0, """[{"type":"text","value":"kept"}]""")
        seedMessage("s2", "m1", 1, """[{"type":"text","value":"big"}]""" + ",\"" + "x".repeat(2_500_000) + "\"")
        seedMessage("s2", "m2", 2, """[{"type":"text","value":"small-doomed"}]""")

        repo.deleteMessagesAfter("s2", 1)

        assertEquals("truncation must be aborted, all rows survive", 3, db.chatDao().countMessagesBeforeSort("s2", 9999))
        assertEquals("nothing may be archived when the read failed", 0, db.messageVersionDao().countForSession("s2"))
    }

    @Test
    fun updateMessagePartsArchivesTheTrimmedRowBeforeRewriting() = runBlocking {
        seedSession("s3")
        val original = """[{"type":"text","value":"before-cut"},{"type":"tool_use"}]"""
        seedMessage("s3", "m0", 0, original)

        repo.updateMessageParts("m0", """[{"type":"text","value":"before-cut"}]""")

        val archived = db.messageVersionDao().versionsFor("m0").single()
        assertEquals("the archive stores the pre-cut parts", original, archived.partsJson)
        assertEquals(1, archived.versionIndex)
        assertEquals("assistant", archived.role)
        val live = db.chatDao().messageById("m0")!!
        assertEquals(
            "the live row now holds the trimmed parts",
            """[{"type":"text","value":"before-cut"}]""",
            live.partsJson,
        )
    }
}
