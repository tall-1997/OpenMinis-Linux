package com.openminis.app.backup

import com.openminis.app.ProductionSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-restore-cancel-propagation] [T-android-restore-cancel-empty-sessions]
 * Stopping a restore, from the importer's side.
 *
 * A Stop has to do three things the eye alone cannot check:
 *  1. reach the importer at all — every category importer below runs to
 *     completion otherwise, so a "cancelled" restore keeps writing;
 *  2. not be swallowed by the per-category failure handler — a
 *     CancellationException IS an Exception, so the generic clause would file
 *     chats under "failed" and carry on with every remaining category;
 *  3. not strand empty sessions — sessions and messages are separate
 *     transactions, so a Stop between them leaves sessions this run created
 *     with no messages, which show up in the user's list as blank chats.
 *
 * (3) is covered as behaviour, over the real cleanup function. (1) and (2) are
 * control flow inside a 1100-line importer that needs a Context and a database
 * to construct, so they are pinned as source guards next to the ported
 * decision, the same pattern the rest of this suite uses.
 */
class RestoreCancelCleanupTest {

    // ── the cleanup, as behaviour ────────────────────────────────────────

    @Test
    fun `only sessions with no messages are deleted`() = runBlocking {
        val counts = mapOf("empty-a" to 0, "has-messages" to 12, "empty-b" to 0)
        val deleted = mutableListOf<String>()
        val removed = BackupImporter.deleteEmptySessions(
            sessionIds = counts.keys,
            messageCount = { counts[it] ?: error("unknown session") },
            delete = { deleted += it },
        )
        assertEquals(listOf("empty-a", "empty-b"), removed)
        assertEquals(listOf("empty-a", "empty-b"), deleted)
    }

    @Test
    fun `a failing lookup skips that session, not the rest of the cleanup`() = runBlocking {
        // Best effort by design: this runs while the job is already cancelled,
        // so one unreadable row must not abandon the sessions after it.
        val removed = BackupImporter.deleteEmptySessions(
            sessionIds = listOf("boom", "empty", "after"),
            messageCount = {
                if (it == "boom") throw IllegalStateException("no such table")
                if (it == "after") 3 else 0
            },
            delete = {},
        )
        assertEquals(listOf("empty"), removed)
    }

    @Test
    fun `a failing delete is not reported as removed`() = runBlocking {
        val removed = BackupImporter.deleteEmptySessions(
            sessionIds = listOf("fails", "ok"),
            messageCount = { 0 },
            delete = { if (it == "fails") throw IllegalStateException("locked") },
        )
        assertEquals(listOf("ok"), removed)
    }

    @Test
    fun `an empty candidate list is not an error`() = runBlocking {
        assertEquals(
            emptyList<String>(),
            BackupImporter.deleteEmptySessions(emptyList(), { 0 }, {}),
        )
    }

    // ── the control flow, as source guards ───────────────────────────────

    private val importer by lazy { ProductionSources.read("backup/BackupImporter.kt") }

    @Test
    fun `a stop reaches the importer between categories`() {
        val loop = importer.substringAfter("for (category in ORDER.filter { it in wanted }) {")
        val dispatch = loop.indexOf("when (category)")
        val stop = loop.indexOf("coroutineContext.ensureActive()")
        assertTrue("the category loop must dispatch something", dispatch > 0)
        assertTrue(
            "a stop must be checked before each category runs: without it a " +
                "cancelled restore goes on writing the remaining categories",
            stop in 0 until dispatch,
        )
    }

    @Test
    fun `a stop is not filed as a category failure`() {
        val rethrow = importer.indexOf("} catch (e: kotlinx.coroutines.CancellationException) {")
        val generic = importer.indexOf("} catch (e: Exception) {")
        assertTrue("both clauses must exist", rethrow >= 0 && generic > rethrow)
        // The cancel clause has to come FIRST and rethrow: the generic clause
        // below turns anything it catches into a per-category failure report.
        val clause = importer.substring(rethrow, generic)
        assertTrue("the clause between them must rethrow", clause.contains("throw e"))
        assertTrue(
            "it must not record a failure before rethrowing",
            !clause.contains("CategoryReport("),
        )
    }

    @Test
    fun `the cleanup only ever touches sessions this run created`() {
        val branch = importer.substringAfter("if (existing == null) {").substringBefore("} else {")
        assertTrue(
            "only a session this run inserted may become a cleanup candidate — " +
                "a pre-existing empty session belongs to the user",
            branch.contains("newlyInsertedSessionIds.add(id)"),
        )
        // Exactly one place may add a candidate, and the cleanup is fed that
        // list — not a re-query of "every session with no messages", which
        // would delete pre-existing empty sessions too.
        assertEquals(
            1,
            Regex("newlyInsertedSessionIds\\.add\\(").findAll(importer).count(),
        )
        val args = importer.substringAfter("deleteEmptySessions(").substringBefore(")")
        assertTrue("the cleanup is fed that list", args.contains("newlyInsertedSessionIds,"))
    }

    @Test
    fun `the cleanup runs even though the job is already cancelled`() {
        val before = importer.substringBefore("deleteEmptySessions(")
        val start = before.lastIndexOf("catch (e: kotlinx.coroutines.CancellationException)")
        assertTrue("the cleanup sits in a cancel handler", start >= 0)
        // Without NonCancellable every suspending DAO call in there throws on
        // entry and the empty sessions are left behind.
        assertTrue(before.substring(start).contains("NonCancellable"))
    }
}
