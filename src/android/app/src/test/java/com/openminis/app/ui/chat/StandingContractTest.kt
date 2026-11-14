package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
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
        assertTrue(block.contains("BINDING instructions"))
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
        val block = StandingContract.block("x".repeat(400), maxChars = 100)
        assertEquals(101, block.length)
        assertTrue(block.endsWith("…"))
    }

    @Test
    fun `default block stays within MAX_CHARS`() {
        assertTrue(StandingContract.block("富江").length <= StandingContract.MAX_CHARS)
    }
}
