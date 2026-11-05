package com.openminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-markdown-inline-html] Coverage for the simple inline-HTML normalization
 * pass that runs before block parsing. Models frequently emit `<br>` and
 * `<b>/<i>/<code>` spans; the renderer has no WebView, so the pass maps that
 * trivial subset onto the Markdown syntax the inline parser already handles
 * and passes everything else through verbatim.
 *
 * Contracts pinned here:
 *  1. `<br>` variants (any casing, optional space, optional slash) become a
 *     line break;
 *  2. `<b>/<strong>`, `<i>/<em>`, `<code>` map to bold/italic/code markers;
 *  3. fenced code and inline code spans are never rewritten;
 *  4. table rows keep their shape — a cell `<br>` degrades to a space instead
 *     of a newline that would split the row;
 *  5. unknown tags pass through so no content is ever swallowed.
 */
class MarkdownInlineHtmlTest {

    // -- 1. <br> variants --

    @Test
    fun `br variants all become a newline`() {
        for (tag in listOf("<br>", "<br/>", "<br />", "<Br>", "<BR/>")) {
            assertEquals("failed for $tag", "a\nb", MarkdownInlineHtml.normalize("a${tag}b"))
        }
    }

    @Test
    fun `br breaks a paragraph into two rendered lines`() {
        val blocks = MarkdownParser.parseWithMath("line one<br>line two").blocks
        assertEquals(1, blocks.size)
        val para = blocks.single() as MarkdownParser.Block.Paragraph
        assertEquals("line one\nline two", para.content)
    }

    // -- 2. span mapping --

    @Test
    fun `b and strong map to bold markers`() {
        assertEquals("**x**", MarkdownInlineHtml.normalize("<b>x</b>"))
        assertEquals("**x**", MarkdownInlineHtml.normalize("<strong>x</strong>"))
    }

    @Test
    fun `i and em map to italic markers`() {
        assertEquals("*x*", MarkdownInlineHtml.normalize("<i>x</i>"))
        assertEquals("*x*", MarkdownInlineHtml.normalize("<em>x</em>"))
    }

    @Test
    fun `code maps to backticks`() {
        assertEquals("`x`", MarkdownInlineHtml.normalize("<code>x</code>"))
    }

    @Test
    fun `mixed spans and br normalize together`() {
        assertEquals(
            "**b**\n*i* `c`",
            MarkdownInlineHtml.normalize("<b>b</b><br><i>i</i> <code>c</code>"),
        )
    }

    // -- 3. code is protected --

    @Test
    fun `br inside a fenced block stays literal`() {
        val md = "```\nhello<br>world\n```"
        assertEquals(md, MarkdownInlineHtml.normalize(md))
    }

    @Test
    fun `tags inside an inline code span stay literal`() {
        assertEquals("a `<b>x</b>` b", MarkdownInlineHtml.normalize("a `<b>x</b>` b"))
    }

    // -- 4. table rows keep their shape --

    @Test
    fun `br inside a table cell degrades to a space`() {
        val md = "| a<br>b | c |\n|---|---|\n| d | e |"
        assertEquals("| a b | c |\n|---|---|\n| d | e |", MarkdownInlineHtml.normalize(md))
    }

    @Test
    fun `table with cell br still parses as a table`() {
        val blocks = MarkdownParser.parseWithMath("| a<br>b | c |\n|---|---|\n| d | e |").blocks
        assertTrue(blocks.first() is MarkdownParser.Block.Table)
    }

    // -- 5. unknown tags pass through --

    @Test
    fun `unknown tags pass through verbatim with their content`() {
        val md = "<u>x</u> and <sub>y</sub> <span class=\"k\">z</span>"
        assertEquals(md, MarkdownInlineHtml.normalize(md))
    }

    @Test
    fun `known tag with attributes still maps so the pair stays consistent`() {
        assertEquals("**bold**", MarkdownInlineHtml.normalize("<b class=\"x\">bold</b>"))
    }

    @Test
    fun `unclosed known tag still maps to its marker`() {
        assertEquals("**x", MarkdownInlineHtml.normalize("<b>x"))
    }

    @Test
    fun `plain markdown without angle brackets is untouched`() {
        val md = "**bold** *it* `c` [l](u) ${'$'}x^2${'$'}"
        assertEquals(md, MarkdownInlineHtml.normalize(md))
    }
}
