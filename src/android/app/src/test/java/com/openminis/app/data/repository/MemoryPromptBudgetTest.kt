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
    fun oversizedRulesBlockIsTruncatedWithTheRulesMarkerNotDropped() {
        // [T-rules-never-silently-vanish] The old non-truncatable path dropped
        // an oversized rules block wholesale with zero model-visible trace —
        // the user's session rules vanished from the prompt while every other
        // layer behaved as if they were there. Now the head ships with an
        // explicit rules-cut marker.
        val result = MemoryPromptBudget.allocate(
            listOf(fragment("session-global", 5_000, MemoryPromptBudget.PRIORITY_SESSION)),
            budgetChars = 1_000,
        )
        assertEquals(listOf("session-global"), result.included.map { it.key })
        assertTrue(
            "rules cut must carry the rules marker",
            result.included.single().text.endsWith(MemoryPromptBudget.RULES_TRUNCATION_MARKER),
        )
        assertEquals(listOf("session-global"), result.truncatedKeys)
        assertTrue("never exceed the budget", result.usedChars <= 1_000)
    }

    @Test
    fun hugeProjectRulesCanNeverEvictSessionRules() {
        // [T-rules-never-silently-vanish] Narrowest scope first: the session's
        // own rules are admitted BEFORE the project's, so a long project
        // AGENTS.md truncates (visibly) instead of displacing this chat's
        // rules — the documented intent the old priority numbers inverted.
        val result = MemoryPromptBudget.allocate(
            listOf(
                fragment("project-rules", 20_000, MemoryPromptBudget.PRIORITY_PROJECT),
                fragment("session-global", 600, MemoryPromptBudget.PRIORITY_SESSION),
            ),
            budgetChars = 2_000,
        )
        val keys = result.included.map { it.key }
        assertTrue("session rules must survive a crowded project file", "session-global" in keys)
        assertTrue("project rules are cut, not vanished", "project-rules" in result.truncatedKeys)
        assertTrue(result.included.first { it.key == "project-rules" }.text.endsWith(MemoryPromptBudget.RULES_TRUNCATION_MARKER))
    }

    @Test
    fun dropOnlyHappensWhenEvenAMeaningfulHeadDoesNotFit() {
        val result = MemoryPromptBudget.allocate(
            listOf(fragment("session-global", 5_000, MemoryPromptBudget.PRIORITY_SESSION)),
            // 100 chars leave a head below MIN_TRUNCATED_CHARS even after the
            // marker reservation — the one case where dropping is honest.
            budgetChars = 100,
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
