package com.openminis.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * The hand-written SELECT heads in [RawQuerySql] are string-built, so neither
 * the Kotlin compiler nor Room's annotation processor can see them. Room's
 * `@RawQuery` mappers bind **by column name** and assign a silent null for any
 * column they cannot find — and when the missing field is non-null in the
 * projection, the failure surfaces at *construction* time, i.e. only on a
 * query that actually returns a row.
 *
 * That is exactly how `search_sessions` broke: `substr(COALESCE(preview,
 * parts_json), …)` was emitted with no `AS parts_json`, so SQLite named the
 * column after the expression text, `MessageSearchRow.partsJson` came back
 * null, and every hit threw `Parameter specified as non-null is null` — which
 * the tool dispatcher reported to the model as `permission_denied`, while a
 * keyword with zero hits quietly succeeded. A schema-shaped projection
 * (`MESSAGE_PAGE_SELECT`, which enumerates every column of `messages`) cannot
 * rot that way because the debug pager and the schema share a column list, so
 * the contract that needs a test is the *expression* columns.
 *
 * These checks run against a real in-memory SQLite (Robolectric, plain JVM
 * unit-test task) and ask the cursor what it actually produced, so a dropped
 * or renamed alias fails here instead of in a user's search.
 */
@RunWith(RobolectricTestRunner::class)
// Plain android.app.Application, NOT MinisApp: the app class wires sandbox
// paths that do not exist under Robolectric. This test only needs a Context
// to hand Room an in-memory database.
@Config(sdk = [35], application = android.app.Application::class)
class RawQueryColumnContractTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Column names the cursor really produces for a SELECT head. */
    private fun columnsOf(selectHead: String): List<String> {
        db.openHelper.readableDatabase.rawQuery("$selectHead LIMIT 0", emptyArray<String>())
            .use { cursor ->
                return (0 until cursor.columnCount).map { cursor.getColumnName(it) }
            }
    }

    /** Column names the live schema declares for a table. */
    private fun schemaColumnsOf(table: String): List<String> {
        db.openHelper.readableDatabase.rawQuery("PRAGMA table_info($table)", emptyArray<String>())
            .use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val out = mutableListOf<String>()
                while (cursor.moveToNext()) out += cursor.getString(nameIdx)
                return out
            }
    }

    private fun assertExposes(selectHead: String, expected: List<String>) {
        val actual = columnsOf(selectHead)
        val missing = expected.filterNot { it in actual }
        assertTrue(
            "SELECT head is missing column(s) $missing that its projection " +
                "consumes; a missing column maps to a silent null and, for a " +
                "non-null field, throws when a row is constructed.\n" +
                "  emitted: $actual\n  expected: $expected",
            missing.isEmpty(),
        )
    }

    @Test
    fun `message search head aliases its expression column to parts_json`() {
        assertExposes(
            RawQuerySql.MESSAGE_SEARCH_SELECT,
            listOf("session_id", "id", "role", "created_at", "parts_json"),
        )
    }

    @Test
    fun `message search head does not leak the raw expression as a column name`() {
        // SQLite names an unaliased expression column after its source text.
        // If that ever comes back, the projection silently loses parts_json.
        val actual = columnsOf(RawQuerySql.MESSAGE_SEARCH_SELECT)
        assertTrue(
            "unaliased expression column leaked into the cursor: $actual",
            actual.none { it.contains("substr(", ignoreCase = true) },
        )
    }

    @Test
    fun `sessions meta head aliases both computed columns`() {
        assertExposes(
            RawQuerySql.SESSIONS_META_SELECT,
            listOf("id", "title", "first_user_msg", "source", "created_at", "updated_at", "msg_count"),
        )
    }

    @Test
    fun `message page head still covers every column the schema declares`() {
        val schema = schemaColumnsOf("messages")
        assertTrue("PRAGMA returned no columns for messages", schema.isNotEmpty())
        assertExposes(RawQuerySql.MESSAGE_PAGE_SELECT, schema)
    }

    @Test
    fun `message count head aliases its aggregate to count`() {
        assertExposes(RawQuerySql.MESSAGE_COUNT_SELECT, listOf("count"))
    }

    @Test
    fun `the search head maps rows instead of throwing on every hit`() = runBlocking {
        // End-to-end through the real mapper: a row must survive construction.
        // Before the alias existed this threw for every single match.
        db.chatDao().insertSession(
            ChatSessionEntity(id = "s1", modelId = "test-model", createdAt = 1L, updatedAt = 1L),
        )
        db.chatDao().insertMessage(
            MessageEntity(
                id = "m1",
                sessionId = "s1",
                role = "user",
                partsJson = """[{"type":"text","value":"alpha keyword"}]""",
                createdAt = 10L,
                sortOrder = 0,
            ),
        )
        val rows = db.chatDao().runMessageSearchQuery(
            androidx.sqlite.db.SimpleSQLiteQuery(
                RawQuerySql.MESSAGE_SEARCH_SELECT + " WHERE m.parts_json LIKE ?",
                arrayOf<Any>("%keyword%"),
            ),
        )
        assertEquals(1, rows.size)
        assertTrue(
            "mapped parts_json lost its text: ${rows[0].partsJson}",
            rows[0].partsJson.contains("alpha keyword"),
        )
        assertEquals("s1", rows[0].sessionId)
        assertEquals("m1", rows[0].id)
    }

    @Test
    fun `an unaliased expression really is named after its source text`() {
        // Sentinel for the assumption every check above relies on: if SQLite
        // ever named unaliased columns something the projection could still
        // bind, the missing alias would be harmless and these tests noise.
        val cols = columnsOf(
            "SELECT substr(COALESCE(preview, parts_json), 1, 64) FROM messages",
        )
        assertEquals(1, cols.size)
        assertTrue("expected an expression-derived name, got $cols", "parts_json" !in cols)
    }
}
