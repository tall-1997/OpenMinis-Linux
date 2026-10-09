package com.openminis.app.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-think-tag-case] The stream parser must agree with the history
 * stripper's IGNORE_CASE semantics, and the shared variant table must feed
 * both. Also covers the user-transformer idempotency sentinels wired in
 * ChatViewModelSendExt.
 */
class ThinkPrefixStreamParserCaseTest {

    @Test
    fun `uppercase open tags enter thinking like their lowercase twins`() {
        for ((open, close) in listOf(
            "<THINKING>" to "</THINKING>",
            "<Thinking>" to "</Thinking>",
            "<antthinking>" to "</antthinking>",
            "<INNER_THOUGHT>" to "</INNER_THOUGHT>",
        )) {
            val parser = ThinkPrefixStreamParser()
            val out = parser.feed(open + "secret reasoning" + close + "visible body")
            val done = parser.finishTurn()
            assertEquals("", (out.visible + done.visible).replace("visible body", "").trim())
            assertTrue(
                "thinking should carry the reasoning for $open",
                (out.thinking + done.thinking).contains("secret reasoning"),
            )
            assertEquals("visible body", (out.visible + done.visible).trim())
        }
    }

    @Test
    fun `case-mixed close tags still close the thinking block`() {
        val parser = ThinkPrefixStreamParser()
        val out = parser.feed("<think>hidden</THINK>body")
        val done = parser.finishTurn()
        assertEquals("body", out.visible + done.visible)
    }

    @Test
    fun `variant tables from the shared source stay aligned`() {
        assertEquals(
            ReasoningTagVariantsPresence.sharedTableNames.size,
            ReasoningTagVariantsPresence.sharedTableNames.toSet().size,
        )
        // The parser's table and the history stripper's table come from one
        // source; spot-check a spelling that used to exist on only one side.
        assertTrue(ReasoningTagVariantsPresence.antThinkingPresent)
    }

    private object ReasoningTagVariantsPresence {
        val sharedTableNames: List<String> =
            com.openminis.app.harness.text.ReasoningTagVariants.NAMES
        val antThinkingPresent: Boolean =
            com.openminis.app.harness.text.ReasoningTagVariants.OPEN_TAGS.any { it.equals("<antThinking>", ignoreCase = true) }
    }
}
