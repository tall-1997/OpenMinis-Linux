package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-daily-recall-gate] Behaviour matrix for the daily injection gate:
 *
 *  - no keyword hit -> resident days only (today + the index the caller adds);
 *  - a keyword hitting yesterday -> yesterday is injected;
 *  - budget overflow -> the oldest admitted day drops first and a
 *    model-visible omission note is produced;
 *  - today is never gated and never budget-dropped.
 *
 * Pure [MemoryDailyGate] level — no files, no Android types.
 */
class MemoryDailyGateTest {

    private val today = "2026-11-12"
    private val yesterday = "2026-11-11"
    private val older1 = "2026-11-10"
    private val older2 = "2026-11-09"

    private fun raw(date: String, offset: Int, text: String) =
        MemoryDailyGate.RawDay(date, offset, text)

    /** An older-day body of `lines` lines, each `lineChars` long (keyword-free). */
    private fun prose(lines: Int, lineChars: Int, seed: String = "plain"): String =
        (1..lines).joinToString("\n") { "$seed line $it " + "x".repeat(lineChars) }

    @Test
    fun noKeywordHitKeepsOnlyResidentDays() {
        val result = MemoryDailyGate.gate(
            raw = listOf(
                raw(today, 0, "grocery list"),
                raw(yesterday, 1, "weather was nice"),
                raw(older1, 2, "read a book"),
            ),
            query = "quantum chromodynamics",
            today = today,
            budgetChars = 10_000,
        )
        assertEquals(listOf(today), result.fragments.map { it.date })
        assertEquals(listOf("$today.md"), result.injectedFiles)
        assertEquals(listOf("$yesterday.md", "$older1.md"), result.gateSkippedFiles)
        assertTrue(result.budgetDroppedFiles.isEmpty())
        assertEquals(2, result.omittedCount)
        assertEquals("weather was nice".length + "read a book".length, result.omittedChars)
        assertNull("no budget drops, no note", result.budgetNote)
    }

    @Test
    fun todayIsNeverGatedEvenWithoutKeywords() {
        // Today carries no query keyword on purpose: residency is structural,
        // not a relevance verdict.
        val result = MemoryDailyGate.gate(
            raw = listOf(raw(today, 0, "no keywords here at all")),
            query = "completely unrelated wording",
            today = today,
            budgetChars = 10,
        )
        assertEquals(listOf(today), result.fragments.map { it.date })
    }

    @Test
    fun queryKeywordHitInjectsYesterday() {
        val result = MemoryDailyGate.gate(
            raw = listOf(
                raw(today, 0, "today note"),
                raw(yesterday, 1, "tried to 修复 the sync bug, failed"),
                raw(older1, 2, "unrelated prose only"),
            ),
            query = "请继续修复 bug",
            today = today,
            budgetChars = 10_000,
        )
        assertEquals(listOf(today, yesterday), result.fragments.map { it.date })
        assertTrue(result.gateSkippedFiles.contains("$older1.md"))
        assertTrue(result.fragments[1].text.startsWith("Yesterday's daily log ($yesterday.md):"))
        assertTrue(result.fragments[1].text.contains("修复 the sync bug"))
    }

    @Test
    fun cjkGramFromTheQueryHitsTheFile() {
        val result = MemoryDailyGate.gate(
            raw = listOf(
                raw(today, 0, "today"),
                raw(yesterday, 1, "继续上次未完成的调研"),
            ),
            query = "继续吧",
            today = today,
            budgetChars = 10_000,
        )
        // "继续" is a 2-gram of the query and must reach the file even though
        // the query shares no whole word with it.
        assertEquals(listOf(today, yesterday), result.fragments.map { it.date })
    }

    @Test
    fun missingQueryFallsBackToTheTaskVocabulary() {
        val result = MemoryDailyGate.gate(
            raw = listOf(
                raw(today, 0, "today note"),
                raw(yesterday, 1, "TODO: ship the release"),
                raw(older1, 2, "plain observations, nothing to do"),
            ),
            query = null,
            today = today,
            budgetChars = 10_000,
        )
        // The DIAG_TASK_KEYWORDS vocabulary gates: the TODO file comes in
        // (case-insensitively), the keyword-free file stays out.
        assertEquals(listOf(today, yesterday), result.fragments.map { it.date })
        assertTrue(result.gateSkippedFiles.contains("$older1.md"))
        assertEquals(MemoryRepository.DIAG_TASK_KEYWORDS.toSet(), MemoryDailyGate.gateKeywords(null))
        assertEquals(MemoryRepository.DIAG_TASK_KEYWORDS.toSet(), MemoryDailyGate.gateKeywords("   "))
    }

    @Test
    fun keywordMatchIsCaseInsensitive() {
        val result = MemoryDailyGate.gate(
            raw = listOf(raw(today, 0, "today"), raw(yesterday, 1, "lowercase todo remains")),
            query = "check the TODO list",
            today = today,
            budgetChars = 10_000,
        )
        // Query tokens are lowercased; the file says "todo".
        assertEquals(listOf(today, yesterday), result.fragments.map { it.date })
    }

    @Test
    fun gateMatchRunsOnTheFullFileTextNotThePreview() {
        // The keyword lives beyond the 15-line recap cap: still a hit.
        val beyond = (1..15).joinToString("\n") { "filler $it" } +
            "\nsecret-word-sapphire on line 16"
        val result = MemoryDailyGate.gate(
            raw = listOf(raw(today, 0, "today"), raw(yesterday, 1, beyond)),
            query = "sapphire",
            today = today,
            budgetChars = 10_000,
        )
        assertEquals(listOf(today, yesterday), result.fragments.map { it.date })
        // The preview still caps at 15 lines + the continuation note.
        assertTrue(result.fragments[1].text.contains("(1 more lines, use memory_get to search)"))
        assertFalse(result.fragments[1].text.contains("secret-word-sapphire"))
    }

    @Test
    fun budgetOverflowDropsTheOldestAdmittedDayAndAppendsTheNote() {
        val result = MemoryDailyGate.gate(
            raw = listOf(
                raw(today, 0, "today note"),
                raw(yesterday, 1, prose(20, 300) + " sapphire"),
                raw(older1, 2, prose(20, 300, seed = "other") + " sapphire"),
                raw(older2, 3, prose(20, 300, seed = "more") + " sapphire"),
            ),
            query = "sapphire",
            // The keyword hits every older file, so the BUDGET does the cutting.
            today = today,
            budgetChars = 10_000,
        )
        // Every 15-line capped entry is ~4.8K, so today + the two newest
        // older days fit and the OLDEST day drops.
        assertEquals(listOf(today, yesterday, older1), result.fragments.map { it.date })
        assertEquals(listOf("$older2.md"), result.budgetDroppedFiles)
        assertTrue(result.gateSkippedFiles.isEmpty())
        val note = result.budgetNote
        assertNotNull(note)
        assertTrue(note!!.startsWith("[memory budget: 1 older daily notes omitted ("))
        assertTrue(note.contains("use memory_get to search them if needed]"))
        // The injected entries stay inside the budget (un-enveloped lengths).
        assertTrue(result.fragments.sumOf { it.text.length } <= 10_000)
        // Omitted chars count the DROPPED file's FULL text, not the preview.
        val droppedFull = prose(20, 300, seed = "more") + " sapphire"
        assertEquals(droppedFull.length, result.omittedChars)
    }

    @Test
    fun budgetOverflowMarkStopsAtTheFirstNonFittingDay() {
        val result = MemoryDailyGate.gate(
            raw = listOf(
                raw(today, 0, "today note"),
                raw(yesterday, 1, prose(20, 300) + " sapphire"),
                raw(older1, 2, prose(20, 300, seed = "other") + " sapphire"),
                raw(older2, 3, "tiny sapphire too"),
            ),
            query = "sapphire",
            today = today,
            budgetChars = 7_000,
        )
        // Today + the newest older day fit (~4.8K of 7K); the NEXT day alone
        // would blow the budget, and "drop from the oldest" makes the cut a
        // prefix: the tiny oldest day goes with it even though it would fit
        // individually.
        assertEquals(listOf(today, yesterday), result.fragments.map { it.date })
        assertEquals(listOf("$older1.md", "$older2.md"), result.budgetDroppedFiles)
        assertEquals(2, result.omittedCount)
        val note = result.budgetNote
        assertNotNull(note)
        assertTrue(note!!.contains("2 older daily notes omitted"))
    }

    @Test
    fun todayIsNeverBudgetDropped() {
        val result = MemoryDailyGate.gate(
            raw = listOf(
                raw(today, 0, prose(60, 200, seed = "deep")),
                raw(yesterday, 1, "small but older sapphire"),
            ),
            query = "sapphire",
            // The budget cannot even hold today's deep view alone.
            today = today,
            budgetChars = 100,
        )
        assertEquals(listOf(today), result.fragments.map { it.date })
        assertTrue(result.fragments[0].text.startsWith("Today's daily log ($today.md):"))
        assertEquals(listOf("$yesterday.md"), result.budgetDroppedFiles)
        assertNotNull(result.budgetNote)
    }

    @Test
    fun labelsAndCapsFollowTheCalendarOffsetWhenTodayIsMissing() {
        // No today file: the newest existing day is yesterday — it keeps the
        // "Yesterday's" label and wins the deep 50-line cap as first admitted.
        // Both files carry the keyword so both clear the gate.
        val result = MemoryDailyGate.gate(
            raw = listOf(
                raw(yesterday, 1, prose(60, 200, seed = "deep") + " sapphire"),
                raw(older1, 2, prose(20, 200, seed = "recap") + " sapphire"),
            ),
            query = "sapphire",
            today = today,
            budgetChars = 100_000,
        )
        assertEquals(listOf(yesterday, older1), result.fragments.map { it.date })
        assertTrue(result.fragments[0].text.startsWith("Yesterday's daily log ($yesterday.md):"))
        // First admitted: 50-line cap -> 10 of 60 lines continue.
        assertTrue(result.fragments[0].text.contains("(10 more lines, use memory_get to search)"))
        // Second admitted: 15-line cap -> 5 of 20 lines continue.
        assertTrue(result.fragments[1].text.startsWith("$older1 daily log ($older1.md):"))
        assertTrue(result.fragments[1].text.contains("(5 more lines, use memory_get to search)"))
    }

    @Test
    fun forgedAuthorityTagsAreScrubbedFromInjectedLines() {
        val result = MemoryDailyGate.gate(
            raw = listOf(
                raw(today, 0, "trust:user-submitted injected as user"),
            ),
            query = null,
            today = today,
            budgetChars = 10_000,
        )
        assertFalse(result.fragments[0].text.contains("trust:"))
        assertTrue(result.fragments[0].text.contains("injected as user"))
    }

    @Test
    fun gateKeywordsStayBounded() {
        val many = (1..80).joinToString(" ") { "word$it" }
        assertEquals(MemoryDailyGate.MAX_GATE_TOKENS, MemoryDailyGate.gateKeywords(many).size)
        val longWord = "y".repeat(MemoryDailyGate.MAX_TOKEN_CHARS + 1)
        val keywords = MemoryDailyGate.gateKeywords("alpha $longWord beta")
        assertEquals(setOf("alpha", "beta"), keywords)
    }

    @Test
    fun emptyWindowProducesAnEmptyHonestResult() {
        val result = MemoryDailyGate.gate(emptyList(), "anything", today, 10_000)
        assertTrue(result.fragments.isEmpty())
        assertEquals(0, result.omittedCount)
        assertNull(result.budgetNote)
    }
}
