package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-memory-sheet-simplify] The sheet's information architecture, asserted
 * without composing UI.
 *
 * The complaint this pins: "Auto-Injected" listed six sibling rows — persona,
 * app-wide GLOBAL.md, session GLOBAL.md, a prompt snapshot, today's and
 * yesterday's diary — for what a user reads as two settings. Grouping is a
 * structural property, so it is testable as a pure function; the old flat
 * rendering had no such seam and the row count could only be checked by eye.
 */
class SessionMemorySheetGroupingTest {

    private fun item(
        name: String,
        group: AutoGroup,
        scopeLabel: String = "",
        content: String = "",
        lineCount: Int = if (content.isBlank()) 0 else content.lines().size,
    ) = AutoItem(
        name = name,
        detail = name,
        fileName = name,
        content = content,
        group = group,
        scopeLabel = scopeLabel,
        lineCount = lineCount,
    )

    /** The exact shape the sheet produced before grouping: six flat rows. */
    private fun fullFlatList() = listOf(
        item("persona", AutoGroup.PERSONA, scopeLabel = "Session override"),
        item("GLOBAL.md", AutoGroup.RULES, scopeLabel = "App-wide", content = "a\nb\nc"),
        item("GLOBAL.md", AutoGroup.RULES, scopeLabel = "This session", content = "x"),
        item("snapshot", AutoGroup.DIAGNOSTIC, content = "s1\ns2"),
        item("today", AutoGroup.DIARY, content = "t1\nt2"),
        item("yesterday", AutoGroup.DIARY, content = "y1"),
    )

    @Test
    fun `six flat rows regroup into one persona, two rules, two diary, one diagnostic`() {
        val s = groupAutoItems(fullFlatList())

        assertEquals("persona", s.persona?.name)
        assertEquals(listOf("App-wide", "This session"), s.rules.map { it.scopeLabel })
        assertEquals(listOf("today", "yesterday"), s.diary.map { it.name })
        assertEquals(listOf("snapshot"), s.diagnostic.map { it.name })
    }

    @Test
    fun `primary section is exactly two rows`() {
        val s = groupAutoItems(fullFlatList())
        // One persona row + one rules row. The rules children are revealed by
        // expansion, so they must not count as primary rows.
        val primaryRows = (if (s.persona != null) 1 else 0) + (if (s.rules.isNotEmpty()) 1 else 0)
        assertEquals(2, primaryRows)
        assertTrue(s.hasPrimary)
    }

    @Test
    fun `rules keep injection order — app-wide before session`() {
        // Injection concatenates app-wide standing rules then session ones; the
        // expanded list must read in the same order or the sheet misrepresents
        // the prompt it claims to mirror.
        val s = groupAutoItems(fullFlatList())
        assertEquals("App-wide", s.rules.first().scopeLabel)
        assertEquals("This session", s.rules.last().scopeLabel)
    }

    @Test
    fun `grouping preserves the original item instances`() {
        val flat = fullFlatList()
        val s = groupAutoItems(flat)
        assertSame(flat[0], s.persona)
        assertSame(flat[1], s.rules[0])
        assertSame(flat[4], s.diary[0])
        assertSame(flat[3], s.diagnostic[0])
    }

    @Test
    fun `a sheet with no persona and no rules does not render an empty primary section`() {
        val s = groupAutoItems(listOf(item("today", AutoGroup.DIARY, content = "t")))
        assertNull(s.persona)
        assertTrue(s.rules.isEmpty())
        assertFalse("empty Auto-Injected header must not render", s.hasPrimary)
        assertEquals(1, s.diary.size)
    }

    @Test
    fun `a missing session rules file yields a single rules child, not a phantom row`() {
        val s = groupAutoItems(
            listOf(item("GLOBAL.md", AutoGroup.RULES, scopeLabel = "App-wide", content = "a")),
        )
        assertEquals(1, s.rules.size)
        assertTrue(s.hasPrimary)
    }

    // ── collapsed summary ──────────────────────────────────────────────

    private fun lines(n: Int) = "$n lines"

    @Test
    fun `summary lists both configured scopes`() {
        val s = groupAutoItems(fullFlatList())
        assertEquals("App-wide 3 lines · This session 1 lines", rulesSummary(s.rules, ::lines, "Not set"))
    }

    @Test
    fun `summary omits an unset file instead of advertising zero lines`() {
        val rules = listOf(
            item("GLOBAL.md", AutoGroup.RULES, scopeLabel = "App-wide"),                 // unset
            item("GLOBAL.md", AutoGroup.RULES, scopeLabel = "This session", content = "x"),
        )
        assertEquals("This session 1 lines", rulesSummary(rules, ::lines, "Not set"))
    }

    @Test
    fun `summary of two unset files says so once`() {
        val rules = listOf(
            item("GLOBAL.md", AutoGroup.RULES, scopeLabel = "App-wide"),
            item("GLOBAL.md", AutoGroup.RULES, scopeLabel = "This session"),
        )
        assertEquals("Not set", rulesSummary(rules, ::lines, "Not set"))
    }

    @Test
    fun `summary of no rules at all says so`() {
        assertEquals("Not set", rulesSummary(emptyList(), ::lines, "Not set"))
    }

    @Test
    fun `a whitespace-only file counts as unset, not one line`() {
        // "".lines().size and "  \n".lines().size are both >= 1, which would
        // make an untouched GLOBAL.md look configured.
        val blank = item("GLOBAL.md", AutoGroup.RULES, scopeLabel = "App-wide", content = "   \n")
        assertEquals(0, blank.lineCount)
        assertEquals("Not set", rulesSummary(listOf(blank), ::lines, "Not set"))
    }
}
