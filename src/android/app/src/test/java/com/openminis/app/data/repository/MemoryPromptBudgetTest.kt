package com.openminis.app.data.repository

import com.openminis.app.data.repository.MemoryPromptBudget.Fragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-memory-prompt-budget] The budget's whole point is that the narrowest rule
 * survives a crowded prompt. These pin the admission order, the truncation rule
 * and the byte-stability of the assembled block.
 */
class MemoryPromptBudgetTest {

    private fun fragment(key: String, size: Int, priority: Int, truncatable: Boolean = false) =
        Fragment(key, "x".repeat(size), priority, truncatable)

    @Test
    fun narrowestScopeWinsWhenTheBudgetIsTight() {
        val result = MemoryPromptBudget.allocate(
            listOf(
                fragment("daily", 600, MemoryPromptBudget.PRIORITY_DAILY, truncatable = true),
                fragment("app-global", 600, MemoryPromptBudget.PRIORITY_APP, truncatable = true),
                fragment("session-global", 600, MemoryPromptBudget.PRIORITY_SESSION),
            ),
            budgetChars = 1_000,
        )
        assertTrue("project/session rules must survive", "session-global" in result.included.map { it.key })
        assertTrue("daily is the first thing dropped", "daily" in result.droppedKeys)
    }

    @Test
    fun truncatableFragmentIsKeptAsHeadWhenItCannotFit() {
        val result = MemoryPromptBudget.allocate(
            listOf(fragment("daily", 5_000, MemoryPromptBudget.PRIORITY_DAILY, truncatable = true)),
            budgetChars = 1_000,
        )
        assertEquals(listOf("daily"), result.included.map { it.key })
        assertTrue(result.included.single().text.endsWith(MemoryPromptBudget.TRUNCATION_MARKER))
        assertEquals(listOf("daily"), result.truncatedKeys)
        assertTrue("never exceed the budget", result.usedChars <= 1_000)
    }

    @Test
    fun nonTruncatableFragmentIsDroppedRatherThanMangled() {
        val result = MemoryPromptBudget.allocate(
            listOf(fragment("session-global", 5_000, MemoryPromptBudget.PRIORITY_SESSION)),
            budgetChars = 1_000,
        )
        assertTrue(result.included.isEmpty())
        assertEquals(listOf("session-global"), result.droppedKeys)
    }

    @Test
    fun outputKeepsTheCallersOrderForPromptCacheStability() {
        val result = MemoryPromptBudget.allocate(
            listOf(
                fragment("app-global", 10, MemoryPromptBudget.PRIORITY_APP),
                fragment("project-rules", 10, MemoryPromptBudget.PRIORITY_PROJECT),
                fragment("session-global", 10, MemoryPromptBudget.PRIORITY_SESSION),
            ),
            budgetChars = 1_000,
        )
        assertEquals(
            listOf("app-global", "project-rules", "session-global"),
            result.included.map { it.key },
        )
    }

    @Test
    fun blankFragmentsNeverTakeBudget() {
        val result = MemoryPromptBudget.allocate(
            listOf(Fragment("empty", "   ", MemoryPromptBudget.PRIORITY_SESSION, false)),
            budgetChars = 100,
        )
        assertNull(result.rendered)
        assertEquals(0, result.usedChars)
    }

    @Test
    fun everythingFitsWhenTheBudgetIsGenerous() {
        val result = MemoryPromptBudget.allocate(
            listOf(
                fragment("project-rules", 100, MemoryPromptBudget.PRIORITY_PROJECT),
                fragment("session-global", 100, MemoryPromptBudget.PRIORITY_SESSION),
            ),
            budgetChars = MemoryPromptBudget.DEFAULT_BUDGET_CHARS,
        )
        assertTrue(result.droppedKeys.isEmpty())
        assertTrue(result.truncatedKeys.isEmpty())
        assertEquals(200, result.usedChars)
    }

    @Test
    fun describeNamesEveryDecision() {
        val result = MemoryPromptBudget.allocate(
            listOf(
                fragment("project-rules", 100, MemoryPromptBudget.PRIORITY_PROJECT),
                fragment("daily", 900, MemoryPromptBudget.PRIORITY_DAILY, truncatable = true),
            ),
            // 700 leaves the daily fragment ~570 chars of head, above the
            // minimum worth keeping, so it is truncated rather than dropped.
            budgetChars = 700,
        )
        val text = result.describe()
        assertTrue(text.contains("kept=project-rules"))
        assertTrue(text.contains("truncated=daily"))
    }
}
