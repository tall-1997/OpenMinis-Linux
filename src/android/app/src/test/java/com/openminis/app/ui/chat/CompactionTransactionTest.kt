package com.openminis.app.ui.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-compaction-transaction] The two durable facts a crash can leave behind:
 * an unmatched `compaction/start` (a paid-for attempt that never settled) and
 * the generation counter that alone authorizes a retry.
 *
 * Deliberately exercises the STORAGE layer (SessionJournal + the counter) rather
 * than the ViewModel extension functions: those need a live `ChatViewModel`,
 * a DB and a coroutine scope, and a test that only passes when all three exist
 * would pin the wiring but not the contract. The wiring itself is guarded by the
 * architecture test that asserts `beginCompactionJournal(` / `settleCompactionJournal(`
 * are called in `ChatViewModelCompactExt`.
 */
class CompactionTransactionTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("compact-tx").toFile()
        SessionJournal.resetSeqForTest()
        CompactionTransaction.resetForTest()
    }

    @After
    fun tearDown() {
        CompactionTransaction.resetForTest()
        dir.deleteRecursively()
    }

    @Test
    fun anOpenStartIsReportedUntilItCloses() {
        val sid = "tx-1"
        SessionJournal.record(
            dir, sid, SessionJournal.COMPACTION_START,
            mapOf("compactionId" to "c1", "anchorIdx" to 12, "rangeMessages" to 30),
        )
        assertEquals(1, SessionJournal.interruptedCompactions(dir, sid).size)

        SessionJournal.record(
            dir, sid, SessionJournal.COMPACTION_END,
            mapOf("compactionId" to "c1", "coveredCount" to 30, "markerId" to "m1"),
        )
        assertTrue(SessionJournal.interruptedCompactions(dir, sid).isEmpty())
        assertEquals(null, CompactionTransaction.openId)
    }

    @Test
    fun anAbandonedAttemptIsClosedButDoesNotAdvanceTheGeneration() {
        val sid = "tx-2"
        val before = CompactionTransaction.generationOf(sid)
        SessionJournal.record(
            dir, sid, SessionJournal.COMPACTION_START, mapOf("compactionId" to "c2"),
        )
        SessionJournal.record(
            dir, sid, SessionJournal.COMPACTION_INTERRUPTED,
            mapOf("compactionId" to "c2", "reason" to "no usable summary"),
        )

        assertTrue(SessionJournal.interruptedCompactions(dir, sid).isEmpty())
        assertEquals(before, CompactionTransaction.generationOf(sid))
        assertFalse("a failed attempt must not authorize a retry", CompactionTransaction.retryAuthorized(sid, before))
    }

    @Test
    fun onlyASettledAttemptAdvancesTheRetryGeneration() {
        val sid = "tx-3"
        val before = CompactionTransaction.generationOf(sid)
        assertFalse(CompactionTransaction.retryAuthorized(sid, before))

        // A settle is the only place the generation moves.
        val generation = CompactionTransaction.advanceGeneration(sid)
        assertEquals(before + 1, generation)
        assertTrue(CompactionTransaction.retryAuthorized(sid, before))
        assertFalse("the generation does not move twice for one attempt", CompactionTransaction.retryAuthorized(sid, generation))
    }

    @Test
    fun generationsArePerSessionAndMonotonic() {
        CompactionTransaction.advanceGeneration("a")
        CompactionTransaction.advanceGeneration("a")
        CompactionTransaction.advanceGeneration("b")

        assertEquals(2, CompactionTransaction.generationOf("a"))
        assertEquals(1, CompactionTransaction.generationOf("b"))
        // An unknown session starts at zero, so its first settle is a real move.
        assertEquals(0, CompactionTransaction.generationOf("never-seen"))
    }

    @Test
    fun aMissingFilesDirIsNeverFatal() {
        // A process without storage must still be able to compact: the journal
        // is evidence, not a precondition.
        val id = CompactionTransaction.newId()
        SessionJournal.record(null, "s", SessionJournal.COMPACTION_START, mapOf("compactionId" to id))
        CompactionTransaction.advanceGeneration("s")
        assertTrue(CompactionTransaction.generationOf("s") > 0)
    }
}
