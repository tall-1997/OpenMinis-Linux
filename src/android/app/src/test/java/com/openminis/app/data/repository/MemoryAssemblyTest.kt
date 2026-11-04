package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-memory-window] Pins the two-pass assembly contract: every diary day is
 * either admitted verbatim or excluded from the TOC by identity — never
 * invisible, never duplicated — and the TOC added in pass 2 never evicts a
 * day pass 1 admitted.
 */
class MemoryAssemblyTest {

    private fun standing(key: String, text: String, priority: Int, truncatable: Boolean) =
        MemoryPromptBudget.Fragment(key, text, priority, truncatable)

    private fun day(date: String, text: String) = MemoryAssembly.DailyFragment(date, text)

    @Test
    fun allDaysFitAndTheTocExcludesExactlyThem() {
        val allDates = setOf("2026-11-05", "2026-11-04", "2026-11-01")
        val seen = mutableListOf<Set<String>>()
        val result = MemoryAssembly.assemble(
            standing = emptyList(),
            daily = listOf(day("2026-11-05", "today body"), day("2026-11-04", "yesterday body")),
            budgetChars = 10_000,
        ) { excluded ->
            seen.add(excluded)
            "toc: " + (allDates - excluded).sorted().joinToString(",")
        }
        // Pass 2 saw exactly the admitted dates...
        assertEquals(listOf(setOf("2026-11-05", "2026-11-04")), seen)
        // ...and the final allocation carries both days plus the TOC.
        assertEquals(
            listOf("daily-2026-11-05", "daily-2026-11-04", "daily-index"),
            result.allocation.included.map { it.key },
        )
        assertEquals(setOf("2026-11-05", "2026-11-04"), result.admittedDailyDates)
        assertTrue(result.droppedDailyDates.isEmpty())
        val rendered = result.allocation.rendered!!
        assertTrue(rendered.contains("today body"))
        // The older day is the only TOC row; the admitted ones are not listed.
        assertTrue(rendered.contains("toc: 2026-11-01"))
    }

    @Test
    fun aDayTheBudgetDropsFallsBackToTheToc() {
        val allDates = setOf("2026-11-05", "2026-11-04", "2026-11-01")
        val seen = mutableListOf<Set<String>>()
        val result = MemoryAssembly.assemble(
            standing = emptyList(),
            daily = listOf(
                day("2026-11-05", "today body"),
                day("2026-11-04", "x".repeat(200)),
            ),
            // Room for today only: the older day cannot even truncate (its
            // head would fall below the 512-char floor), so it drops.
            budgetChars = "today body".length + 100,
        ) { excluded ->
            seen.add(excluded)
            "toc: " + (allDates - excluded).sorted().joinToString(",")
        }
        // Only today was admitted verbatim...
        assertEquals(listOf(setOf("2026-11-05")), seen)
        assertEquals(setOf("2026-11-05"), result.admittedDailyDates)
        assertEquals(setOf("2026-11-04"), result.droppedDailyDates)
        // ...and the dropped day is still named by the TOC — zero invisibility.
        val rendered = result.allocation.rendered!!
        assertTrue(rendered.contains("today body"))
        assertTrue(
            "dropped day must stay visible via the TOC",
            rendered.contains("toc: 2026-11-01,2026-11-04"),
        )
    }

    @Test
    fun aTruncatedDayIsDemotedToAFullTocRow() {
        // Truncation consumes the whole remaining budget, so the TOC after a
        // truncated day can never fit; the loop demotes the truncated day
        // (the oldest admitted) and reruns, trading the partial view for a
        // full TOC row.
        val allDates = setOf("2026-11-05", "2026-11-04", "2026-11-01")
        val seen = mutableListOf<Set<String>>()
        val result = MemoryAssembly.assemble(
            standing = emptyList(),
            daily = listOf(day("2026-11-05", "today"), day("2026-11-04", "x".repeat(2_000))),
            budgetChars = "today".length + 700,
        ) { excluded ->
            seen.add(excluded)
            "toc: " + (allDates - excluded).sorted().joinToString(",")
        }
        // Pass 1 truncated the older day; pass 2 could not fit the TOC, so
        // the loop demoted it and reran the pair of passes.
        assertEquals(
            listOf(setOf("2026-11-05", "2026-11-04"), setOf("2026-11-05")),
            seen,
        )
        assertEquals(setOf("2026-11-05"), result.admittedDailyDates)
        assertEquals(setOf("2026-11-04"), result.droppedDailyDates)
        val rendered = result.allocation.rendered!!
        assertTrue(rendered.contains("today"))
        assertTrue("the demoted day must stay visible via the TOC", rendered.contains("2026-11-04"))
    }

    @Test
    fun anExhaustedBudgetDemotesTheOldestFullyAdmittedDay() {
        val allDates = setOf("2026-11-05", "2026-11-04", "2026-11-01")
        val seen = mutableListOf<Set<String>>()
        val result = MemoryAssembly.assemble(
            standing = emptyList(),
            daily = listOf(
                day("2026-11-05", "today"),
                day("2026-11-04", "y".repeat(600)),
            ),
            // Fits both days whole but leaves the TOC below the truncation
            // floor: the loop must demote the older day, not drop the TOC.
            budgetChars = "today".length + 600 + 100,
        ) { excluded ->
            seen.add(excluded)
            "toc: " + (allDates - excluded).sorted().joinToString(",") + " " + "z".repeat(200)
        }
        assertEquals(
            listOf(setOf("2026-11-05", "2026-11-04"), setOf("2026-11-05")),
            seen,
        )
        assertEquals(setOf("2026-11-05"), result.admittedDailyDates)
        assertEquals(setOf("2026-11-04"), result.droppedDailyDates)
        assertTrue("daily-index" in result.allocation.included.map { it.key })
        val rendered = result.allocation.rendered!!
        assertTrue(rendered.contains("today"))
        assertTrue("the demoted day must stay visible via the TOC", rendered.contains("2026-11-04"))
    }

    @Test
    fun noDailyDaysStillIndexesEverything() {
        val seen = mutableListOf<Set<String>>()
        val result = MemoryAssembly.assemble(
            standing = emptyList(),
            daily = emptyList(),
            budgetChars = 10_000,
        ) { excluded ->
            seen.add(excluded)
            "toc of all history"
        }
        assertEquals(listOf(emptySet<String>()), seen)
        assertEquals(listOf("daily-index"), result.allocation.included.map { it.key })
        assertTrue(result.allocation.rendered!!.contains("toc of all history"))
    }

    @Test
    fun aNullTocLeavesTheProbeAllocation() {
        val result = MemoryAssembly.assemble(
            standing = emptyList(),
            daily = listOf(day("2026-11-05", "today body")),
            budgetChars = 10_000,
        ) { _ -> null }
        assertEquals(listOf("daily-2026-11-05"), result.allocation.included.map { it.key })
        assertTrue(result.allocation.droppedKeys.isEmpty())
    }

    @Test
    fun standingFragmentsWinTheBudgetBeforeTheDailyWindow() {
        val result = MemoryAssembly.assemble(
            standing = listOf(
                standing(
                    "project-rules", "rules",
                    MemoryPromptBudget.PRIORITY_PROJECT,
                    truncatable = false,
                ),
            ),
            daily = listOf(day("2026-11-05", "today body")),
            budgetChars = "rules".length,
        ) { _ -> "toc" }
        assertEquals(listOf("project-rules"), result.allocation.included.map { it.key })
        assertTrue("daily-2026-11-05" in result.allocation.droppedKeys)
        assertEquals(setOf("2026-11-05"), result.droppedDailyDates)
    }

    @Test
    fun theTocTruncatesItselfRatherThanDisplacingADaily() {
        val result = MemoryAssembly.assemble(
            standing = emptyList(),
            daily = listOf(day("2026-11-05", "today")),
            budgetChars = "today".length + 600,
        ) { _ -> "y".repeat(2_000) }
        val rendered = result.allocation.rendered!!
        assertTrue(rendered.contains("today"))
        assertFalse("daily-2026-11-05" in result.allocation.droppedKeys)
        // The TOC itself was truncated in, not dropped.
        assertTrue("daily-index" in result.allocation.truncatedKeys)
    }

    @Test
    fun everyDayIsEitherAdmittedOrDroppedNeverBoth() {
        val dates = (0 until 7).map { "2026-10-%02d".format(25 - it) }
        val result = MemoryAssembly.assemble(
            standing = emptyList(),
            daily = dates.mapIndexed { index, date -> day(date, "body $index") },
            budgetChars = 40,
        ) { _ -> "toc" }
        assertTrue(
            "admitted and dropped must be disjoint",
            result.admittedDailyDates.intersect(result.droppedDailyDates).isEmpty(),
        )
        assertEquals(dates.toSet(), result.admittedDailyDates + result.droppedDailyDates)
    }

    @Test
    fun theHeaderIsInsertedAheadOfTheFirstDayAndAccounted() {
        val result = MemoryAssembly.assemble(
            standing = emptyList(),
            daily = listOf(day("2026-11-05", "today body"), day("2026-11-04", "yesterday body")),
            budgetChars = 10_000,
            header = "CAPTION",
        ) { _ -> "toc" }
        assertEquals(
            listOf("daily-header", "daily-2026-11-05", "daily-2026-11-04", "daily-index"),
            result.allocation.included.map { it.key },
        )
        assertEquals(
            "today body".length + "yesterday body".length + "toc".length + "CAPTION".length,
            result.allocation.usedChars,
        )
        assertTrue(result.allocation.rendered!!.startsWith("CAPTION"))
    }

    @Test
    fun theHeaderSurvivesWhenTheNewestDayIsDropped() {
        // The old shape baked the caption into the newest day; a budget that
        // dropped that day rendered the rest of the window without its
        // "past tasks" guard. The reservation makes the caption structural.
        val allDates = setOf("2026-11-05", "2026-11-04", "2026-11-01")
        val result = MemoryAssembly.assemble(
            standing = emptyList(),
            daily = listOf(
                day("2026-11-05", "x".repeat(2_000)),
                day("2026-11-04", "yesterday body"),
            ),
            // 599 reserved for the caption → 401 left: the 2000-char day
            // cannot even truncate (head 372 < the 512 floor) and drops;
            // the 14-char day and the 26-char TOC fit.
            budgetChars = 1_000,
            header = "CAPTION: " + "c".repeat(590),
        ) { excluded ->
            "toc: " + (allDates - excluded).sorted().joinToString(",")
        }
        assertEquals(setOf("2026-11-04"), result.admittedDailyDates)
        assertEquals(setOf("2026-11-05"), result.droppedDailyDates)
        val rendered = result.allocation.rendered!!
        assertTrue(rendered.startsWith("CAPTION"))
        assertTrue(rendered.contains("yesterday body"))
        assertTrue(
            "the dropped newest day stays visible via the TOC",
            rendered.contains("toc: 2026-11-01,2026-11-05"),
        )
    }

    @Test
    fun noHeaderWhenNothingDailyIsAdmitted() {
        val result = MemoryAssembly.assemble(
            standing = listOf(
                standing(
                    "project-rules", "rules",
                    MemoryPromptBudget.PRIORITY_PROJECT,
                    truncatable = false,
                ),
            ),
            daily = listOf(day("2026-11-05", "x".repeat(2_000))),
            // 600 reserved → 100 left: the rules fit, the day and the TOC
            // cannot even truncate — nothing daily made it in, so the
            // caption is not spent and the block is rules-only.
            budgetChars = 700,
            header = "c".repeat(600),
        ) { _ -> "t".repeat(200) }
        assertEquals(listOf("project-rules"), result.allocation.included.map { it.key })
        assertEquals("rules".length, result.allocation.usedChars)
    }
}
