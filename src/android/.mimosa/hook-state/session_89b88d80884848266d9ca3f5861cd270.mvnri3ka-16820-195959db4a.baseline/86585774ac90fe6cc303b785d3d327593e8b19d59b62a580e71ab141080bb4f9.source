package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SupervisorRunStoreTest {

    private fun freshStore() {
        SupervisorRunStore.processStartedAt = 10_000L
    }

    @Test
    fun start_returnsRunningRecordWithObjective() = runBlocking {
        freshStore()
        val r = SupervisorRunStore.start("s1", null, "objective one", 6)
        assertEquals("s1", r.sessionId)
        assertEquals("objective one", r.objective)
        assertEquals(SupervisorRunStore.STATE_RUNNING, r.state)
        assertEquals(0, r.workerTurns)
        assertTrue(r.rounds.isEmpty())
        assertNull(r.doneSummary)
    }

    @Test
    fun start_capsObjectiveLength() = runBlocking {
        freshStore()
        val long = "x".repeat(600)
        val r = SupervisorRunStore.start("s-cap", null, long, 6)
        assertEquals(500, r.objective.length)
    }

    @Test
    fun appendRound_appendsBumpsCountersAndCapsDigests() = runBlocking {
        freshStore()
        SupervisorRunStore.start("s2", null, "objective two", 6)
        val longInstruction = "i".repeat(1000)
        val longReport = "r".repeat(1000)
        SupervisorRunStore.appendRound("s2", null, 1, longInstruction, longReport)
        val r = SupervisorRunStore.latest("s2", null)
        assertEquals(1, r?.rounds?.size)
        assertEquals(1, r?.workerTurns)
        assertEquals(200, r?.rounds?.first()?.instructionDigest?.length)
        assertEquals(500, r?.rounds?.first()?.reportDigest?.length)
    }

    @Test
    fun markTerminal_setsStateAndSummary() = runBlocking {
        freshStore()
        SupervisorRunStore.start("s3", null, "objective three", 6)
        SupervisorRunStore.markTerminal("s3", null, SupervisorRunStore.STATE_DONE, "all green")
        val r = SupervisorRunStore.latest("s3", null)
        assertEquals(SupervisorRunStore.STATE_DONE, r?.state)
        assertEquals("all green", r?.doneSummary)
    }

    @Test
    fun interrupted_flipsStaleRunningRecordFromDeadProcess() = runBlocking {
        freshStore()
        val r = SupervisorRunStore.start("s4", null, "objective four", 6)
        // Simulate a record written by a process that died: creation predates
        // the current process start.
        val stale = r.copy(createdAt = 1_000L)
        SupervisorRunStore.internalReplaceForTest("s4", stale)
        val found = SupervisorRunStore.interrupted("s4", null)
        assertNotNull(found)
        assertEquals(SupervisorRunStore.STATE_INTERRUPTED, found?.state)
    }

    @Test
    fun interrupted_keepsOfferingAfterTheFlip() = runBlocking {
        freshStore()
        val r = SupervisorRunStore.start("s4b", null, "objective 4b", 6)
        SupervisorRunStore.internalReplaceForTest("s4b", r.copy(createdAt = 1_000L))
        // First read reconciles RUNNING -> INTERRUPTED (session load).
        val first = SupervisorRunStore.interrupted("s4b", null)
        assertEquals(SupervisorRunStore.STATE_INTERRUPTED, first?.state)
        // Second read (the Resume tap) must still offer the same record —
        // INTERRUPTED means "awaiting the user's decision", not "consumed".
        val second = SupervisorRunStore.interrupted("s4b", null)
        assertEquals(first, second)
        // The offer retires only via clear (discard) or a new start (resume).
        SupervisorRunStore.clear("s4b", null)
        assertNull(SupervisorRunStore.interrupted("s4b", null))
    }

    @Test
    fun interrupted_neverTouchesThisProcessRunningRecord() = runBlocking {
        freshStore()
        SupervisorRunStore.start("s5", null, "objective five", 6)
        val found = SupervisorRunStore.interrupted("s5", null)
        assertNull(found)
        val r = SupervisorRunStore.internalSnapshotForTest("s5")
        assertEquals(SupervisorRunStore.STATE_RUNNING, r?.state)
    }

    @Test
    fun encodeDecode_roundTripPreservesRoundsAndSummary() = runBlocking {
        val record = SupervisorRunStore.RunRecord(
            id = "id-1",
            sessionId = "s6",
            objective = "ship it",
            state = SupervisorRunStore.STATE_DONE,
            workerTurns = 3,
            rounds = listOf(
                SupervisorRunStore.RoundRecord(1, "do a", "did a", 111L),
                SupervisorRunStore.RoundRecord(2, "do b", "did b", 222L),
            ),
            doneSummary = "done summary",
            createdAt = 1L,
            updatedAt = 2L,
        )
        val decoded = SupervisorRunStore.decode(
            SupervisorRunStore.encode(listOf(record)),
        ).first()
        assertEquals(record, decoded)
    }

    @Test
    fun clear_emptiesTheSession() = runBlocking {
        freshStore()
        SupervisorRunStore.start("s7", null, "objective seven", 6)
        SupervisorRunStore.clear("s7", null)
        assertNull(SupervisorRunStore.interrupted("s7", null))
        assertNull(SupervisorRunStore.internalSnapshotForTest("s7"))
    }
}
