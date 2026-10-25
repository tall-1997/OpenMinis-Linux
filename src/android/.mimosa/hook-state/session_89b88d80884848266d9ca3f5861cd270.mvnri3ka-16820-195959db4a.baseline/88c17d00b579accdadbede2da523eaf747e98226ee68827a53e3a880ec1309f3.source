package com.openminis.app.data

import com.openminis.app.data.model.LLMError
import com.openminis.app.ui.chat.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactFallbackPolicyTest {
    @Test
    fun firstByteWatchdogSplitsButGenericFiveXxDoesNot() {
        assertTrue(
            ChatViewModel.shouldSplitOnError(
                LLMError.TransientError("no response from server (120s TTFB) — check network/proxy"),
            ),
        )
        assertFalse(ChatViewModel.shouldSplitOnError(LLMError.TransientError("502 Bad Gateway")))
        assertTrue(ChatViewModel.isFirstByteTimeout(LLMError.TransientError("no response headers")))
        assertFalse(ChatViewModel.isFirstByteTimeout(LLMError.TransientError("503")))
    }

    @Test
    fun twoShotKindsUseFallbackWhenConfiguredOtherwiseRetrySession() {
        assertEquals(
            listOf("session", "fallback"),
            ChatViewModel.compactStageKinds(hasFallbackDistinctFromSession = true),
        )
        assertEquals(
            listOf("session", "session-retry"),
            ChatViewModel.compactStageKinds(hasFallbackDistinctFromSession = false),
        )
        assertEquals(2, ChatViewModel.COMPACT_STAGE_BUDGET)
    }

    @Test
    fun segmentBudgetClampsAndTriggersOnlyWhenOverBudget() {
        assertEquals(8_000, ChatViewModel.compactSegmentTokenBudget(20_000))
        assertEquals(32_000, ChatViewModel.compactSegmentTokenBudget(1_000_000))
        assertEquals(25_600, ChatViewModel.compactSegmentTokenBudget(128_000))
        assertTrue(
            ChatViewModel.shouldProactivelySplit(
                messageCount = 40,
                estimatedTokens = 40_000,
                tokenBudget = 32_000,
                depth = 0,
                callsAlreadySpent = 0,
            ),
        )
        assertFalse(
            ChatViewModel.shouldProactivelySplit(
                messageCount = 40,
                estimatedTokens = 10_000,
                tokenBudget = 32_000,
                depth = 0,
                callsAlreadySpent = 0,
            ),
        )
        assertFalse(
            ChatViewModel.shouldProactivelySplit(
                messageCount = 40,
                estimatedTokens = 40_000,
                tokenBudget = 32_000,
                depth = 0,
                callsAlreadySpent = 5,
            ),
        )
    }
}
