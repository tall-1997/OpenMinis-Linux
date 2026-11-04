package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-memory-instruction-dedupe] Identical instruction bodies render once. DSH
 * does this in `agent-instructions` ("identical trimmed content renders once");
 * without it, rules copied from GLOBAL.md into AGENTS.md are sent twice.
 */
class InstructionDedupeTest {

    @Test
    fun uniqueBodiesAreAllKept() {
        val kept = InstructionDedupe.firstSeen(
            listOf("project-rules" to "a", "session-global" to "b", "app-global" to "c"),
        )
        assertEquals(setOf("project-rules", "session-global", "app-global"), kept)
    }

    @Test
    fun duplicateBodyKeepsTheFirstCandidate() {
        // Callers pass narrowest scope first, so the more specific layer survives.
        val kept = InstructionDedupe.firstSeen(
            listOf("project-rules" to "same rules", "app-global" to "same rules"),
        )
        assertEquals(setOf("project-rules"), kept)
    }

    @Test
    fun whitespaceDifferencesStillCountAsDuplicates() {
        val kept = InstructionDedupe.firstSeen(
            listOf(
                "project-rules" to "  use\ttabs\n\nand spaces  ",
                "session-global" to "use tabs and spaces",
            ),
        )
        assertEquals(setOf("project-rules"), kept)
    }

    @Test
    fun nullAndBlankBodiesContributeNothing() {
        val kept = InstructionDedupe.firstSeen(
            listOf("project-rules" to null, "session-global" to "   ", "app-global" to "real"),
        )
        assertEquals(setOf("app-global"), kept)
    }

    @Test
    fun threeIdenticalLayersCollapseToOne() {
        val kept = InstructionDedupe.firstSeen(
            listOf(
                "project-rules" to "the rules",
                "session-global" to "the rules",
                "app-global" to "the rules",
            ),
        )
        assertEquals(1, kept.size)
        assertTrue(kept.contains("project-rules"))
    }

    @Test
    fun normalizeTrimsAndCollapses() {
        assertEquals("a b", InstructionDedupe.normalize("  a \n b  "))
        assertNull(InstructionDedupe.normalize("   \n\t "))
        assertNull(InstructionDedupe.normalize(null))
    }
}
