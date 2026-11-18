package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-standing-rules-not-memory] Standing rules are a user-set CONTRACT, not
 * memory content. The prompt builder used to gate the whole instruction block
 * behind the session memory toggle:
 *
 * ```kotlin
 * val sessionMem = if (memoryOn) sessionMemoryRepo() else null
 * val keptInstructionKeys = if (memoryOn) InstructionDedupe.firstSeen(…) else emptySet()
 * ```
 *
 * With memory off, `keptInstructionKeys` was empty, so the session-menu rules
 * and the project's `AGENTS.md` layer never reached the prompt — while Settings
 * kept displaying them as configured. That is "会话级规则失效" in its purest
 * form: the rule text had no path to the model at all.
 *
 * These tests pin the selection contract the fixed builder now runs
 * unconditionally: narrowest scope wins on identical bodies, distinct bodies
 * all survive, and blank scopes contribute nothing.
 */
class StandingRulesSelectionTest {

    @Test
    fun `all distinct scopes are kept`() {
        val kept = InstructionDedupe.firstSeen(
            listOf(
                "project-rules" to "always run the linter",
                "session-global" to "answer in Chinese",
                "app-global" to "never force-push",
            ),
        )
        assertEquals(setOf("project-rules", "session-global", "app-global"), kept)
    }

    @Test
    fun `identical bodies keep only the narrowest scope`() {
        val kept = InstructionDedupe.firstSeen(
            listOf(
                "project-rules" to "run the linter",
                "session-global" to "run the linter",
                "app-global" to "run the linter",
            ),
        )
        assertEquals(setOf("project-rules"), kept)
    }

    @Test
    fun `session rules survive when the project has none`() {
        // The regression case: memory off used to make this empty. With the
        // gate removed, a session rule with no competing scope is always kept.
        val kept = InstructionDedupe.firstSeen(
            listOf(
                "project-rules" to null,
                "session-global" to "UI 改动前先读 awesome-design-md",
                "app-global" to null,
            ),
        )
        assertTrue("session rules must reach the prompt", "session-global" in kept)
        assertEquals(setOf("session-global"), kept)
    }

    @Test
    fun `whitespace-only rules contribute nothing`() {
        val kept = InstructionDedupe.firstSeen(
            listOf(
                "project-rules" to "   \n\t ",
                "session-global" to "",
            ),
        )
        assertTrue(kept.isEmpty())
        assertFalse("session-global" in kept)
    }

    @Test
    fun `re-indented copy still counts as a duplicate`() {
        val kept = InstructionDedupe.firstSeen(
            listOf(
                "session-global" to "不要本地编译验证",
                "app-global" to "  不要本地编译验证\n\n",
            ),
        )
        assertEquals(setOf("session-global"), kept)
    }
}
