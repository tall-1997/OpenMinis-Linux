package com.openminis.app.tools

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.agent.ToolResultProjection
import com.openminis.app.data.body.BodyStore
import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactInlineProbe
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.GoalDao
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.chat.buildToolResultPartsJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * [T-history-retrieval] The offset contract a caller relies on: reading at
 * `excerpt_start` must return text that BEGINS with the excerpt, and paging
 * must never split a code point.
 */
class HistorySearchToolTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeHistory(files: java.io.File) {
        val messages = mutableListOf<MessageEntity>()
        var marker: CompactMarkerEntity? = null
        var queryFailure: Exception? = null
        private val dao = fake<ChatDao> { method, args ->
            when (method) {
                "latestCompactMarker" -> marker
                "compactOrderForId" -> find(args[0], args[1])?.sortOrder
                "compactInlineProbe" -> find(args[0], args[1])?.let {
                    val bytes = it.partsJson.toByteArray()
                    CompactInlineProbe(bytes.size.toLong(), bytes.takeIf { it.size <= ResourceLimits.INLINE_BODY_BYTES })
                }
                "compactInlineBodyChunk" -> find(args[0], args[1])?.let {
                    val offset = (args[2] as Int) - 1
                    it.partsJson.toByteArray().copyOfRange(offset, offset + args[3] as Int)
                }
                "runMessagesQuery" -> readQuery(args[0] as SupportSQLiteQuery)
                else -> error("Unexpected DAO method: $method")
            }
        }
        val repo = ChatRepository(dao, fake<GoalDao> { _, _ -> error("No goal calls expected") }, filesDir = files)

        private fun find(session: Any?, id: Any?) = messages.firstOrNull { it.sessionId == session && it.id == id }

        private fun readQuery(query: SupportSQLiteQuery): List<MessageEntity> {
            queryFailure?.let { throw it }
            assertTrue(query.sql.contains("WHERE session_id = ? AND sort_order < ?"))
            val bind = mutableMapOf<Int, Any?>()
            query.bindTo(object : SupportSQLiteProgram {
                override fun bindString(index: Int, value: String) { bind[index] = value }
                override fun bindLong(index: Int, value: Long) { bind[index] = value }
                override fun bindDouble(index: Int, value: Double) = error("Unexpected double")
                override fun bindNull(index: Int) = error("Unexpected null")
                override fun bindBlob(index: Int, value: ByteArray) = error("Unexpected blob")
                override fun clearBindings() = bind.clear()
                override fun close() = Unit
            })
            val candidates = messages.filter { it.sessionId == bind[1] && it.sortOrder.toLong() < bind[2] as Long }
            val selected = if (query.sql.contains("AND id = ?")) {
                candidates.filter { it.id == bind[3] }
            } else {
                candidates.filter {
                    when {
                        query.sql.contains("AND role = 'assistant'") -> it.role == "assistant"
                        query.sql.contains("AND role = 'user'") -> it.role == "user"
                        query.sql.contains("AND role IN ('user', 'tool')") -> it.role in setOf("user", "tool")
                        else -> it.role in setOf("user", "assistant", "tool")
                    }
                }.sortedByDescending { it.sortOrder }.take((bind[3] as Long).toInt())
            }
            return selected.map { it.copy(partsJson = "", preview = null, reasoningContent = null) }
        }

        fun add(id: String, order: Int, body: String, role: String = "user", session: String = "own") {
            messages += MessageEntity(id, session, role, body, createdAt = 7, sortOrder = order)
        }
    }

    private fun text(body: String) = JSONArray().put(JSONObject().put("type", "text").put("value", body)).toString()
    private fun marker(version: Int, last: String? = null, first: String? = null, firstOrder: Int = Int.MAX_VALUE) =
        CompactMarkerEntity("marker", "own", "summary", firstOrder, 1, 7, firstKeptMessageId = first,
            lastCompactedMessageId = last, version = version)

    private suspend fun FakeHistory.search(query: String, role: String? = null): JSONObject {
        val args = JSONObject().put("query", query).put("role", role)
        val result = HistorySearchTool.executeSearch(args.toString(), "own", repo)
        assertTrue(result.output, result.success)
        return JSONObject(result.output)
    }

    private suspend fun FakeHistory.read(id: String, offset: Int = 0): ToolExecutionResult =
        HistorySearchTool.executeRead(JSONObject().put("message_id", id).put("offset", offset).toString(), "own", repo)

    private fun JSONObject.ids(): List<String> = getJSONArray("hits").let { hits ->
        List(hits.length()) { hits.getJSONObject(it).getString("message_id") }
    }

    @Test
    fun latinAndCjkQueriesBecomeSearchTerms() {
        assertEquals(listOf("cargo", "build"), HistorySearchTool.keywords("Cargo build"))
        // A CJK run is bigrammed: one character alone matches almost every row.
        val cjk = HistorySearchTool.keywords("\u6570\u636e\u5e93\u8fc1\u79fb")
        assertTrue(cjk.contains("\u6570\u636e"))
        assertTrue(cjk.contains("\u636e\u5e93"))
        assertTrue(HistorySearchTool.keywords("   ").isEmpty())
    }

    @Test
    fun excerptOffsetsAreCoordinatesIntoTheSource() {
        val text = "alpha beta gamma " + "x".repeat(400) + " NEEDLE " + "y".repeat(400) + " omega"
        val (excerpt, start, end) = HistorySearchTool.excerptAt(text, "NEEDLE", width = 120)!!
        assertTrue(excerpt.contains("NEEDLE"))
        // The contract: slicing the source at [start, end) reproduces the excerpt
        // exactly, so a caller can page from the reported offset.
        val cps = text.codePoints().toArray()
        assertEquals(excerpt, String(cps, start, end - start))
    }

    @Test
    fun aHitNearTheEndStillYieldsAFullWidthExcerpt() {
        val text = "HEAD " + "z".repeat(200) + " TAILTOKEN"
        val (excerpt, start, end) = HistorySearchTool.excerptAt(text, "TAILTOKEN", width = 100)!!
        assertEquals(100, end - start)
        assertTrue(excerpt.endsWith("TAILTOKEN"))
        assertTrue(start >= 0)
    }

    @Test
    fun aMissingNeedleYieldsNull() {
        assertNull(HistorySearchTool.excerptAt("abc", "zzz"))
        assertNull(HistorySearchTool.excerptAt("", "a"))
    }

    @Test
    fun pagingNeverSplitsASurrogatePairAndReportsNextOffset() {
        // 5000 code points, but 10000 UTF-16 units: slicing by code point is
        // the only way a page boundary can never fall inside an emoji.
        val text = "\ud83d\ude00".repeat(5000)
        val (first, next, truncated) = HistorySearchTool.page(text, 0, limit = 4000)
        assertTrue(truncated)
        assertEquals(4000, first.codePoints().count().toInt())
        assertEquals(4000, next)
        // Resuming at next_offset must not begin with a low surrogate.
        val (second, secondNext, stillTruncated) =
            HistorySearchTool.page(text, next!!, limit = 4000)
        assertFalse(Character.isLowSurrogate(second[0]))
        // 1000 code points remain, so this page ends the text: no next offset.
        assertEquals(1000, second.codePoints().count().toInt())
        assertFalse(stillTruncated)
        assertNull(secondNext)
    }

    @Test
    fun anOutOfRangeOffsetYieldsEmptyAndNoNext() {
        val (body, next, truncated) = HistorySearchTool.page("abc", offset = 99)
        assertEquals("", body)
        assertNull(next)
        assertFalse(truncated)
    }

    @Test
    fun readingToTheEndClearsNextOffset() {
        val (body, next, truncated) = HistorySearchTool.page("abcdef", offset = 0, limit = 4_000)
        assertEquals("abcdef", body)
        assertNull(next)
        assertFalse(truncated)
    }

    @Test
    fun v2IncludesAnchorBySortOrderAndNeitherToolCanReachTheActiveRegion() = runBlocking {
        val history = FakeHistory(tmp.root).apply {
            marker = marker(2, last = "anchor", first = "active", firstOrder = 99)
            add("earlier", 1, text("README in earlier message"))
            add("anchor", 2, text("README in last folded message"))
            add("active", 3, text("README in active message"))
            add("foreign", 1, text("README in other chat"), session = "other")
        }
        assertEquals(listOf("anchor", "earlier"), history.search("readme").ids())
        assertTrue(history.read("anchor").success)
        assertEquals(ToolErrorCode.NOT_FOUND, history.read("active").errorCode)
        assertEquals(ToolErrorCode.NOT_FOUND, history.read("foreign").errorCode)
    }

    @Test
    fun v1FirstKeptIsExclusiveAndSortOnlyMarkersUseTheStoredBoundary() = runBlocking {
        val history = FakeHistory(tmp.root).apply {
            add("earlier", 3, text("find earlier"))
            add("kept", 4, text("find kept"))
            add("active", 5, text("find active"))
        }
        for (old in listOf(marker(1, first = "kept", last = "active", firstOrder = 6),
            marker(1).copy(boundaryMessageId = "kept"), marker(1, last = "earlier"), marker(1, firstOrder = 4))) {
            history.marker = old
            assertEquals(listOf("earlier"), history.search("find").ids())
            assertEquals(ToolErrorCode.NOT_FOUND, history.read("kept").errorCode)
        }
    }

    @Test
    fun unverifiableAnchorNeverFallsBackToWholeSession() = runBlocking {
        val history = FakeHistory(tmp.root).apply {
            add("active", 1, text("find active"))
            add("foreign", 1, text("find foreign"), session = "other")
        }
        for (bad in listOf(marker(2, last = "foreign", first = "active", firstOrder = 2),
            marker(1, first = "missing", last = "active", firstOrder = 2))) {
            history.marker = bad
            val search = HistorySearchTool.executeSearch("""{"query":"find"}""", "own", history.repo)
            assertEquals(ToolErrorCode.EXECUTION_FAILED, search.errorCode)
            assertEquals(ToolErrorCode.NOT_FOUND, history.read("active").errorCode)
        }
        history.marker = null
        assertEquals(listOf("active"), history.search("find").ids())
    }

    @Test
    fun offloadedTextPastPreviewIsSearchableAndReadAtItsCodePointOffset() = runBlocking {
        val history = FakeHistory(tmp.root)
        val content = "\uD83D\uDE00".repeat(131_000) + " READMEMARKER at the end"
        val bytes = text(content).toByteArray()
        val stored = BodyStore(tmp.root.resolve("bodies")).put(bytes)
        assertTrue(stored.ok)
        history.messages += MessageEntity("offloaded", "own", "user", text("body stored (fixture)"), 7,
            sortOrder = 1, bodyBytes = bytes.size.toLong(), bodyRef = stored.ref, bodySha = stored.sha,
            preview = "Beginning only")
        val search = history.search("readmemarker")
        assertEquals(0, search.getInt("preview_only_rows"))
        val hit = search.getJSONArray("hits").getJSONObject(0)
        val read = JSONObject(history.read("offloaded", hit.getInt("excerpt_start")).output)
        assertEquals(hit.getString("excerpt"), read.getString("text"))
        assertFalse(read.optBoolean("preview_only"))
        assertFalse(read.getBoolean("truncated"))
        assertTrue(read.isNull("next_offset"))
        java.io.File(tmp.root.resolve("bodies"), stored.ref!!).delete()
        assertEquals(ToolErrorCode.NOT_FOUND, history.read("offloaded").errorCode)
    }

    @Test
    fun queryAndPageUseTheSameMaskedToolTextWithoutRevealingMetadata() = runBlocking {
        val history = FakeHistory(tmp.root)
        val private = "token=localFakeSecret123"
        val toolText = "Safe output " + private + "\n" + "x".repeat(4_100) + " README marker"
        val parts = JSONArray()
            .put(JSONObject().put("type", "toolUse").put("value", JSONObject().put("input", "secretArgs")))
            .put(JSONObject().put("type", "toolResult").put("value", JSONObject().put("output", toolText)
                .put("receiptProof", "internalProof").put("imageLinuxPath", "/private/local-image")
                .put("snapshot", JSONObject().put("text", "hiddenSnapshot"))))
        history.add("tool-row", 1, parts.toString())
        history.add("host-row", 2, text("<system-reminder>README hidden</system-reminder>"))
        assertEquals(emptyList<String>(), history.search("readme", "user").ids())
        assertEquals(listOf("tool-row"), history.search("readme", "tool").ids())
        assertEquals(emptyList<String>(), history.search("localFakeSecret123").ids())
        val first = history.read("tool-row")
        assertTrue(first.success)
        assertFalse(listOf("localFakeSecret123", "secretArgs", "internalProof", "local-image", "hiddenSnapshot")
            .any { first.output.contains(it) })
        val page = JSONObject(first.output)
        assertTrue(page.getString("text").contains("[redacted]"))
        assertEquals("tool", page.getString("role"))
        assertEquals(4_000, page.getInt("next_offset"))
        val tail = JSONObject(history.read("tool-row", page.getInt("next_offset")).output)
        assertTrue(tail.getString("text").endsWith("README marker"))
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, history.read("tool-row", 99_000).errorCode)
    }

    @Test
    fun readBoundsStructuredOutputAndDoesNotEchoFailureDetails() = runBlocking {
        val history = FakeHistory(tmp.root)
        history.add("m", 1, text("\u0001".repeat(4_500) + "end"))
        val result = history.read("m")
        assertTrue(result.success)
        assertTrue(result.output.length < ToolOutputSpill.LIMIT)
        assertTrue(JSONObject(result.output).getBoolean("truncated"))
        history.queryFailure = IllegalStateException("private database filename")
        val failed = history.read("m")
        assertEquals(ToolErrorCode.EXECUTION_FAILED, failed.errorCode)
        assertFalse(failed.output.contains("private"))
        history.queryFailure = CancellationException("fake cancellation")
        val cancelled = runCatching { history.read("m") }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
    }

    private companion object {
        inline fun <reified T : Any> fake(crossinline answer: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                answer(method.name, args.orEmpty())
            } as T
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HistoryToolPersistenceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun spilledStdoutIsNotSearchedAsFullTextEvenWhenItsFileExists() = withHistory { repo ->
        val preview = spill()
        val row = persist(repo, preview)
        // BodyStore has the complete serialized message, but its output field is a preview.
        val bodyRef = requireNotNull(row.bodyRef)
        val missing = search(repo, MIDDLE)
        assertTrue(missing.ids().isEmpty())
        assertEquals(1, missing.getInt("preview_only_rows"))
        assertEquals(0, missing.optInt("unavailable_rows"))
        assertTrue(missing.getBoolean("truncated"))
        assertPreview(repo, row.id)
        assertEquals(0, search(repo, MIDDLE, role = "user").getInt("preview_only_rows"))

        // Delete the serialized message, not the stdout spill. Neither the
        // DB preview nor the workspace file can substitute for this body.
        assertTrue(tmp.root.resolve("bodies").resolve(bodyRef).delete())
        val unavailable = search(repo, "headkeyword")
        assertTrue(unavailable.ids().isEmpty())
        assertTrue("An unreadable row must not look like a complete zero-hit search", unavailable.getBoolean("truncated"))
        assertEquals(1, unavailable.getInt("unavailable_rows"))
        assertEquals(0, unavailable.getInt("preview_only_rows"))
        assertTrue(unavailable.getString("coverage_note").contains("could not be searched"))
        val read = HistorySearchTool.executeRead(JSONObject().put("message_id", row.id).toString(), SID, repo)
        assertEquals(ToolErrorCode.NOT_FOUND, read.errorCode)
    }

    @Test
    fun aMissingSpillFileLeavesThePersistedPreviewSearchableWithoutClaimingEof() = withHistory { repo ->
        val preview = spill()
        val row = persist(repo, "$TOOL_RECEIPT_NOTICE The command did not report success.\n$preview", isError = true)
        val path = requireNotNull(ToolOutputSpill.declaredSpillPath(preview))
        val file = requireNotNull(ToolOutputSpill.hostFile(tmp.root, SID, path))
        assertTrue(file.delete())
        assertNull(ToolOutputSpill.hostFile(tmp.root, SID, path))
        assertPreview(repo, row.id)
        val beyondPreview = HistorySearchTool.executeRead(
            JSONObject().put("message_id", row.id).put("offset", preview.length + 1000).toString(), SID, repo,
        )
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, beyondPreview.errorCode)
        assertTrue(beyondPreview.output.contains("full tool output is unavailable"))
    }

    @Test
    fun unreadableInlineBodiesLowerCoverageButValidRowsWithoutTextDoNot() = withHistory { repo ->
        repo.appendMessage(SID, "user", "[]")
        repo.appendMessage(SID, "user", """[{"type":"text","value":"   "}]""")
        repo.appendMessage(SID, "assistant", """[{"type":"toolUse","value":{"name":"call","input":"findme"}}]""")
        repo.appendMessage(SID, "user", """[{"type":"text","value":"findme"},{"type":"receiptOrigin","value":"host"}]""")
        val empty = search(repo, "findme", role = null)
        assertTrue(empty.ids().isEmpty())
        assertFalse(empty.getBoolean("truncated"))
        assertEquals(0, empty.optInt("unavailable_rows"))
        assertFalse(empty.has("coverage_note"))

        val readable = repo.appendMessage(SID, "user", """[{"type":"text","value":"findme"}]""")
        // These three shapes have no recoverable inline body, and are newer than
        // the valid match so the scan must continue after encountering them.
        repo.appendMessage(SID, "user", "")
        repo.appendMessage(SID, "user", """[{"type":"text","value":"body omitted; store failed"}]""")
        repo.appendMessage(SID, "user", """[{"type":"text","value":"findme"}""")
        val incomplete = search(repo, "findme", role = "user")
        assertEquals(listOf(readable.id), incomplete.ids())
        assertTrue(incomplete.getBoolean("truncated"))
        assertEquals(3, incomplete.getInt("unavailable_rows"))
        assertEquals(0, incomplete.getInt("preview_only_rows"))
    }

    @Test
    fun aProjectionInOneResultMarksTheWholeCarrierButKeepsOtherResultsSearchable() = withHistory { repo ->
        val full = AgentContentPart.ToolResult("wire", "shell_execute", stdout())
        val projected = requireNotNull(ToolResultProjection.replacement(
            full, ToolResultProjection.AGING_MARKER, headChars = 160, tailChars = 160,
        ))
        val parts = requireNotNull(buildToolResultPartsJson(listOf(
            full.copy(content = projected), AgentContentPart.ToolResult("sibling", "shell_execute", "siblingword"),
        )))
        val row = repo.appendMessage(SID, "user", parts)
        val search = search(repo, "siblingword")
        assertEquals(listOf(row.id), search.ids())
        assertTrue(search.getJSONArray("hits").getJSONObject(0).getBoolean("preview_only"))
        assertEquals(1, search.getInt("preview_only_rows"))
        assertPreview(repo, row.id)
    }

    private fun spill(): String {
        val stdout = stdout()
        val preview = ToolOutputSpill.maybeSpill(tmp.root, SID, "shell_execute", "wire", stdout)
        val path = requireNotNull(ToolOutputSpill.declaredSpillPath(preview))
        assertEquals(stdout, requireNotNull(ToolOutputSpill.hostFile(tmp.root, SID, path)).readText())
        assertFalse(preview.contains(MIDDLE))
        return preview
    }

    private fun stdout() = "headkeyword\n" + "beginning\n".repeat(1200) + "$MIDDLE\n" + "ending\n".repeat(1200)

    private suspend fun persist(repo: ChatRepository, output: String, isError: Boolean = false): MessageEntity {
        val parts = requireNotNull(buildToolResultPartsJson(listOf(
            AgentContentPart.ToolResult("wire", "shell_execute", output, isError = isError),
        )))
        return repo.appendMessage(SID, "user", parts)
    }

    private suspend fun assertPreview(repo: ChatRepository, id: String) {
        val search = search(repo, "headkeyword")
        assertEquals(listOf(id), search.ids())
        val hit = search.getJSONArray("hits").getJSONObject(0)
        assertTrue(hit.getBoolean("preview_only"))
        val result = HistorySearchTool.executeRead(JSONObject().put("message_id", id)
            .put("offset", hit.getInt("excerpt_start")).toString(), SID, repo)
        assertTrue(result.output, result.success)
        val page = JSONObject(result.output)
        assertTrue(page.getString("text").startsWith(hit.getString("excerpt")))
        assertFalse(page.getString("text").contains(MIDDLE))
        assertTrue(page.getBoolean("preview_only"))
        assertTrue(page.getBoolean("truncated"))
        assertTrue(page.isNull("next_offset")) // End of preview, not EOF of stdout.
    }

    private suspend fun search(repo: ChatRepository, word: String, role: String? = "tool"): JSONObject {
        val result = HistorySearchTool.executeSearch(JSONObject().put("query", word).put("role", role).toString(), SID, repo)
        assertTrue(result.output, result.success)
        return JSONObject(result.output)
    }

    private fun JSONObject.ids(): List<String> = getJSONArray("hits").let { hits ->
        List(hits.length()) { hits.getJSONObject(it).getString("message_id") }
    }

    private fun withHistory(test: suspend (ChatRepository) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val dao = db.chatDao()
            dao.insertSession(ChatSessionEntity(id = SID, modelId = "model", createdAt = 1L, updatedAt = 1L))
            test(ChatRepository(dao, db.goalDao(), filesDir = tmp.root, db = db))
        } finally {
            db.close()
        }
    }

    private companion object {
        const val SID = "preview-session"
        const val MIDDLE = "middleonlykeyword"
    }
}
