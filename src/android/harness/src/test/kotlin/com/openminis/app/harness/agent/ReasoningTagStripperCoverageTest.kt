package com.openminis.app.harness.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the reasoning-tag history stripper beyond the original chain
 * test: the shared variant table, code-fence protection, and the
 * unterminated-block fix (`.*$` crossing `<`). The streaming side is a
 * separate parser — these tests pin the PERSISTED-history path.
 */
class ReasoningTagStripperCoverageTest {

    private fun apply(text: String): String = MessageTransformerChain.apply(text)

    @Test
    fun `paired antThinking inner_thought scratchpad blocks are stripped from history`() {
        // [T-universal-think-tag-history] The three spellings the history
        // table used to miss while the stream parser knew them.
        assertEquals(
            "answer",
            apply("<antThinking>hidden</antThinking>answer"),
        )
        assertEquals(
            "answer",
            apply("<inner_thought>hidden</inner_thought>answer"),
        )
        assertEquals(
            "answer",
            apply("<scratchpad>hidden</scratchpad>answer"),
        )
    }

    @Test
    fun `unterminated thinking with a less-than sign inside is still stripped`() {
        // [T-think-tag-unterminated] `[^<]*$` bailed on the first `<`, leaking
        // the whole reasoning tail into the body when the model wrote a
        // comparison or a code line inside its unterminated thinking.
        val text = "<think>let me reason: a < b and x[0] < len" +
            " and more <details> of thought"
        assertEquals("", apply(text))
        val text2 = "ok<think>compare 1 < 2"
        assertEquals("ok", apply(text2))
    }

    @Test
    fun `prose explaining the tag inside inline code survives`() {
        // [T-think-tag-code-fence] The message is EXPLAINING the tag, not
        // using it. Inline-code spans are masked before matching.
        val text = "Use the `<think>` tag to mark reasoning."
        assertEquals(text, apply(text))
    }

    @Test
    fun `prose inside a fenced block survives`() {
        val fenced = "```html\n<think>not reasoning, just demo markup</think>\n```\ndone"
        assertEquals(fenced, apply(fenced))
    }

    @Test
    fun `real reasoning inside prose is still stripped when not fenced`() {
        assertEquals("body", apply("<thinking>tail</thinking>body"))
    }

    @Test
    fun `tilde fences are protected too`() {
        val fenced = "~~~\n<think>demo</think>\n~~~\nbody"
        assertEquals(fenced, apply(fenced))
    }

    @Test
    fun `unterminated fence keeps its tail protected`() {
        // CommonMark: an unclosed fence runs to end of text — everything
        // after the opener is code and must pass through verbatim.
        val text = "before\n```\n<think>demo <b>bold"
        assertEquals(text, apply(text))
    }
}
