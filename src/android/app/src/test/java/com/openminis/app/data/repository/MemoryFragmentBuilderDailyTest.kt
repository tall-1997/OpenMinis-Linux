package com.openminis.app.data.repository

import com.openminis.app.evolution.LearnedPrefsStore
import com.openminis.app.util.IsoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-daily-recall-gate] Integration level: the builder reads the real daily
 * files, [MemoryDailyGate] decides, and the returned [MemoryFragmentBuilder.DailyInjection]
 * carries exactly what the prompt will carry — enveloped fragments, honest
 * omitted counts, and the budget note for the caller's daily-index suffix.
 * Also pins the cache contract: the RAW window is cached by file fingerprint,
 * while the gate re-runs per call, so a changed query changes the output
 * without a re-read.
 */
class MemoryFragmentBuilderDailyTest {

    private val today = IsoTime.formatLocalDate()
    private val yesterday = IsoTime.formatLocalDate(System.currentTimeMillis() - 86_400_000L)
    private val older1 = IsoTime.formatLocalDate(System.currentTimeMillis() - 2L * 86_400_000L)
    private val older2 = IsoTime.formatLocalDate(System.currentTimeMillis() - 3L * 86_400_000L)

    private class Fixture(val dir: File) {
        val builder = MemoryFragmentBuilder(
            dir,
            LearnedPrefsStore(File(dir, "LEARNED.md")),
            fingerprint = { f -> if (f.exists()) "${f.lastModified()}:${f.length()}" else null },
        )

        fun write(name: String, content: String) {
            File(dir, name).writeText(content)
        }
    }

    private fun fixture(): Fixture = Fixture(Files.createTempDirectory("daily-gate").toFile())

    private fun oldProse(lines: Int, seed: String): String =
        (1..lines).joinToString("\n") { "$seed line $it " + "x".repeat(300) }

    @Test
    fun queryMissInjectsOnlyTodayAndCountsTheSkips() {
        val fx = fixture()
        fx.write("$today.md", "today's notes")
        fx.write("$yesterday.md", "weather was calm")
        fx.write("$older1.md", "read a book")
        val injection = fx.builder.loadRecentDailyFragments("quantum chromodynamics")
        assertEquals(listOf(today), injection.fragments.map { it.date })
        assertEquals(2, injection.omittedCount)
        assertEquals("weather was calm".length + "read a book".length, injection.omittedChars)
        assertNull(injection.budgetNote)
        // Every injected fragment is enveloped.
        assertTrue(injection.fragments.all { it.text.startsWith("[MEMORY_REFERENCE") })
    }

    @Test
    fun queryHitInjectsTheMatchingOlderDay() {
        val fx = fixture()
        fx.write("$today.md", "today's notes")
        fx.write("$yesterday.md", "tried to 修复 the sync bug")
        fx.write("$older1.md", "unrelated prose only")
        val injection = fx.builder.loadRecentDailyFragments("请继续修复 bug")
        assertEquals(listOf(today, yesterday), injection.fragments.map { it.date })
        assertEquals(1, injection.omittedCount)
        // The enveloped fragment still carries the matched content.
        assertTrue(injection.fragments[1].text.contains("修复 the sync bug"))
    }

    @Test
    fun nullQueryFallsBackToTheTaskVocabulary() {
        val fx = fixture()
        fx.write("$today.md", "today's notes")
        fx.write("$yesterday.md", "TODO: ship the release")
        fx.write("$older1.md", "calm day, nothing special")
        val injection = fx.builder.loadRecentDailyFragments(null)
        assertEquals(listOf(today, yesterday), injection.fragments.map { it.date })
        assertEquals(1, injection.omittedCount)
    }

    @Test
    fun theGateReRunsPerCallOnTheCachedRawWindow() {
        val fx = fixture()
        fx.write("$today.md", "today's notes")
        fx.write("$yesterday.md", "weather was calm, very 天气")
        fx.write("$older1.md", "read a book about coral")
        // First call: the query misses everything older.
        val first = fx.builder.loadRecentDailyFragments("quantum chromodynamics")
        assertEquals(listOf(today), first.fragments.map { it.date })
        // Second call on the SAME builder (raw cache hit): a different query
        // must change the gate outcome without a re-read.
        val second = fx.builder.loadRecentDailyFragments("珊瑚色 coral")
        assertEquals(listOf(today, older1), second.fragments.map { it.date })
        // And a third call goes back — no stale state leaks from the cache.
        val third = fx.builder.loadRecentDailyFragments("quantum chromodynamics")
        assertEquals(listOf(today), third.fragments.map { it.date })
    }

    @Test
    fun budgetOverflowSurfacesTheNoteAndHonestCounts() {
        val fx = fixture()
        fx.write("$today.md", "today's notes")
        fx.write("$yesterday.md", oldProse(20, "deep") + " sapphire")
        fx.write("$older1.md", oldProse(20, "reef") + " sapphire")
        fx.write("$older2.md", "tiny but relevant sapphire")
        val injection = fx.builder.loadRecentDailyFragments("sapphire")
        // Two ~4.8K recap entries + today exceed the 8K daily budget: the
        // newest-first prefix cut keeps today + yesterday and drops BOTH the
        // big oldest day and the tiny one behind it.
        assertEquals(listOf(today, yesterday), injection.fragments.map { it.date })
        assertEquals(2, injection.omittedCount)
        // Omitted chars = the dropped files' FULL text lengths.
        assertEquals(
            (oldProse(20, "reef") + " sapphire").length + "tiny but relevant sapphire".length,
            injection.omittedChars,
        )
        val note = injection.budgetNote
        assertNotNull(note)
        assertTrue(note!!.startsWith("[memory budget: 2 older daily notes omitted ("))
        assertTrue(note.contains("use memory_get to search them if needed]"))
    }

    @Test
    fun todayIsPresentAndNeverBudgetDropped() {
        val fx = fixture()
        fx.write("$today.md", oldProse(60, "deep"))
        fx.write("$yesterday.md", "small older day sapphire")
        val injection = fx.builder.loadRecentDailyFragments("sapphire")
        assertTrue(injection.fragments.map { it.date }.contains(today))
        assertFalse(injection.fragments.map { it.date }.contains(yesterday))
        // The envelope rides ahead of the entry text, so the label is matched
        // with contains, not startsWith.
        assertTrue(injection.fragments.first().text.contains("Today's daily log ($today.md):"))
        assertEquals(1, injection.omittedCount)
        assertNotNull(injection.budgetNote)
    }

    @Test
    fun emptyMemoryDirGivesAnEmptyHonestInjection() {
        val fx = fixture()
        val injection = fx.builder.loadRecentDailyFragments("anything")
        assertTrue(injection.fragments.isEmpty())
        assertEquals(0, injection.omittedCount)
        assertEquals(0, injection.omittedChars)
        assertNull(injection.budgetNote)
    }

    @Test
    fun missingTodayStillLabelsTheNewestExistingDayYesterday() {
        val fx = fixture()
        fx.write("$yesterday.md", "old note with sapphire")
        fx.write("$older1.md", "older note with sapphire")
        val injection = fx.builder.loadRecentDailyFragments("sapphire")
        assertEquals(listOf(yesterday, older1), injection.fragments.map { it.date })
        assertTrue(injection.fragments[0].text.contains("Yesterday's daily log ($yesterday.md):"))
    }
}
