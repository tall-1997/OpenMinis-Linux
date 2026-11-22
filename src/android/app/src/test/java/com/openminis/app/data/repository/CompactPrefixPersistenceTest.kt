package com.openminis.app.data.repository

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.agent.InterruptedTailDetector
import com.openminis.app.agent.InterruptedTailShape
import com.openminis.app.data.CompactAssets
import com.openminis.app.data.CompactSliceBoundaries
import com.openminis.app.data.LEGACY_ORPHAN_STOP_NOTE
import com.openminis.app.data.USER_STOP_NOTE
import com.openminis.app.data.isStoredUserStopNote
import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.AppMigrations
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.db.PersistedMessageStatus
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.MediaRef
import com.openminis.app.ui.chat.ChatCompactAssets
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.buildCompactSummaryEnvelope
import com.openminis.app.ui.chat.buildCompactSummaryInput
import com.openminis.app.ui.chat.buildDetachedCompactHistory
import com.openminis.app.ui.chat.buildMediaRefPartJson
import com.openminis.app.ui.chat.decodeCompactMessage
import com.openminis.app.ui.chat.mapStoredLLMMessage
import com.openminis.app.ui.chat.persistUserStop
import com.openminis.app.ui.chat.projectLegacyOrphanedStops
import com.openminis.app.ui.chat.repairToolPairing
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class CompactPrefixPersistenceTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private val sid = "test-session"
    private val dao get() = db.chatDao()
    @get:Rule val tmp = TemporaryFolder()

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = ChatRepository(dao, db.goalDao(), filesDir = tmp.root, db = db)
        dao.insertSession(ChatSessionEntity(id = sid, modelId = "model", createdAt = 1L, updatedAt = 1L))
    }

    @After
    fun tearDown() = db.close()

    private fun text(value: String): String = JSONArray()
        .put(JSONObject().put("type", "text").put("value", value)).toString()

    private suspend fun row(order: Int, role: String = "user", parts: String = text("text")) =
        dao.insertMessage(MessageEntity(id = "m$order", sessionId = sid, role = role, partsJson = parts,
            createdAt = order.toLong(), sortOrder = order))

    private fun toolUse(id: String, path: String): String = JSONArray().put(JSONObject().put("type", "toolUse")
        .put("value", JSONObject().put("toolUseId", id).put("name", "file_read")
            .put("input", JSONObject().put("path", path).toString()))).toString()

    private fun toolResult(id: String): String = JSONArray().put(JSONObject().put("type", "toolResult")
        .put("value", JSONObject().put("toolUseId", id).put("name", "file_read")
            .put("success", true).put("output", "contents"))).toString()

    private fun cancelledValue(id: String, name: String = "browser_use", output: String = ChatViewModel.CANCELLED_MARKER) =
        JSONObject().put("toolUseId", id).put("name", name).put("success", false).put("output", output)
            .put("snapshot", JSONObject().put("type", "text").put("text", output))

    private fun resultParts(vararg values: JSONObject): String = JSONArray().apply {
        values.forEach { put(JSONObject().put("type", "toolResult").put("value", it)) }
    }.toString()

    private suspend fun load(throughId: String? = null, allowTerminatedTail: Boolean = true) =
        repo.loadCompactSource(sid, throughId, allowTerminatedTail) { row, raw ->
            decodeCompactMessage(row.id, row.role, raw)
        }

    private fun CompactSource.closedCount(): Int =
        CompactSliceBoundaries(messages, protectPending = true).atOrBefore(messages.size)

    private suspend fun commit(source: CompactSource, count: Int, summary: String) =
        repo.commitCompactPrefix(source, count, summary, { _, _ -> null }, ChatCompactAssets::merge)

    @Test
    fun firstAndDetachedCompactionsConsumeOldestDurableRowsOnly() = runBlocking {
        repeat(230) { row(it) }
        assertTrue(repo.hasUncoveredCompactPrefix(sid, "m210"))
        val oldest = load()
        assertEquals("m0", oldest.messages.first().dbMessageId)
        assertTrue(oldest.limited)
        assertTrue(oldest.messages.size < 210)

        val first = commit(oldest, 5, "first")
        assertEquals("m4", first.lastCompactedMessageId)
        assertEquals(5, first.compactedCount)
        assertTrue(repo.hasUncoveredCompactPrefix(sid, "m210"))
        val detached = load("m10")
        assertEquals("first", detached.previousSummary)
        assertEquals((5..10).map { "m$it" }, detached.messages.map { it.dbMessageId })
        val second = commit(detached, 6, "second")
        assertEquals("m10", dao.latestCompactMarker(sid)?.lastCompactedMessageId)
        assertEquals(second, dao.latestCompactMarker(sid))
        assertFalse(repo.hasUncoveredCompactPrefix(sid, "m11"))
        assertEquals(230, dao.messageCountForSession(sid))
    }

    @Test
    fun reducedCommitMergesOnlyCoveredSuccessfulPairsAndUndoRestoresPriorAssets() = runBlocking {
        suspend fun paired(at: Int, path: String) {
            val inputs = org.json.JSONObject().put("path", path).toString()
            val call = org.json.JSONObject().put("toolUseId", "call-$at").put("name", "file_read").put("input", inputs)
            val reply = org.json.JSONObject().put("toolUseId", "call-$at").put("name", "file_read")
                .put("success", true).put("output", "contents")
            row(at, "assistant", org.json.JSONArray().put(org.json.JSONObject().put("type", "toolUse").put("value", call)).toString())
            row(at + 1, parts = org.json.JSONArray().put(org.json.JSONObject().put("type", "toolResult").put("value", reply)).toString())
        }
        paired(0, "/root/old")
        paired(2, "/root/covered")
        paired(4, "/root/not-covered")
        val first = commit(load(), 2, "first")
        val second = commit(load(), 2, "second")
        assertEquals("m3", second.lastCompactedMessageId)
        val reloaded = dao.latestCompactMarker(sid)!!
        assertEquals(listOf("/root/old", "/root/covered"), CompactAssets.decode(reloaded.assetsJson).reads)
        val envelope = buildCompactSummaryEnvelope(reloaded.summary, null, reloaded.assetsJson)
        val detached = buildDetachedCompactHistory(envelope, load().messages, tailSize = 20)
        val projection = detached.joinToString("\n") { it.content }
        assertEquals(1, Regex("<context-assets>").findAll(projection).count())
        assertFalse(projection.contains("/root/not-covered"))
        assertEquals(6, dao.messageCountForSession(sid))

        dao.deleteCompactMarker(second.id)
        assertEquals(first, dao.latestCompactMarker(sid))
        assertEquals(listOf("/root/old"), CompactAssets.decode(dao.latestCompactMarker(sid)?.assetsJson).reads)
    }

    @Test
    fun committedSummaryAndNewChunkUseTheSameMaskedText() = runBlocking {
        row(0, parts = text("local test transcript"))
        row(1, parts = text("unsummarized suffix"))
        val dummy = "sk-local-fixture-123456"
        val marker = repo.commitCompactPrefix(
            load(), 1, "Summary mentioned $dummy", { _, summary -> summary }, ChatCompactAssets::merge,
        )
        assertEquals("Summary mentioned [redacted]", marker.summary)
        assertEquals(marker.summary, marker.summaryChunks)
        assertEquals(marker, dao.latestCompactMarker(sid))
        assertEquals(listOf("m1"), load().messages.map { it.dbMessageId })
    }

    @Test
    fun editedRowsPreviousBoundaryAndRacingMarkersEachRejectTheCommit() = runBlocking {
        repeat(4) { row(it * 2) }
        val first = commit(load(), 1, "first")
        val staleRows = load()
        dao.updateMessageParts("m4", """[{"type":"text","value":"changed"}]""")
        assertTrue(runCatching { commit(staleRows, 2, "must fail") }.exceptionOrNull() is CompactSourceChanged)
        assertEquals(first, dao.latestCompactMarker(sid))

        val staleBoundary = load()
        dao.updateMessageParts("m0", """[{"type":"text","value":"changed anchor"}]""")
        assertTrue(runCatching { commit(staleBoundary, 1, "must fail") }.exceptionOrNull() is CompactSourceChanged)
        val racing = load()
        val winner = commit(racing, 1, "winner")
        assertTrue(runCatching { commit(racing, 2, "loser") }.exceptionOrNull() is CompactSourceChanged)
        assertEquals(listOf(first, winner), dao.listCompactMarkers(sid))
    }

    @Test
    fun insertedGapOrMarkerWriteFailureNeverAdvanceCoverage() = runBlocking {
        row(0)
        row(2)
        row(4)
        val first = commit(load(), 1, "first")
        val beforeInsertion = load()
        row(1)
        assertTrue(runCatching { commit(beforeInsertion, 2, "gap") }.exceptionOrNull() is CompactSourceChanged)
        val rejected = runCatching {
            repo.commitCompactPrefix(load(), 1, "failed", { _, _ -> null }, { _, _, _ -> error("asset reducer failed") })
        }.exceptionOrNull()
        assertTrue(rejected is IllegalStateException)
        assertEquals("asset reducer failed", rejected?.message)
        assertEquals(listOf(first), dao.listCompactMarkers(sid))

        // SQLite FAIL does not undo the row inserted before this AFTER trigger.
        // Room must roll back its enclosing transaction to remove the new marker.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_compaction AFTER INSERT ON compact_markers " +
                "BEGIN SELECT RAISE(FAIL, 'simulated write failure'); END",
        )
        val writeFailure = runCatching { commit(load(), 1, "failed") }.exceptionOrNull()
        assertTrue(writeFailure?.message.orEmpty().contains("simulated write failure"))
        assertEquals(listOf(first), dao.listCompactMarkers(sid))
        assertEquals(first, dao.latestCompactMarker(sid))
        assertEquals(4, dao.messageCountForSession(sid))
    }

    @Test
    fun corruptBodyDoesNotPermitJumpingAhead() = runBlocking {
        row(0)
        row(1, parts = """[{"type":"text","value":"${"x".repeat(70_000)}"""")
        row(2)
        val prefix = load()
        assertTrue(prefix.limited)
        assertEquals(listOf("m0"), prefix.messages.map { it.dbMessageId })
        val first = commit(prefix, 1, "first")
        val blocked = load()
        assertTrue(blocked.limited)
        assertTrue(blocked.messages.isEmpty())
        assertEquals(first, dao.latestCompactMarker(sid))
    }

    @Test
    fun maximumOffloadedBodyAndLargeLegacyInlineBodyAreBothCompactable() = runBlocking {
        val limit = ResourceLimits.MAX_DECLARED_UNCOMPRESSED
        val offloaded = repo.appendMessage(sid, "user", text("x".repeat(limit - text("").length)))
        assertEquals(limit.toLong(), offloaded.bodyBytes)
        checkNotNull(offloaded.bodyRef)
        // A character straddles a SQL chunk, and a final marker tests tail retention.
        val long = "a".repeat(ResourceLimits.SQL_CELL_BYTES - text("").indexOf("\"\"}") - 2) +
            "\uD83D\uDE42" + "b".repeat(2_100_000) + " legacy ending"
        val legacy = repo.appendMessage(sid, "user", text("old placeholder"))
        dao.updateMessageParts(legacy.id, text(long)) // Legacy inline storage, not BodyStore.
        val source = load()
        assertEquals(listOf(offloaded.id, legacy.id), source.messages.map { it.dbMessageId })
        assertTrue(source.messages.last().content.endsWith("legacy ending"))
        assertTrue(source.messages.all { it.content.length <= 500 })
        assertEquals(legacy.id, commit(source, 2, "both read").lastCompactedMessageId)
    }

    @Test
    fun maximumOffloadedRowsAreBudgetedTogetherAndChangedBlobCannotCommit() = runBlocking {
        val limit = ResourceLimits.MAX_DECLARED_UNCOMPRESSED
        val raw = text("x".repeat(limit - text("").length))
        val rows = List(5) { repo.appendMessage(sid, "user", raw) }
        val headers = checkNotNull(rows.first().bodyRef)
        val source = load()
        assertTrue(source.limited)
        assertEquals(rows.take(4).map { it.id }, source.messages.map { it.dbMessageId })
        val marker = commit(source, 4, "four bounded rows")
        val tail = load()
        assertEquals(rows.last().id, tail.messages.single().dbMessageId)
        java.io.RandomAccessFile(File(tmp.root, "bodies/$headers"), "rw").use { file ->
            file.seek(file.length() - 1)
            file.write(0) // Size and DB metadata are unchanged; the digest must detect this.
        }
        assertTrue(runCatching { commit(tail, 1, "corrupt tail") }.exceptionOrNull() is CompactSourceChanged)
        assertTrue(load().messages.isEmpty())
        assertEquals(marker, dao.latestCompactMarker(sid))
    }

    @Test
    fun userStopWithoutCommittedToolRoundReopensAndCompactsAsText() = runBlocking {
        row(0, parts = text("Read the test file"))
        val saved = repo.persistUserStop(sid, listOf(AgentContentPart.Text("I started to respond.")))
        val reloadedRows = repo.loadRequestHistory(sid, 10).messages
        val reloaded = reloadedRows.map { mapStoredLLMMessage(it, mediaFile = { name -> File(tmp.root, name) }) }
        assertEquals(listOf("user", "assistant", "user"), reloadedRows.map { it.role })
        assertEquals(saved, reloaded.drop(1))
        assertEquals(PersistedMessageStatus.INTERRUPTED_INFO, reloadedRows[1].errorInfo)
        assertEquals(USER_STOP_NOTE, reloaded.last().content)
        assertTrue(reloaded.last().isHostGenerated)
        assertTrue(isStoredUserStopNote(JSONArray(reloadedRows.last().partsJson)))
        assertEquals(InterruptedTailShape.CONTINUE_REMINDER, InterruptedTailDetector.classify(reloaded.last()))
        assertTrue(sid in repo.interruptedSessionIds())
        assertTrue(reloaded.flatMap { it.contentParts }.all { it is AgentContentPart.Text })
        assertEquals(reloaded, repairToolPairing(reloaded).history)

        val source = load()
        assertEquals(3, source.closedCount())
        assertTrue(buildCompactSummaryInput(source.messages, null).messages.single().content.contains("user stopped"))
        val marker = commit(source, source.closedCount(), "The user stopped this response.")
        assertEquals(saved.last().dbMessageId, marker.lastCompactedMessageId)
        assertNull(marker.assetsJson)
        assertFalse(repo.hasUncoveredCompactPrefix(sid, null))
    }

    @Test
    fun stopLosingOwnershipBeforeTheRoomWriteDoesNotAppendEitherRow() = runBlocking {
        row(0, parts = text("Existing user turn"))
        val originalSession = dao.getSession(sid)
        var ownerChecks = 0
        val saved = repo.persistUserStop(sid, listOf(AgentContentPart.Text("Partial answer from a stale Stop")), ownsWrite = { ++ownerChecks == 1 })
        assertEquals(emptyList<Any>(), saved)
        assertEquals(2, ownerChecks)
        assertEquals(listOf("m0"), repo.loadRequestHistory(sid, 10).messages.map { it.id })
        assertEquals(originalSession, dao.getSession(sid))
        assertFalse(sid in repo.interruptedSessionIds())
    }

    @Test
    fun toolOnlyStopKeepsSettledPairAndDoesNotDuplicateAssistantText() = runBlocking {
        row(0, parts = text("Read the test file"))
        row(1, "assistant", toolUse("already-read", "/root/already-read"))
        row(2, parts = toolResult("already-read"))

        val saved = repo.persistUserStop(sid, emptyList(), capturedAssistantDbIds = listOf("m1", "m2"))
        assertEquals(USER_STOP_NOTE, saved.single().content)
        val reloaded = repo.loadRequestHistory(sid, 10).messages
        assertEquals(4, reloaded.size)
        assertEquals(PersistedMessageStatus.INTERRUPTED_INFO, reloaded[1].errorInfo)
        assertTrue(sid in repo.interruptedSessionIds())
        val source = load()
        assertFalse(CompactSliceBoundaries(source.messages, protectPending = true).isClosed(2))
        assertEquals(4, source.closedCount())
        val marker = commit(source, source.closedCount(), "Read one file, then the user stopped the response.")
        assertEquals(listOf("/root/already-read"), CompactAssets.decode(marker.assetsJson).reads)
    }

    @Test
    fun legacyStopAfterOldMarkerCanBeSummarizedAndSentWithoutToolEvidence() = runBlocking {
        row(0, "assistant", toolUse("call", "/root/no-read-evidence"))
        val legacy = cancelledValue("other", output = "[cancelled by user]").apply { remove("snapshot") }
        val raw = resultParts(cancelledValue("call", "file_read"), legacy)
        row(1, parts = raw)
        // Existing legacy marker predates the new pairing guard and bisects this round.
        dao.insertCompactMarker(CompactMarkerEntity("legacy-stop", sid, "old summary", 1, 1, 11, firstKeptMessageId = "m1"))
        assertTrue(repo.hasUncoveredCompactPrefix(sid, null))
        val source = load(allowTerminatedTail = false)
        assertEquals(listOf("m1"), source.messages.map { it.dbMessageId })
        assertEquals(listOf(AgentContentPart.Text(LEGACY_ORPHAN_STOP_NOTE), AgentContentPart.Text(LEGACY_ORPHAN_STOP_NOTE)),
            source.messages.single().contentParts)
        assertEquals(1, source.closedCount())
        val summaryInput = buildCompactSummaryInput(source.messages, source.previousSummary).messages.single().content
        assertTrue(summaryInput.contains("user cancelled an operation"))
        assertFalse(summaryInput.contains("[result "))
        assertFalse(summaryInput.contains("/root/no-read-evidence"))

        val rows = repo.loadRequestHistory(sid, 10).messages
        val history = rows.map { mapStoredLLMMessage(it, mediaFile = { name -> File(tmp.root, name) }) }
        val full = projectLegacyOrphanedStops(history)
        assertTrue(full.last().contentParts.first() is AgentContentPart.ToolResult)
        // Slice the original full history as the legacy marker does, before pairing repair.
        val wire = repairToolPairing(projectLegacyOrphanedStops(history.drop(1)))
        assertFalse(wire.changed)
        assertEquals("m1", wire.history.single().dbMessageId)
        assertTrue(wire.history.single().isHostGenerated)
        assertEquals(source.messages.single().contentParts, wire.history.single().contentParts)
        val marker = commit(source, 1, "The user cancelled; no tool outcome established.")
        assertEquals("m1", marker.lastCompactedMessageId)
        assertNull(marker.assetsJson)
        assertEquals(raw, dao.loadRawPartsJson("m1"))
        assertFalse(repo.hasUncoveredCompactPrefix(sid, null))
    }

    @Test
    fun legacyStopWithARealPairStillRequiresBothHalvesAndDoesNotConsumeLaterReuse() = runBlocking {
        row(0, "assistant", toolUse("call|fc_one", "/root/cancelled"))
        row(1, parts = resultParts(cancelledValue("call", "file_read"), cancelledValue("second", "file_read")))
        row(2, "assistant", toolUse("second", "/root/reused"))
        val source = load(allowTerminatedTail = false)
        assertEquals(2, source.closedCount())
        assertFalse(CompactSliceBoundaries(source.messages, protectPending = true).isClosed(1))
        assertTrue(source.messages[1].contentParts[0] is AgentContentPart.ToolResult)
        assertEquals(AgentContentPart.Text(LEGACY_ORPHAN_STOP_NOTE), source.messages[1].contentParts[1])
        val prompt = buildCompactSummaryInput(source.messages.take(2), null).messages.single().content
        assertTrue(prompt.contains("user cancelled; outcome unknown"))
        assertTrue(runCatching { commit(source, 1, "one half") }.exceptionOrNull() is IllegalArgumentException)
        assertNull(commit(source, 2, "One cancelled call; a separate stop has no known outcome.").assetsJson)
        assertEquals(0, load(allowTerminatedTail = false).closedCount())

        val wire = projectLegacyOrphanedStops(repo.loadRequestHistory(sid, 10).messages.map {
            mapStoredLLMMessage(it, mediaFile = { name -> File(tmp.root, name) })
        })
        assertTrue(wire[1].contentParts[0] is AgentContentPart.ToolResult)
        assertEquals(AgentContentPart.Text(LEGACY_ORPHAN_STOP_NOTE), wire[1].contentParts[1])
        assertFalse(repairToolPairing(wire).changed)
    }

    @Test
    fun nonStopOrphanResultsStillBlockCoverageAndNeverBecomeExecutionEvidence() = runBlocking {
        row(0, parts = text("Earlier user turn"))
        val variants = listOf(
            toolResult("ordinary"),
            resultParts(cancelledValue("success").put("success", true)),
            resultParts(cancelledValue("lookalike", "file_read", ChatViewModel.CANCELLED_MARKER + " extra output")),
            resultParts(cancelledValue("untyped").put("success", "false")),
            resultParts(cancelledValue("proof").put("receiptProof", "not-a-stop-proof")),
            resultParts(cancelledValue("snapshot").put("snapshot", JSONObject().put("type", "text").put("text", "other"))),
            resultParts(cancelledValue("mixed"), cancelledValue("unclassified", "unknown_tool", "error")),
        )
        for (raw in variants) {
            row(1, parts = raw)
            row(2, parts = text("Later user turn"))
            val source = load()
            assertEquals(1, source.closedCount())
            assertTrue(source.messages[1].contentParts.all { it is AgentContentPart.ToolResult })
            val wire = projectLegacyOrphanedStops(repo.loadRequestHistory(sid, 10).messages.map {
                mapStoredLLMMessage(it, mediaFile = { name -> File(tmp.root, name) })
            })
            assertTrue(repairToolPairing(wire).history.flatMap { it.contentParts }.all { it is AgentContentPart.Text })
            assertFalse(repairToolPairing(wire).history.any { it.content == LEGACY_ORPHAN_STOP_NOTE })
            assertTrue(runCatching { commit(source, source.messages.size, "false coverage") }.exceptionOrNull() is IllegalArgumentException)
            assertNull(dao.latestCompactMarker(sid))
            dao.deleteMessageById("m1")
            dao.deleteMessageById("m2")
        }
    }

    @Test
    fun historicalOrphanAdvancesAtExplicitBoundWithoutCreatingEvidenceOrChangingRows() = runBlocking {
        val original = toolUse("old", "/root/never-confirmed")
        row(0, "assistant", original)
        val synthetic = JSONArray(text("Host text without reminder delimiters"))
            .put(JSONObject().put("type", "receiptOrigin").put("value", "host"))
        row(1, parts = synthetic.toString())
        assertEquals(0, load().closedCount())

        row(2, parts = text("A later genuine question"))
        val source = load(throughId = "m0")
        assertEquals(1, source.closedCount())
        val prompt = buildCompactSummaryInput(source.messages, null).messages.single().content
        assertTrue(prompt.contains("outcome unknown"))
        assertFalse(prompt.contains("never-confirmed"))
        assertTrue(source.messages.single().contentParts.none { it is AgentContentPart.ToolResult })
        assertFalse(prompt.contains("later genuine question"))
        val marker = commit(source, 1, "Outcome unknown")
        assertEquals("m0", marker.lastCompactedMessageId)
        assertEquals(1, marker.compactedCount)
        assertNull(marker.assetsJson)
        assertEquals(original, dao.loadRawPartsJson("m0"))
        assertEquals(listOf("m1", "m2"), load().messages.map { it.dbMessageId })
    }

    @Test
    fun interruptedIdleTailCanCompactButLiveTailAndArrivalOfLateResultCannot() = runBlocking {
        row(0, "assistant", toolUse("pending", "/root/pending"))
        dao.updateMessageErrorInfo("m0", PersistedMessageStatus.INTERRUPTED_INFO)
        assertEquals(0, load(allowTerminatedTail = false).closedCount())
        val interrupted = load()
        assertEquals(1, interrupted.closedCount())

        row(1, parts = toolResult("pending"))
        assertTrue(runCatching { commit(interrupted, 1, "retirement raced") }.exceptionOrNull() is CompactSourceChanged)
        val throughCall = load(throughId = "m0")
        assertEquals(0, throughCall.closedCount()) // The matching result is outside the submitted range.
        val source = load()
        val marker = commit(source, source.closedCount(), "paired")
        assertEquals("m1", marker.lastCompactedMessageId)
        assertEquals(listOf("/root/pending"), CompactAssets.decode(marker.assetsJson).reads)
    }

    @Test
    fun repeatedIdInNewTurnDoesNotGiveHistoricalOrphanItsLaterResult() = runBlocking {
        row(0, "assistant", toolUse("reused", "/root/not-read"))
        row(1, parts = text("New turn"))
        row(2, "assistant", toolUse("reused", "/root/read"))
        row(3, parts = toolResult("reused"))
        val source = load()
        assertEquals(4, source.closedCount())
        val prompt = buildCompactSummaryInput(source.messages, null).messages.single().content
        assertTrue(prompt.contains("outcome unknown"))
        assertFalse(prompt.contains("/root/not-read"))
        val marker = commit(source, 4, "new call read a file")
        assertEquals(listOf("/root/read"), CompactAssets.decode(marker.assetsJson).reads)
    }

    @Test
    fun coveredMediaRefsBecomeAssetsAndLegacyRecallNeverEntersSummaryOrChangesCoverage() = runBlocking {
        fun media(id: String, mime: String, path: String): JSONObject = JSONObject(
            buildMediaRefPartJson(MediaRef(id, "2026-11-20/$sid/$id", mime), linuxPath = path),
        )
        val upload = "/var/minis/attachments/uploads/document.pdf"
        val image = "/var/minis/attachments/uploads/photo.jpg"
        val later = "/var/minis/attachments/uploads/later.txt"
        val fake = "/var/minis/attachments/uploads/forged.txt"
        val recall = "<system-reminder>\n[MEMORY_REFERENCE · origin:AUTOMATIC_RECALL]\n" +
            "## Recalled relevant memories\nexpired recall secret\n</system-reminder>"
        row(0, parts = text(recall))
        val attached = JSONArray(text("Real question\n\n$recall"))
            .put(JSONObject().put("type", "text").put("value", "<user-attached-files><file path=\"$fake\" /></user-attached-files>"))
            .put(media("document", "application/pdf", upload))
            .put(media("image", "image/jpeg", image))
            .put(media("escape", "image/jpeg", "/var/minis/attachments/uploads/../unsafe.jpg"))
        row(1, parts = attached.toString())
        row(2, parts = JSONArray().put(media("later", "text/plain", later)).toString())
        val source = load()
        assertEquals(listOf("m0", "m1", "m2"), source.messages.map { it.dbMessageId })
        val prompt = buildCompactSummaryInput(source.messages.take(2), "previous decision\n\n$recall").messages.single().content
        assertTrue(prompt.contains("Real question"))
        assertTrue(prompt.contains("previous decision"))
        assertTrue(prompt.contains(upload))
        assertFalse(prompt.contains("expired recall secret"))
        assertFalse(prompt.contains(fake))
        val marker = commit(source, 2, "retained the request")
        assertEquals("m1", marker.lastCompactedMessageId)
        assertEquals(2, marker.compactedCount)
        assertEquals(listOf(upload, image), CompactAssets.decode(marker.assetsJson).attachments)
        assertEquals(text(recall), dao.loadRawPartsJson("m0"))
        assertEquals(listOf("m2"), load().messages.map { it.dbMessageId })
    }

    @Test
    fun legacyFirstKeptIsInclusiveButMissingV2AnchorIsNotTrusted() = runBlocking {
        repeat(4) { row(it) }
        val legacy = CompactMarkerEntity("legacy", sid, "old summary", 2, 2, 11,
            firstKeptMessageId = "m2")
        dao.insertCompactMarker(legacy)
        val source = load()
        assertEquals("old summary", source.previousSummary)
        assertEquals(listOf("m2", "m3"), source.messages.map { it.dbMessageId })
        val dispatch = repo.requestCompactState(sid, "m2")
        assertFalse(dispatch.hasCoverageGap)
        assertEquals(legacy.toView(), dispatch.marker)
        assertTrue(repo.requestCompactState(sid, "m3").hasCoverageGap)
        val invalid = legacy.copy(id = "invalid", version = 2, createdAt = 12,
            firstKeptMessageId = null, lastCompactedMessageId = "missing")
        dao.insertCompactMarker(invalid)
        assertTrue(runCatching { load() }.exceptionOrNull() is CompactSourceChanged)
        assertTrue(repo.hasUncoveredCompactPrefix(sid, "m2"))
        assertEquals(invalid, dao.latestCompactMarker(sid))
        assertEquals(legacy, repo.latestUsableCompactMarker(sid))
    }

    @Test
    fun directCommitCannotAnchorOnAUseWhoseResultWasNotSummarized() = runBlocking {
        row(0, "assistant", """[{"type":"toolUse","value":{"toolUseId":"call","name":"file_read","input":"{}"}}]""")
        row(1, parts = """[{"type":"toolResult","value":{"toolUseId":"call","name":"file_read","success":true}}]""")
        assertTrue(runCatching { commit(load(), 1, "half pair") }.exceptionOrNull() is IllegalArgumentException)
        assertNull(dao.latestCompactMarker(sid))
        val marker = commit(load(), 2, "complete pair")
        assertEquals(marker, repo.latestUsableCompactMarker(sid))
        dao.deleteMessageById("m1")
        assertNull(repo.latestUsableCompactMarker(sid))
    }

    @Test
    fun realRoomOpenMigratesVersion34AndKeepsPriorMarkerOnUndo() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "compact-34-to-35.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val schemaPath = "schemas/com.openminis.app.data.db.AppDatabase/34.json"
        val schemaFile = listOf(File(schemaPath), File("src/android/app", schemaPath)).first { it.isFile }
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) {
                        old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO sessions (id, model_id, created_at, updated_at, memory_enabled, edit_count) " +
                    "VALUES ('old-session', 'model', 1, 1, 1, 0)")
                old.execSQL("INSERT INTO compact_markers (id, session_id, summary, first_kept_sort_order, " +
                    "compacted_count, created_at, version, last_compacted_message_id) " +
                    "VALUES ('old-marker', 'old-session', 'old summary', 2147483647, 2, 1, 2, 'm2')")
                old.version = 34
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*AppMigrations.ALL).build()
            try {
                val markers = migrated.chatDao()
                val old = checkNotNull(markers.latestCompactMarker("old-session"))
                assertEquals("old summary", old.summary)
                assertEquals("m2", old.lastCompactedMessageId)
                assertNull(old.assetsJson)
                val next = old.copy(id = "new-marker", createdAt = 2,
                    assetsJson = CompactAssets(reads = listOf("/root/a.txt")).encode())
                markers.insertCompactMarker(next)
                assertEquals(next, markers.latestCompactMarker("old-session"))
                markers.deleteCompactMarker(next.id)
                assertEquals(old, markers.latestCompactMarker("old-session"))
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }
}
