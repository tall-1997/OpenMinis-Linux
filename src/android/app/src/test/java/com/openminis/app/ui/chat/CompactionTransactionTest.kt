package com.openminis.app.ui.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompactionTransactionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        SessionJournal.resetSeqForTest()
        CompactionTransaction.resetForTest()
    }

    @After
    fun tearDown() = CompactionTransaction.resetForTest()

    @Test
    fun aLiveAttemptIsNotRecoveredAndSettlesOnlyOnce() {
        val sid = "tx-1"
        val before = CompactionTransaction.generationOf(sid)
        val attempt = CompactionTransaction.begin(tmp.root, sid, 12, 13, 100)
        assertEquals(1, SessionJournal.interruptedCompactions(tmp.root, sid).size)
        assertEquals(0, CompactionTransaction.recoverInterrupted(tmp.root, sid))
        CompactionTransaction.settle(tmp.root, sid, attempt, 20, 13, "m1")
        CompactionTransaction.abandon(tmp.root, sid, attempt, "late failure", 20L)
        CompactionTransaction.settle(tmp.root, sid, attempt, 20, 13, "m1")
        assertEquals(before + 1, CompactionTransaction.generationOf(sid))
        assertTrue(CompactionTransaction.retryAuthorized(sid, before))
        assertTrue(CompactionTransaction.retryAllowedNow(sid, 20L))
        val events = SessionJournal.tail(tmp.root, sid)
        assertEquals(listOf(SessionJournal.COMPACTION_START, SessionJournal.COMPACTION_END), events.map { it.getString("kind") })
        assertEquals("m1", events.last().getString("markerId"))
        assertEquals(13, events.last().getInt("coveredCount"))
        assertTrue(SessionJournal.interruptedCompactions(tmp.root, sid).isEmpty())
    }

    @Test
    fun attemptsCannotSettleOrCoolDownTheWrongSession() {
        val a = CompactionTransaction.begin(tmp.root, "a", 0, 1, 100)
        val b = CompactionTransaction.begin(tmp.root, "b", 1, 2, 100)
        CompactionTransaction.abandon(tmp.root, "b", a, "wrong owner", 100L)
        CompactionTransaction.settle(tmp.root, "b", a, 20, 1, "wrong-owner")
        assertEquals(0, CompactionTransaction.generationOf("b"))
        assertEquals(a, SessionJournal.interruptedCompactions(tmp.root, "a").single().getString("compactionId"))
        assertEquals(b, SessionJournal.interruptedCompactions(tmp.root, "b").single().getString("compactionId"))

        CompactionTransaction.abandon(tmp.root, "a", a, "marker not persisted", 1_000L)
        CompactionTransaction.abandon(tmp.root, "b", b, REASON_CANCELLED, 1_000L)
        assertEquals(0, CompactionTransaction.generationOf("a"))
        assertFalse(CompactionTransaction.retryAuthorized("a", 0))
        assertFalse(CompactionTransaction.retryAllowedNow("a", 1_001L))
        assertTrue(CompactionTransaction.retryAllowedNow("a", 999L))
        assertTrue(CompactionTransaction.retryAllowedNow("a", 1_000L + CompactionTransaction.FAILURE_COOLDOWN_MS))
        assertTrue(CompactionTransaction.retryAllowedNow("b", 1_001L))
        assertTrue(SessionJournal.interruptedCompactions(tmp.root, "a").isEmpty())
        assertTrue(SessionJournal.interruptedCompactions(tmp.root, "b").isEmpty())
    }

    @Test
    fun aLostAttemptIsReportedOnceWithoutFabricatingGeneration() {
        val sid = "recovered"
        CompactionTransaction.begin(tmp.root, sid, 0, 1, 100)
        CompactionTransaction.resetForTest() // Simulates a process that no longer owns the open attempt.
        assertEquals(1, CompactionTransaction.recoverInterrupted(tmp.root, sid))
        assertEquals(0, CompactionTransaction.recoverInterrupted(tmp.root, sid))
        assertFalse(CompactionTransaction.retryAuthorized(sid, 0))
        assertTrue(SessionJournal.interruptedCompactions(tmp.root, sid).isEmpty())
    }

    @Test
    fun aMissingFilesDirIsNeverFatal() {
        val attempt = CompactionTransaction.begin(null, "s", 0, 1, 100)
        CompactionTransaction.settle(null, "s", attempt, 20, 1, "m1")
        assertEquals(1, CompactionTransaction.generationOf("s"))
    }
}
