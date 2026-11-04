package com.openminis.app.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-memory-center-stats] The hub's numbers are a bounded filesystem walk: shared
 * diary files, recall-count sidecars, the known-failure ledgers, and compaction
 * summaries. These pin what each number counts so the block cannot quietly start
 * reporting something else.
 */
class MemoryCenterStatsTest {

    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("memory-center-stats").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun sharedMemory(): File =
        File(filesDir, MemoryIndex.SHARED_DIR).apply { mkdirs() }

    private fun session(id: String): File = File(filesDir, "minis-sessions/$id").apply { mkdirs() }

    @Test
    fun anEmptyWorkspaceCountsZero() {
        val snapshot = MemoryCenterStats.collect(filesDir, noteCount = 0)
        assertEquals(0, snapshot.files)
        assertEquals(0, snapshot.recalled)
        assertEquals(0, snapshot.blockedRepeats)
        assertEquals(0, snapshot.sediments)
    }

    @Test
    fun sharedDiaryFilesAreCounted() {
        File(sharedMemory(), "GLOBAL.md").writeText("rules")
        File(sharedMemory(), "2026-11-01.md").writeText("a")
        File(sharedMemory(), "2026-11-02.md").writeText("b")
        File(sharedMemory(), "LEARNED.md").writeText("c")
        assertEquals(4, MemoryCenterStats.collect(filesDir, 0).files)
    }

    @Test
    fun noteCountIsPassedThrough() {
        assertEquals(7, MemoryCenterStats.collect(filesDir, noteCount = 7).notes)
    }

    @Test
    fun recallCountsSumAcrossSharedAndSessionSidecars() {
        File(sharedMemory(), MemoryCenterStats.RECALL_SIDECAR)
            .writeText("""{"a":1,"b":2}""")
        val memory = File(session("s1"), "memory").apply { mkdirs() }
        File(memory, MemoryCenterStats.RECALL_SIDECAR).writeText("""{"c":1}""")
        assertEquals(3, MemoryCenterStats.collect(filesDir, 0).recalled)
    }

    @Test
    fun blockedRepeatsComeFromTheFailureLedger() {
        File(session("s1"), MemoryCenterStats.FAILURE_LEDGER)
            .writeText("""{"f1":{"tool":"shell_execute"},"f2":{"tool":"shell_execute"}}""")
        assertEquals(2, MemoryCenterStats.collect(filesDir, 0).blockedRepeats)
    }

    @Test
    fun sedimentsAreCountedPerOccurrenceNotPerFile() {
        val memory = File(session("s1"), "memory").apply { mkdirs() }
        File(memory, "2026-11-03.md").writeText(
            "${MemorySediment.LABEL} 3 messages\nsummary one\n\n" +
                "${MemorySediment.LABEL} 4 messages\nsummary two\n",
        )
        assertEquals(2, MemoryCenterStats.collect(filesDir, 0).sediments)
    }

    @Test
    fun aCorruptSidecarDoesNotThrow() {
        File(sharedMemory(), MemoryCenterStats.RECALL_SIDECAR).writeText("{ not json")
        File(session("s1"), MemoryCenterStats.FAILURE_LEDGER).writeText("also broken")
        val snapshot = MemoryCenterStats.collect(filesDir, 0)
        assertEquals(0, snapshot.recalled)
        assertEquals(0, snapshot.blockedRepeats)
    }

    @Test
    fun theSessionWalkIsCapped() {
        // More sessions than the cap: only the first N are examined. Build N+5
        // ledgers and assert the count stops at the cap.
        val extra = 5
        repeat(MemoryCenterStats.MAX_SESSION_DIRS + extra) { index ->
            val session = session("s%04d".format(index))
            File(session, MemoryCenterStats.FAILURE_LEDGER).writeText("""{"k":{"tool":"t"}}""")
        }
        assertEquals(MemoryCenterStats.MAX_SESSION_DIRS, MemoryCenterStats.collect(filesDir, 0).blockedRepeats)
    }
}
