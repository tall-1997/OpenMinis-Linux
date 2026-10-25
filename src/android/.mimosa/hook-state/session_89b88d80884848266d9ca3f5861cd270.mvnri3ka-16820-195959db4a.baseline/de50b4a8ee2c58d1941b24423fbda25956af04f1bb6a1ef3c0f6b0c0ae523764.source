package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentTokenBudgetTest {

    @Test
    fun `records weighted usage below limit keeps budget alive`() {
        val b = SubAgentTokenBudget(limitTokens = 1000)
        // 500 output * 1.0 + 1000 prefill * 0.1 = 600 weighted
        assertTrue(b.recordUsage(outputTokens = 500, prefillTokens = 1000))
        assertEquals(600L, b.usedTokens)
        assertEquals(400L, b.remainingTokens)
        assertFalse(b.exhausted)
    }

    @Test
    fun `exceeding limit returns false and is a hard stop`() {
        val b = SubAgentTokenBudget(limitTokens = 1000)
        assertTrue(b.recordUsage(outputTokens = 600, prefillTokens = 0))
        // 600 + 500 = 1100 > 1000 → exhausted
        assertFalse(b.recordUsage(outputTokens = 500, prefillTokens = 0))
        assertTrue(b.exhausted)
        // Usage was still recorded (Codex: "usage was recorded and budget is now exhausted")
        assertEquals(1100L, b.usedTokens)
    }

    @Test
    fun `exhausted stays exhausted on later calls`() {
        val b = SubAgentTokenBudget(limitTokens = 100)
        assertFalse(b.recordUsage(outputTokens = 150, prefillTokens = 0))
        assertFalse(b.recordUsage(outputTokens = 10, prefillTokens = 0))
        assertTrue(b.exhausted)
    }

    @Test
    fun `cache-read prefill is discounted via caller math`() {
        val b = SubAgentTokenBudget(limitTokens = 10_000)
        // input 8000, cacheRead 6000 → prefill 2000; output 100
        // weighted = 100 + 200 = 300
        assertTrue(b.recordUsage(outputTokens = 100, prefillTokens = 2000))
        assertEquals(300L, b.usedTokens)
    }

    @Test
    fun `negative usage is clamped to zero`() {
        val b = SubAgentTokenBudget(limitTokens = 1000)
        assertTrue(b.recordWeighted(-500))
        assertEquals(0L, b.usedTokens)
    }

    @Test
    fun `clamp bounds coordinator requests`() {
        assertEquals(SubAgentTokenBudget.DEFAULT_LIMIT_TOKENS, SubAgentTokenBudget.clamp(null))
        assertEquals(SubAgentTokenBudget.DEFAULT_LIMIT_TOKENS, SubAgentTokenBudget.clamp(0L))
        assertEquals(SubAgentTokenBudget.DEFAULT_LIMIT_TOKENS, SubAgentTokenBudget.clamp(-5L))
        assertEquals(1_000L, SubAgentTokenBudget.clamp(10L))
        assertEquals(SubAgentTokenBudget.MAX_LIMIT_TOKENS, SubAgentTokenBudget.clamp(99_999_999L))
        assertEquals(50_000L, SubAgentTokenBudget.clamp(50_000L))
    }

    @Test
    fun `zero or negative limit is rejected`() {
        try {
            SubAgentTokenBudget(limitTokens = 0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
