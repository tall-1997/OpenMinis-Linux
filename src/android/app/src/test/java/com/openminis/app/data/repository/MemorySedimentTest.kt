package com.openminis.app.data.repository

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-memory-sediment] Compaction leaves a searchable trace, but only once per
 * distinct payload: re-compacting the same stretch (retry, revert-then-compact)
 * must not multiply the daily log.
 */
class MemorySedimentTest {

    private lateinit var dir: File
    private lateinit var repository: MemoryRepository

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("sediment").toFile()
        repository = MemoryRepository(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun blankSummaryProducesNoEntry() {
        assertNull(MemorySediment.buildEntry("   \n", 3))
    }

    @Test
    fun entryIsLabelledAndCarriesTheFoldedCount() {
        val entry = MemorySediment.buildEntry("decided to ship on friday", 12)!!
        assertTrue(entry.startsWith(MemorySediment.LABEL))
        assertTrue(entry.contains("12 message(s)"))
        assertTrue(entry.contains("decided to ship on friday"))
    }

    @Test
    fun longSummaryIsCapped() {
        val entry = MemorySediment.buildEntry("x".repeat(MemorySediment.MAX_CHARS + 500), 1)!!
        assertTrue(entry.length <= MemorySediment.MAX_CHARS + 200)
        assertTrue(entry.endsWith("…"))
    }

    @Test
    fun identicalPayloadIsNotWrittenTwice() {
        val entry = MemorySediment.buildEntry("the same summary", 5)
        val first = MemorySediment.record(repository, entry)
        assertNotNull("first write must land", first)
        val second = MemorySediment.record(repository, entry)
        assertNull("second identical write must be skipped", second)
    }

    @Test
    fun aDifferentPayloadIsWritten() {
        MemorySediment.recordCompaction(repository, "summary one", 3)
        assertNotNull(MemorySediment.recordCompaction(repository, "summary two", 4))
    }

    @Test
    fun writtenEntryIsReadableFromTheDailyLog() {
        MemorySediment.recordCompaction(repository, "remember the release date", 2)
        val today = com.openminis.app.util.IsoTime.formatLocalDate()
        val log = repository.readFile("$today.md")
        assertTrue(log.contains(MemorySediment.LABEL))
        assertTrue(log.contains("remember the release date"))
    }

    @Test
    fun duplicateDetectionWorksOnRawText() {
        assertTrue(MemorySediment.isDuplicate("abc", "xx abc yy"))
        assertFalse(MemorySediment.isDuplicate("abc", "xx yy"))
    }
}
