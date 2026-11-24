package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-standing-contract-recency] The dynamic tail must re-assert the standing
 * contract on every request: a persona line (with the configured soul name,
 * graceful generic fallback) plus the standing-rules authority pointer —
 * deterministic and hard-capped. Pure JVM, no Android imports (same
 * constraint as TaskAnchorTest).
 */
class StandingContractTest {

    @Test
    fun `block names the configured persona and re-asserts rules`() {
        val block = StandingContract.block("富江", hasSessionRules = true, hasGlobalRules = true)
        assertTrue(block.contains("Persona reminder (SOUL.md): stay in the configured persona '富江'"))
        assertTrue(block.contains("Session-scoped standing rules"))
        assertTrue(block.contains("Global standing rules (GLOBAL.md)"))
        assertTrue(block.contains("binding constraints"))
        assertTrue(block.contains("latest message still wins"))
    }

    @Test
    fun `block falls back to generic persona wording on blank names`() {
        for (name in listOf(null, "", "   ")) {
            val block = StandingContract.block(name, hasSessionRules = true)
            assertTrue(block.contains("stay in the configured persona for every"))
            assertTrue(block.contains("Session-scoped standing rules"))
        }
    }

    @Test
    fun `block is deterministic and trims the name`() {
        assertEquals(StandingContract.block("富江"), StandingContract.block("富江"))
        assertEquals(StandingContract.block("  富江  "), StandingContract.block("富江"))
    }

    @Test
    fun `block hard-caps at maxChars with ellipsis`() {
        // [T-standing-rules-authority] Persona and rules get separate quotas out
        // of maxChars; only the persona is present here, so the block is the
        // clipped persona (cap + the ellipsis) and never exceeds the budget.
        val block = StandingContract.block("x".repeat(400), maxChars = 100)
        assertTrue("persona clip must stay inside the budget: ${block.length}", block.length <= 100)
        assertTrue(block.endsWith("…"))
    }

    @Test
    fun `default block stays within MAX_CHARS`() {
        assertTrue(StandingContract.block("富江").length <= StandingContract.MAX_CHARS)
    }

    // ── budget truth ────────────────────────────────────────────────────────

    @Test
    fun `a rule block dropped by the budget is not called binding`() {
        val block = StandingContract.block(
            "A",
            hasSessionRules = false,
            hasGlobalRules = true,
            omittedRules = listOf("session-scoped rules (/var/minis/memory/GLOBAL.md)"),
        )
        assertTrue(block.contains("Global standing rules (GLOBAL.md)"))
        assertFalse(
            "a dropped block must not be listed as present",
            block.contains("Session-scoped standing rules"),
        )
        assertFalse(block.contains("Session-scoped standing rules"))
        assertTrue(block.contains("are not present in this prompt"))
        assertTrue(block.contains("/var/minis/memory/GLOBAL.md"))    }

    @Test
    fun `omitted-only contract still tells the model the rules are missing`() {
        val block = StandingContract.block(
            "A",
            hasSessionRules = false,
            omittedRules = listOf("project rules (AGENTS.md)"),
        )
        assertFalse(block.contains("STANDING RULES IN FORCE"))
        assertTrue(block.contains("project rules (AGENTS.md)"))
        assertTrue(block.contains("could rest on their content"))
    }

    @Test
    fun `contract flags require both dedupe-kept and budget-admitted`() {
        val both = standingRuleContract(
            kept = setOf("session-global", "app-global", "project-rules"),
            admitted = setOf("session-global", "app-global", "project-rules"),
        )
        assertTrue(both.hasSessionRules)
        assertTrue(both.hasGlobalRules)
        assertTrue(both.hasProjectRules)
        assertTrue(both.omittedBlocks.isEmpty())

        val dropped = standingRuleContract(
            kept = setOf("session-global", "app-global"),
            admitted = setOf("app-global"),
        )
        assertFalse("budget-dropped session rules are not present", dropped.hasSessionRules)
        assertTrue(dropped.hasGlobalRules)
        assertEquals(
            listOf("session-scoped rules (/var/minis/memory/GLOBAL.md)"),
            dropped.omittedBlocks,
        )

        val deduped = standingRuleContract(
            kept = setOf("app-global"),
            admitted = setOf("app-global"),
        )
        assertFalse(deduped.hasSessionRules)
        assertTrue("a deduped block is not an omission", deduped.omittedBlocks.isEmpty())
    }
}
