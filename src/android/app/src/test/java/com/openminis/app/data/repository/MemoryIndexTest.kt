package com.openminis.app.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-memory-index] The index exists so that diary days outside the verbatim
 * window stay *discoverable* instead of silently vanishing from the prompt.
 * These pin the scope merge, the verbatim-window exclusion, and the bounded size.
 */
class MemoryIndexTest {

    private lateinit var root: File
    private lateinit var sessionDir: File
    private lateinit var sharedDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("memory-index").toFile()
        sessionDir = File(root, "session").apply { mkdirs() }
        sharedDir = File(root, "shared").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun diary(dir: File, date: String, vararg entries: String): File {
        val body = entries.joinToString("\n\n") { "<!-- $date 10:00:00 -->\n$it" }
        return File(dir, "$date.md").apply { writeText(body) }
    }

    @Test
    fun sessionAndSharedDaysAreMergedNewestFirst() {
        diary(sessionDir, "2026-11-01", "session note")
        diary(sharedDir, "2026-10-30", "shared note")
        val rows = MemoryIndex.rows(sessionDir, sharedDir, excludeDates = emptySet())
        assertEquals(listOf("2026-11-01", "2026-10-30"), rows.map { it.date })
        assertEquals(listOf(false, true), rows.map { it.shared })
    }

    @Test
    fun theVerbatimWindowIsExcluded() {
        diary(sessionDir, "2026-11-03", "a")
        diary(sessionDir, "2026-11-02", "b")
        diary(sessionDir, "2026-11-01", "c")
        val rows = MemoryIndex.rows(sessionDir, sharedDir, excludeDates = setOf("2026-11-03", "2026-11-02"))
        assertEquals(listOf("2026-11-01"), rows.map { it.date })
    }

    @Test
    fun theSessionCopyWinsOnTheSameDate() {
        diary(sessionDir, "2026-11-01", "session version")
        diary(sharedDir, "2026-11-01", "shared version")
        val rows = MemoryIndex.rows(sessionDir, sharedDir, excludeDates = emptySet())
        assertEquals(1, rows.size)
        assertFalse("the session row replaces the shared one", rows.single().shared)
        assertTrue(rows.single().preview.contains("session version"))
    }

    @Test
    fun entryCountAndPreviewAreExtracted() {
        diary(sessionDir, "2026-11-01", "first line", "second line")
        val row = MemoryIndex.rows(sessionDir, sharedDir, excludeDates = emptySet()).single()
        assertEquals(2, row.entries)
        assertEquals("first line", row.preview)
    }

    @Test
    fun nonDiaryAndEmptyFilesAreIgnored() {
        File(sessionDir, "GLOBAL.md").writeText("rules")
        File(sessionDir, "2026-11-01.md").writeText("   ")
        assertTrue(MemoryIndex.rows(sessionDir, sharedDir, excludeDates = emptySet()).isEmpty())
    }

    @Test
    fun rowsAreCappedAndTheOmittedCountIsReported() {
        for (day in 1..10) diary(sessionDir, "2026-10-%02d".format(day), "note $day")
        val rows = MemoryIndex.rows(sessionDir, sharedDir, excludeDates = emptySet(), maxRows = 4)
        assertEquals(4, rows.size)
        val rendered = MemoryIndex.render(rows, omitted = 6)!!
        assertTrue(rendered.contains("6 older day(s) not listed"))
    }

    @Test
    fun sharedRowsAreLabelledAsShared() {
        diary(sharedDir, "2026-10-30", "from another chat")
        val rendered = MemoryIndex.render(MemoryIndex.rows(sessionDir, sharedDir, emptySet()))!!
        assertTrue(rendered.contains("shared"))
    }

    @Test
    fun fragmentIsEnvelopedAndNullWhenThereIsNothing() {
        assertNull(MemoryIndex.fragment(sessionDir, sharedDir, excludeDates = emptySet()))
        diary(sessionDir, "2026-10-30", "note")
        val fragment = MemoryIndex.fragment(sessionDir, sharedDir, excludeDates = emptySet())!!
        assertTrue(fragment.startsWith("[MEMORY_REFERENCE"))
        assertTrue(fragment.contains("2026-10-30"))
        assertTrue(fragment.contains("memory_get"))
    }

    @Test
    fun exclusionIsByIdentityNotPosition() {
        // The verbatim window reads only the session dir; a count-based drop
        // would exclude the newest dates of the session+shared union and make
        // this shared-only day — never injected verbatim — invisible.
        diary(sessionDir, "2026-11-05", "session day")
        diary(sharedDir, "2026-11-06", "shared day, newer than the window")
        val rows = MemoryIndex.rows(sessionDir, sharedDir, excludeDates = setOf("2026-11-05"))
        assertEquals(listOf("2026-11-06"), rows.map { it.date })
    }
}
