package com.openminis.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.body.Admission
import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.display.DisplayParts
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
 * `searchMessages` (the `search_sessions` tool and `minis-sessions-cli search`)
 * against a REAL in-memory Room database.
 *
 * Two failures lived in the same query and neither was visible to a unit test
 * that stopped at the SQL string:
 *
 *  1. **Every hit threw.** The dynamic SELECT emitted
 *     `substr(COALESCE(preview, parts_json), …)` with no `AS parts_json`, so
 *     SQLite named that column after the expression text, Room's `@RawQuery`
 *     mapper bound `MessageSearchRow.partsJson` to a silent null, and
 *     constructing the row threw `Parameter specified as non-null is null`.
 *     The dispatcher surfaced it to the model as `permission_denied`, while a
 *     keyword with *zero* hits returned an empty list successfully — which is
 *     why the bug looked like "search never finds anything" rather than a
 *     crash. [RawQueryColumnContractTest] pins the column names; this test
 *     pins the behaviour through the real mapper.
 *
 *  2. **Spilled bodies were unsearchable.** Anything over
 *     [ResourceLimits.INLINE_BODY_BYTES] (2 KiB — most assistant turns and
 *     every large tool result) is written to `filesDir/bodies` and its
 *     `parts_json` column is replaced by a `"body stored (N bytes)"` stub.
 *     `WHERE parts_json LIKE ?` therefore matched the stub, so the substantive
 *     part of every real conversation was invisible to search while the SELECT
 *     was already reaching for `preview`. Both columns are matched now.
 *
 * Robolectric (not instrumented) so this runs in the plain JVM unit-test task
 * on every PR, same as the rest of the suite.
 */
@RunWith(RobolectricTestRunner::class)
// Plain android.app.Application, NOT MinisApp: the real app's onCreate wires
// RootfsManager against sandbox paths that don't exist under Robolectric.
@Config(sdk = [35], application = android.app.Application::class)
class MessageSearchIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var sessionId: String
    private var order = 0

    @Before
    fun setUp() = runBlocking {
        // Admission.tryAdmit gates the whole query: an exhausted budget makes
        // searchMessages return emptyList, which would fake a pass on every
        // "expects no hits" case below. Reset it (test-only hook) so a prior
        // test in the same JVM cannot starve these calls.
        Admission.resetForTests()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // filesDir = null keeps storeBody off the disk path; the spilled rows
        // below are written directly in the shape storeBody leaves behind.
        repo = ChatRepository(
            dao = db.chatDao(),
            goalDao = db.goalDao(),
            filesDir = null,
            versionDao = db.messageVersionDao(),
            db = db,
        )
        sessionId = repo.createSession(modelId = "test-model").id
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** An inline row: body small enough to stay in `parts_json`. */
    private suspend fun inlineMessage(text: String, at: Long, role: String = "user") {
        val parts = """[{"type":"text","value":${org.json.JSONObject.quote(text)}}]"""
        db.chatDao().insertMessage(
            MessageEntity(
                id = "m-inline-${order++}",
                sessionId = sessionId,
                role = role,
                partsJson = parts,
                createdAt = at,
                sortOrder = order,
                bodyBytes = parts.toByteArray().size.toLong(),
                preview = parts.take(ResourceLimits.PREVIEW_BYTES),
            ),
        )
    }

    /**
     * A spilled row exactly as [ChatRepository.storeBody] leaves it: the real
     * text survives only in `preview`, `parts_json` holds the stub, and
     * `body_ref` points at the on-disk body.
     */
    private suspend fun spilledMessage(text: String, at: Long, role: String = "assistant") {
        val full = """[{"type":"text","value":${org.json.JSONObject.quote(text)}}]"""
        db.chatDao().insertMessage(
            MessageEntity(
                id = "m-spilled-${order++}",
                sessionId = sessionId,
                role = role,
                partsJson = DisplayParts.note("body stored (${full.length} bytes)"),
                createdAt = at,
                sortOrder = order,
                bodyBytes = full.toByteArray().size.toLong(),
                bodyRef = "bodies/${order}.json",
                bodySha = "deadbeef",
                preview = full.take(ResourceLimits.PREVIEW_BYTES),
            ),
        )
    }

    private suspend fun search(vararg keywords: String) =
        repo.searchMessages(null, keywords.toList(), 10, null, null)

    @Test
    fun `an inline hit maps and returns instead of throwing`() = runBlocking {
        inlineMessage("planning the Kyoto itinerary", at = 1_000L)

        val hits = search("Kyoto")

        assertEquals(1, hits.size)
        assertEquals(sessionId, hits[0].sessionId)
        assertEquals("user", hits[0].role)
        assertTrue("snippet lost the keyword: ${hits[0].snippet}", hits[0].snippet.contains("Kyoto"))
    }

    @Test
    fun `a hit whose text only survives in preview is still found`() = runBlocking {
        // Before both columns were matched this returned nothing at all: the
        // WHERE clause compared the keyword against the "body stored" stub.
        spilledMessage(
            "the rolling build cache was evicted by the 10GB LRU quota",
            at = 2_000L,
        )

        val hits = search("LRU quota")

        assertEquals(1, hits.size)
        assertTrue("snippet lost the keyword: ${hits[0].snippet}", hits[0].snippet.contains("LRU"))
    }

    @Test
    fun `a stub is never reported as a match for its own wording`() = runBlocking {
        spilledMessage("ordinary content about databases", at = 3_000L)

        // "body stored" is the stub's text, not user-visible content. Matching
        // it would make every spilled row a hit for the same meaningless word.
        assertTrue(search("body stored").isEmpty())
    }

    @Test
    fun `zero hits returns empty rather than throwing`() = runBlocking {
        inlineMessage("something entirely unrelated", at = 4_000L)

        // This is the case that always worked, which is why the mapper crash
        // stayed hidden: an empty result set never constructs a row.
        assertTrue(search("nonexistent-token-xyz").isEmpty())
    }

    @Test
    fun `keywords AND together across inline and spilled rows`() = runBlocking {
        inlineMessage("alpha meets beta", at = 5_000L)
        spilledMessage("alpha meets gamma", at = 5_100L)

        val both = search("alpha", "beta")
        assertEquals(1, both.size)
        assertTrue(both[0].snippet.contains("beta"))

        val neither = search("alpha", "delta")
        assertTrue(neither.isEmpty())
    }

    @Test
    fun `session filter and date window still narrow the match set`() = runBlocking {
        inlineMessage("windowed keyword inside", at = 6_000L)
        inlineMessage("windowed keyword outside", at = 9_000L)

        val inWindow = repo.searchMessages(
            listOf(sessionId), listOf("windowed"), 10, 5_500L, 7_000L,
        )
        assertEquals(1, inWindow.size)
        assertTrue(inWindow[0].snippet.contains("inside"))

        val otherSession = repo.searchMessages(
            listOf("some-other-session"), listOf("windowed"), 10, null, null,
        )
        assertTrue(otherSession.isEmpty())
    }

    @Test
    fun `session meta keyword search reaches a spilled body`() = runBlocking {
        spilledMessage("the ndk toolchain is pinned to r29", at = 7_000L)

        val metas = repo.querySessionsMeta(
            sessionIds = null,
            keywords = listOf("r29"),
            limit = 10,
            startMs = null,
            endMs = null,
            keywordsMatchBody = true,
        )

        assertEquals(listOf(sessionId), metas.map { it.id })
    }

    @Test
    fun `title-only meta search does not become a body oracle`() = runBlocking {
        spilledMessage("the ndk toolchain is pinned to r29", at = 7_500L)

        // keywordsMatchBody = false is what the ungated `minis-sessions-cli
        // list` passes; matching preview there would leak foreign content.
        val metas = repo.querySessionsMeta(
            sessionIds = null,
            keywords = listOf("r29"),
            limit = 10,
            startMs = null,
            endMs = null,
            keywordsMatchBody = false,
        )

        assertTrue("title-only search matched a body keyword", metas.isEmpty())
    }

    @Test
    fun `text past the preview prefix is documented as unsearchable`() = runBlocking {
        // The database keeps only the first PREVIEW_BYTES of a spilled body,
        // so a keyword past that prefix lives solely in the body file and a
        // DB-side search cannot see it. Pin the boundary instead of letting a
        // future reader assume search is full-text.
        val filler = "x".repeat(ResourceLimits.PREVIEW_BYTES + 512)
        spilledMessage("$filler tail-only-keyword", at = 8_000L)

        assertTrue(search("tail-only-keyword").isEmpty())
    }
}
