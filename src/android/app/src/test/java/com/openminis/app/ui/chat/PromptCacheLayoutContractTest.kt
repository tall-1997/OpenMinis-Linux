package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prompt-cache-turn-context] Source-guard for the cache layout.
 *
 * The whole point of the refactor is structural: the system prompt must not
 * contain per-turn content, because a per-turn byte inside `system` sits before
 * the conversation and invalidates every provider's prefix cache (OpenAI /
 * DeepSeek re-ingest all messages; Anthropic's message breakpoints include that
 * block). A future edit that appends "just one small dynamic fragment" back
 * into buildSystemPrompt would silently undo the fix, so this test pins the
 * separation by symbol.
 */
class PromptCacheLayoutContractTest {

    private val promptSource =
        ProductionSources.read("ui/chat/ChatViewModelPromptExt.kt")
    private val historySource =
        ProductionSources.read("ui/chat/ChatViewModelHistoryExt.kt")
    private val prepSource =
        ProductionSources.read("ui/chat/PromptRunPrep.kt")

    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("signature not found: $signature", start >= 0)
        val brace = source.indexOf('{', start)
        assertTrue("no body after: $signature", brace >= 0)
        var depth = 0
        var i = brace
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(brace + 1, i)
                }
            }
            i++
        }
        error("unbalanced braces after $signature")
    }

    @Test
    fun `buildSystemPrompt carries no per-turn fragment`() {
        val body = bodyOf(promptSource, "fun ChatViewModel.buildSystemPrompt()")
        // Stable inputs only.
        assertTrue(body.contains("skillPromptFragment"))
        assertTrue(body.contains("mcpPromptFragment"))
        // The whole system prompt is cached (single block).
        assertTrue(body.contains("systemPromptStablePrefixLen = -1"))
        // Per-turn sources must NOT be consulted here.
        for (symbol in listOf(
            "assembleDynamicTail",
            "WorldBook.injection",
            "MemoryRecallEngine",
            "renderRuntimeContext",
            "taskAnchorFragment",
            "HistoryDigest.render",
            "selectSummaryChunks",
        )) {
            assertFalse(
                "per-turn symbol '$symbol' must not be in the stable system prompt",
                body.contains(symbol),
            )
        }
    }

    @Test
    fun `buildTurnContextFragment owns the per-turn fragments`() {
        val body = bodyOf(promptSource, "fun ChatViewModel.buildTurnContextFragment()")
        assertTrue(body.contains("assembleDynamicTail"))
        assertTrue(body.contains("WorldBook.injection"))
        assertTrue(body.contains("MemoryRecallEngine"))
        assertTrue(body.contains("renderRuntimeContext"))
        assertTrue(body.contains("HistoryDigest.render"))
        assertTrue(body.contains("selectSummaryChunks"))
    }

    @Test
    fun `request history attaches the turn context to the latest user turn`() {
        val body = bodyOf(historySource, "fun ChatViewModel.effectiveAgentHistory()")
        assertTrue(body.contains("applyTurnContext"))
        assertTrue(body.contains("promptCache.turnContext"))
        // The digest is part of the tail now, never a rewrite of the head.
        assertFalse(body.contains("HistoryDigest.inject"))
    }

    @Test
    fun `run prep builds the turn context once per run`() {
        assertTrue(prepSource.contains("prepareRunHistory("))
        assertTrue(prepSource.contains("buildTurnContextFragment()"))
        assertTrue(prepSource.contains("promptCache.turnContext"))
    }
}
