package com.openminis.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.pruneStaleCompactMarkers
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-m6-compact-marker-staleness] The prune walk against a REAL in-memory Room
 * database — the pure rule is covered by CompactMarkerValidityTest, this covers
 * what only SQLite can show: that the walk reads `compact_markers` newest-first,
 * deletes through the DAO, and STOPS on the first marker still anchored.
 *
 * The reported symptom this prevents: retry / edit / delete-from-here cut the
 * tail inside a folded range, the marker survived, and the next compaction
 * handed its summary to the model as `previousSummary` — folding deleted text
 * into a fresh summary and returning it to context turns later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CompactMarkerStalenessIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var sessionId: String

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ChatRepository(
            dao = db.chatDao(),
            goalDao = db.goalDao(),
            filesDir = null,
            versionDao = db.messageVersionDao(),
            db = db,
        )
        sessionId = repo.createSession(modelId = "test-model").id
        // m0..m5 at sort_order 0..5, one millisecond apart.
        repeat(6) { i ->
            db.chatDao().insertMessage(
                MessageEntity(
                    id = "m$i",
                    sessionId = sessionId,
                    role = if (i % 2 == 0) "user" else "assistant",
                    partsJson = """[{"type":"text","text":"m$i"}]""",
                    createdAt = 1_000_000L + i,
                    sortOrder = i,
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun marker(id: String, anchor: String, at: Long) {
        db.chatDao().insertCompactMarker(
            CompactMarkerEntity(
                id = id,
                sessionId = sessionId,
                summary = "summary of everything up to $anchor",
                firstKeptSortOrder = 0,
                compactedCount = 3,
                createdAt = at,
                lastCompactedMessageId = anchor,
                version = 2,
            )
        )
    }

    @Test
    fun `a marker whose anchor predates the cut survives`() = runBlocking {
        marker("k1", anchor = "m2", at = 10L)

        // Cut at sort_order 3: m3..m5 go, the folded range (m0..m2) is intact.
        repo.deleteMessagesAfter(sessionId, 3)

        assertEquals(0, pruneStaleCompactMarkers(db.chatDao(), sessionId))
        assertNotNull(db.chatDao().latestCompactMarker(sessionId))
    }

    @Test
    fun `a marker whose anchor the cut removed is dropped`() = runBlocking {
        marker("k1", anchor = "m4", at = 10L)

        repo.deleteMessagesAfter(sessionId, 3) // m4 gone

        assertEquals(1, pruneStaleCompactMarkers(db.chatDao(), sessionId))
        assertNull(db.chatDao().latestCompactMarker(sessionId))
        // Idempotent: nothing left to prune, and no exception on the empty case.
        assertEquals(0, pruneStaleCompactMarkers(db.chatDao(), sessionId))
    }

    @Test
    fun `the walk stops at the first marker that is still anchored`() = runBlocking {
        // Two-level chain: k1 folds m0..m1, k2 folds m2..m4. A cut at 3 kills
        // k2's anchor but leaves k1 perfectly valid — pruning k1 would throw
        // away a summary of rows that still exist, and it is the ONLY summary
        // left in the session.
        marker("k1", anchor = "m1", at = 10L)
        marker("k2", anchor = "m4", at = 20L)

        repo.deleteMessagesAfter(sessionId, 3)

        assertEquals(1, pruneStaleCompactMarkers(db.chatDao(), sessionId))
        val latest = db.chatDao().latestCompactMarker(sessionId)
        assertEquals("k1", latest?.id)
        assertEquals("m1", latest?.lastCompactedMessageId)
    }

    @Test
    fun `a sort-order-only legacy marker is left alone`() = runBlocking {
        db.chatDao().insertCompactMarker(
            CompactMarkerEntity(
                id = "legacy",
                sessionId = sessionId,
                summary = "v1 summary",
                firstKeptSortOrder = 3,
                compactedCount = 3,
                createdAt = 10L,
                version = 1,
            )
        )

        // Renumbering makes numeric anchors meaningless; the legacy resolution
        // path in effectiveAgentHistory() owns this row.
        repo.deleteMessagesAfter(sessionId, 3)
        assertEquals(0, pruneStaleCompactMarkers(db.chatDao(), sessionId))
        assertNotNull(db.chatDao().latestCompactMarker(sessionId))
    }
}
