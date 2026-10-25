package com.openminis.app.provider

import com.openminis.app.provider.anthropic.AnthropicProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Anthropic system-prompt cache breakpoint layout.
 *
 * The regression these guard: the breakpoint used to sit at the end of the
 * whole system prompt, whose tail changes every turn (WorldBook hits, recall,
 * runtime context), so the cached prefix was rewritten each turn and never
 * read back across turns. It must sit at the end of the byte-stable head.
 */
class SystemPromptCacheLayoutTest {
    private val stable = "STABLE-HEAD-" + "x".repeat(200)
    private val dynamic = "DYNAMIC-TAIL"
    private val prompt = stable + dynamic

    @Test
    fun apiKeySplitsStableCachedFromDynamicUncached() {
        val blocks =
            AnthropicProvider.systemCacheBlocks(prompt, stable.length, isOAuth = false, claudeCodePrefix = "")
        assertEquals(2, blocks.size)
        assertEquals(stable, blocks[0].text)
        assertTrue("stable head must carry the breakpoint", blocks[0].cached)
        assertEquals(dynamic, blocks[1].text)
        assertFalse("per-turn tail must not carry a breakpoint", blocks[1].cached)
    }

    @Test
    fun missingOrInvalidLengthFallsBackToSingleCachedBlock() {
        for (len in listOf(-1, 0, prompt.length, prompt.length + 5)) {
            val blocks =
                AnthropicProvider.systemCacheBlocks(prompt, len, isOAuth = false, claudeCodePrefix = "")
            assertEquals("len=$len", 1, blocks.size)
            assertEquals(prompt, blocks[0].text)
            assertTrue(blocks[0].cached)
        }
    }

    @Test
    fun oauthKeepsPrefixFirstAndUncachedThenStableCachedThenDynamic() {
        val prefix = "CLAUDE-CODE-PREFIX\n"
        val full = prefix + stable + dynamic
        val blocks =
            AnthropicProvider.systemCacheBlocks(
                full,
                (prefix + stable).length,
                isOAuth = true,
                claudeCodePrefix = prefix,
            )
        assertEquals(3, blocks.size)
        assertEquals(prefix, blocks[0].text)
        assertFalse("OAuth prefix block must stay uncached", blocks[0].cached)
        assertEquals(stable, blocks[1].text)
        assertTrue(blocks[1].cached)
        assertEquals(dynamic, blocks[2].text)
        assertFalse(blocks[2].cached)
    }

    @Test
    fun oauthWithoutSplitMatchesLegacyTwoBlockShape() {
        val prefix = "P\n"
        val full = prefix + stable
        val blocks =
            AnthropicProvider.systemCacheBlocks(full, -1, isOAuth = true, claudeCodePrefix = prefix)
        assertEquals(2, blocks.size)
        assertEquals(prefix, blocks[0].text)
        assertFalse(blocks[0].cached)
        assertEquals(stable, blocks[1].text)
        assertTrue(blocks[1].cached)
    }

    @Test
    fun blankDynamicYieldsNoThirdBlock() {
        val blocks =
            AnthropicProvider.systemCacheBlocks(stable + "\n\n", stable.length, isOAuth = false, claudeCodePrefix = "")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].cached)
    }

    @Test
    fun atMostOneCachedSystemBlockSoTheFourBreakpointBudgetHolds() {
        // system(<=1) + last tool(1) + last two user messages(2) must stay <= 4.
        val blocks =
            AnthropicProvider.systemCacheBlocks(prompt, stable.length, isOAuth = true, claudeCodePrefix = "P")
        assertTrue(blocks.count { it.cached } <= 1)
    }
}
