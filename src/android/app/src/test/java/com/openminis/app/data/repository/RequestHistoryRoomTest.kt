package com.openminis.app.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.body.BodyStore
import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.MessageEntity
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RequestHistoryRoomTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private val dao get() = db.chatDao()
    private val sid = "request-history"

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = ChatRepository(dao, db.goalDao(), filesDir = tmp.root, db = db)
        dao.insertSession(ChatSessionEntity(id = sid, modelId = "model", createdAt = 1, updatedAt = 1))
    }

    @After
    fun tearDown() = db.close()

    private fun text(value: String): String = """[{"type":"text","value":${JSONObject.quote(value)}}]"""

    private fun tool(type: String, id: String, name: String = "file_read"): String = JSONArray()
        .put(JSONObject().put("type", type).put("value", JSONObject().put("toolUseId", id).put("name", name)
            .put("input", "{}").put("output", "result").put("success", true))).toString()

    private suspend fun row(
        sort: Int,
        raw: String,
        role: String = "user",
        reasoning: String? = null,
        ref: String? = null,
        bodyBytes: Long = 0L,
        sha: String? = ref,
    ) = dao.insertMessage(MessageEntity(
        id = "m$sort", sessionId = sid, role = role, partsJson = raw, createdAt = -sort.toLong(), sortOrder = sort,
        reasoningContent = reasoning, bodyRef = ref, bodyBytes = bodyBytes, bodySha = sha,
    ))

    @Test
    fun budgetAdmitsOnlyNewestBodiesAndRoomGateSeesTheUncoveredPrefix() = runBlocking {
        val size = ResourceLimits.MAX_DECLARED_UNCOMPRESSED
        val raw = text("x".repeat(size - text("").length))
        val store = BodyStore(File(tmp.root, "bodies"))
        val ref = checkNotNull(store.put(raw.toByteArray()).ref)
        val stub = text("body stored ($size bytes)")
        row(1, text("a smaller older body must not fill the gap"))
        row(5, stub, ref = "0".repeat(64), bodyBytes = size.toLong()) // A read would fail, but is outside the budget.
        row(40, stub, ref = ref, bodyBytes = size.toLong())
        row(70, stub, ref = ref, bodyBytes = size.toLong())

        val tail = repo.loadRequestHistory(sid, 4_000)
        assertEquals(4, tail.totalMessages)
        assertEquals(2, tail.firstMessageOffset)
        assertEquals(listOf("m40", "m70"), tail.messages.map { it.id })
        assertTrue(tail.messages.all { it.partsJson == raw })
        val firstResidentId = tail.messages.first().id
        assertTrue(repo.requestCompactState(sid, firstResidentId).hasCoverageGap)
        dao.insertCompactMarker(CompactMarkerEntity("summary", sid, "covered rows", 40, 2, 99,
            lastCompactedMessageId = "m5", version = 2))
        val covered = repo.requestCompactState(sid, firstResidentId)
        assertEquals("summary", covered.marker?.id)
        assertFalse(covered.hasCoverageGap)
    }

    @Test
    fun legacyInlineBodyAndReasoningAreReadExactlyAcrossSqlByteChunks() = runBlocking {
        val prefixSize = text("").indexOf("\"\"}") + 1
        val prefix = "a".repeat(ResourceLimits.SQL_CELL_BYTES - prefixSize - 1)
        val raw = text(prefix + "\uD83D\uDE42" + "b".repeat(2_100_000) + " body end")
        val reasoning = "r".repeat(2_100_000) + "\uD83D\uDE42 reasoning end"
        // Simulate schema-legacy metadata that reports an obsolete body size.
        row(3, raw, role = "assistant", reasoning = reasoning, bodyBytes = 1)
        val actual = repo.loadRequestHistory(sid, 1).messages.single()
        assertEquals("m3", actual.id)
        assertTrue(raw == actual.partsJson)
        assertTrue(reasoning == actual.reasoningContent)
    }

    @Test
    fun keysetPagesPreserveChronologicalRowsAndPhysicalOffsetDespiteSortGaps() = runBlocking {
        repeat(70) { row(it * 7, text("row $it")) }
        val tail = repo.loadRequestHistory(sid, 39)
        assertEquals(70, tail.totalMessages)
        assertEquals(31, tail.firstMessageOffset)
        assertEquals((31 until 70).map { "m${it * 7}" }, tail.messages.map { it.id })
        val empty = repo.loadRequestHistory(sid, 0)
        assertTrue(empty.messages.isEmpty())
        assertEquals(70, empty.firstMessageOffset)
    }

    @Test
    fun missingCorruptAndMismatchedBodiesFailInsteadOfReturningStubOrSkipping() = runBlocking {
        val body = text("x".repeat(5_000)).toByteArray()
        val dir = File(tmp.root, "bodies")
        val ref = checkNotNull(BodyStore(dir).put(body).ref)
        val stub = text("body stored (${body.size} bytes)")
        row(2, text("later"))
        suspend fun fails(raw: String, bodyRef: String?, bytes: Long = body.size.toLong()) {
            row(1, raw, ref = bodyRef, bodyBytes = bytes)
            val error = runCatching { repo.loadRequestHistory(sid, 2) }.exceptionOrNull()
            assertTrue("Expected a request history failure, got $error", error is RequestHistoryUnavailable)
            assertTrue(error?.message.orEmpty().contains("m1"))
            dao.deleteMessageById("m1")
        }
        fails(stub, "0".repeat(64))
        fails(stub, ref, body.size + 1L)
        RandomAccessFile(File(dir, ref), "rw").use { file ->
            file.seek(file.length() - 1)
            file.write(0)
        }
        fails(stub, ref)
        fails(text("body omitted; store failed"), null)
        fails("""[{"type":"text","value":"truncated"}""", null)
    }

    @Test
    fun droppingOneCallAlsoDropsTheOtherPairThatCrossesItsResult() = runBlocking {
        row(0, tool("toolUse", "a|fc_one"), role = "assistant")
        row(3, tool("toolUse", "b|fc_two"), role = "assistant")
        row(90, tool("toolResult", "a"))
        row(100, tool("toolResult", "b"))
        row(101, text("a later question"))
        val full = repo.loadRequestHistory(sid, 5)
        assertEquals(listOf("m0", "m3", "m90", "m100", "m101"), full.messages.map { it.id })
        val tail = repo.loadRequestHistory(sid, 4)
        assertEquals(listOf("m101"), tail.messages.map { it.id })
        assertEquals(4, tail.firstMessageOffset)
        assertTrue(repo.requestCompactState(sid, tail.messages.first().id).hasCoverageGap)
    }

    @Test
    fun canonicalPairingCannotMoveTheBoundaryBackBehindAWireOrphan() = runBlocking {
        row(0, text("older user turn"))
        row(1, tool("toolResult", "missing"))
        row(2, tool("toolUse", "alias", "shell_exec"), role = "assistant")
        row(3, tool("toolResult", "alias", "shell_execute"))
        row(4, text("new question"))

        val tail = repo.loadRequestHistory(sid, 4)
        assertEquals(listOf("m4"), tail.messages.map { it.id })
        assertEquals(4, tail.firstMessageOffset)
        assertTrue(repo.requestCompactState(sid, "m4").hasCoverageGap)
    }

    @Test
    fun newestToolRoundDoesNotReturnAnOrphanIfItCannotFit() = runBlocking {
        row(0, tool("toolUse", "a|fc_one"), role = "assistant")
        row(1, tool("toolResult", "a"))
        assertEquals(2, repo.loadRequestHistory(sid, 2).messages.size)
        val failure = runCatching { repo.loadRequestHistory(sid, 1) }.exceptionOrNull()
        assertTrue(failure is RequestHistoryUnavailable)
    }

    @Test
    fun aUiTailShorterThanTheRequestHistoryMustNotInventACoverageGap() = runBlocking {
        repeat(600) { row(it, text("row $it")) }

        val uiTail = repo.loadRequestHistory(sid, 500)
        assertEquals("m100", uiTail.messages.first().id)
        assertTrue(
            "A500-row UI tail leaves older persisted rows before its first resident id",
            repo.requestCompactState(sid, uiTail.messages.first().id).hasCoverageGap,
        )

        val requestHistory = repo.loadRequestHistory(sid, 4_000)
        assertEquals("m0", requestHistory.messages.first().id)
        assertFalse(
            "The request history already admits the complete session; no compact gap exists",
            repo.requestCompactState(sid, requestHistory.messages.first().id).hasCoverageGap,
        )
    }
    @Test
    fun fullMessageReadHydratesOffloadedBodyInsteadOfStub() = runBlocking {
        val body = text("full original " + "x".repeat(5_000) + " TAIL")
        val bytes = body.toByteArray()
        val store = BodyStore(File(tmp.root, "bodies"))
        val ref = checkNotNull(store.put(bytes).ref)
        row(1, text("body stored (${bytes.size} bytes)"), ref = ref, bodyBytes = bytes.size.toLong(), sha = ref)

        val item = checkNotNull(repo.readFullMessageText(sid, "m1"))
        assertTrue(item.text.contains("TAIL"))
        val page = repo.loadMessagePageFull(sid,0,1,100).single()
        assertTrue(page.text.startsWith("full original"))
        assertTrue(page.truncated)
    }

    @Test
    fun requestHistoryHonoursTokenBudgetAndKeepsNewestRows() = runBlocking {
        repeat(20) { row(it, text("x".repeat(400))) }
        val tail = repo.loadRequestHistory(sid, Int.MAX_VALUE, maxTokens =500)
        assertTrue(tail.messages.isNotEmpty())
        assertTrue(tail.messages.size <20)
        assertEquals("m19", tail.messages.last().id)
    }

}
