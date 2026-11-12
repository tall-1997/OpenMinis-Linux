package com.openminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#123] Cross-line math inside a blockquote used to drag the continuation
 * lines' `> ` markers into the LaTeX (`"\n> E = mc^2\n> "`), so KaTeX rendered
 * a literal `>`. Math is extracted BEFORE block parsing, which is why the
 * marker has to be stripped here.
 *
 * [GH#276] plus the single-letter inline case: `$x$` carries no LaTeX glyph and
 * used to be rejected as currency, leaking the dollar signs as text.
 */
class BlockquoteMathTest {

    private fun latexOf(md: String): List<String> =
        MarkdownParser.parseWithMath(md).mathSpans.map { it.latex }

    @Test
    fun `display dollars inside a blockquote drop the quote markers`() {
        val md = "> ${'$'}${'$'}\n> E = mc^2\n> ${'$'}${'$'}"
        val latex = latexOf(md)
        assertEquals(1, latex.size)
        val body = latex.single()
        assertTrue("quote marker leaked into LaTeX: <$body>", !body.contains(">"))
        assertTrue("formula body missing: <$body>", body.contains("E = mc^2"))
    }

    @Test
    fun `latex-style display inside a blockquote drops the quote markers`() {
        val md = "> \\[\n> \\frac{a}{b} = c\n> \\]"
        val latex = latexOf(md)
        assertEquals(1, latex.size)
        assertTrue("quote marker leaked into LaTeX: <${latex.single()}>", !latex.single().contains(">"))
    }

    @Test
    fun `a formula whose own lines start with a comparison operator is untouched`() {
        // Only the FIRST continuation line has no leading `>`; the rule must
        // then keep the body verbatim rather than treating it as a quote.
        val md = "${'$'}${'$'}\na > b\nx > y\n${'$'}${'$'}"
        val body = latexOf(md).single()
        assertTrue("legitimate '>' content was stripped: <$body>", body.contains("a > b"))
        assertTrue("legitimate '>' content was stripped: <$body>", body.contains("x > y"))
    }

    @Test
    fun `single-letter inline math is still recognized`() {
        val spans = MarkdownParser.parseWithMath("Let ${'$'}x${'$'} be the value.").mathSpans
        assertEquals(1, spans.size)
        assertEquals("x", spans.single().latex)
        assertFalse(spans.single().isBlock)
    }

    @Test
    fun `sum formula inline still recognized`() {
        val spans = MarkdownParser.parseWithMath("cost ${'$'}5 and ${'$'}x+y${'$'}").mathSpans
        // `$5` is currency; `$x+y$` is math.
        assertEquals(listOf("x+y"), spans.map { it.latex })
    }
}
