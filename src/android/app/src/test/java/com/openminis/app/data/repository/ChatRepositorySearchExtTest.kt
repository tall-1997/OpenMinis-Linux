package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the `[T-#26-session-search-and]` word-aware fork: token
 * rules, the multi-word routing predicate, and the composed raw-SQL shapes
 * (bind order is load-bearing — see [buildSessionsMultiQuery]'s KDoc).
 */
class ChatRepositorySearchExtTest {

    @Test
    fun `tokenizes with min length and cap`() {
        assertEquals(listOf("alpha", "beta"), searchWords("  alpha   beta  "))
        assertEquals(listOf("alpha"), searchWords("alpha a")) // single chars are noise
        assertEquals(4, searchWords("aa bb cc dd ee ff").size) // cap: extras truncated
        assertEquals(emptyList<String>(), searchWords("   "))
    }

    @Test
    fun `routing sends whitespace queries multi and single-word ones to the original path`() {
        assertTrue(isMultiWordSearch("alpha beta"))
        assertTrue(isMultiWordSearch("alpha\tbeta"))
        assertFalse(isMultiWordSearch("alpha")) // no whitespace → original path
        assertFalse(isMultiWordSearch("a b")) // tokens all die → original path, not match-all
        assertFalse(isMultiWordSearch(""))
    }

    @Test
    fun `sessions multi query AND-chains each word on title and preview`() {
        val q = buildSessionsMultiQuery(listOf("alpha", "beta"))
        assertTrue(q.sql.contains("instr(lower(s.title), lower(?)) > 0"))
        assertTrue(q.sql.contains("instr(lower(COALESCE(m.preview, m.parts_json)), lower(?)) > 0"))
        assertTrue(q.sql.contains("ORDER BY s.updated_at DESC LIMIT 200"))
        // Each word binds twice — title conjunct then preview conjunct.
        assertEquals(listOf("alpha", "beta", "alpha", "beta"), q.args)
    }

    @Test
    fun `snippet multi query bind order is window-first then ids then words`() {
        val q = buildSnippetMultiQuery(listOf("alpha", "beta"), listOf("s1", "s2"), radius = 40, windowChars = 120)
        assertEquals(listOf("alpha", 40, 120, "s1", "s2", "alpha", "beta"), q.args)
        assertTrue(q.sql.contains("GROUP BY m.session_id HAVING m.sort_order = MIN(m.sort_order)"))
    }

    @Test
    fun `degenerate all-short-token query never reaches the multi path`() {
        // "a b" has whitespace but no surviving word — the fork must send it to
        // the original whole-string query rather than build an empty AND.
        assertFalse(isMultiWordSearch("a b"))
        val q = buildSessionsMultiQuery(searchWords("a b"))
        assertTrue(q.sql.contains("WHERE ()")) // shape guard: empty conjunctions are visible
    }
}
