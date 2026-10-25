package com.openminis.app.diagnostics

import com.openminis.app.text.BoundedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentDiagTest {

    @Test
    fun `empty text is a zero summary`() {
        val s = ContentDiag.summarize("")
        assertEquals(0, s.chars)
        assertEquals(0, s.lines)
        assertEquals(0, s.paragraphs)
    }

    @Test
    fun `small markdown is scanned in full`() {
        val text = """
            hello

            ```kotlin
            val x = 1
            ```

            | a | b |
            | --- | --- |
            | 1 | 2 |

            ${'$'}${'$'}E=mc^2${'$'}${'$'}
        """.trimIndent()
        val s = ContentDiag.summarize(text)
        assertEquals(text.length, s.chars)
        assertTrue(s.hasCodeBlock)
        assertTrue(s.hasTable)
        assertTrue(s.hasMath)
        assertTrue(s.paragraphs >= 1)
    }

    @Test
    fun `huge buffer reports full length but only windows ICU scans`() {
        val head = "```\ncode\n```\n"
        val tail = "\n| --- | --- |\n"
        val buriedFence = "\n~~~\nsecret-fence\n~~~\n"
        val text = head + "Q".repeat(60_000) + buriedFence + "Q".repeat(60_000) + tail
        val s = ContentDiag.summarize(text)
        assertEquals(text.length, s.chars)
        assertTrue("head fence must be visible in the window", s.hasCodeBlock)
        assertTrue("tail table sep must be visible in the window", s.hasTable)
        // The buried ~~~ in the unscanned middle must not inflate the count
        // beyond the head fence pair (``` open + ``` close → 1 block).
        assertEquals(1, s.codeBlockCount)
        assertTrue(text.length > BoundedText.MAX_CONTENT_DIAG_SCAN_CHARS)
    }

    @Test
    fun `paragraphs do not compile a Regex on the whole body`() {
        // A 40k body with three blank-line-separated paragraphs in the
        // windowed head. If summarize still did split(Regex) on the full
        // string this would be the 2026-09-18 Matcher.reset path.
        val text = "one\n\ntwo\n\nthree\n" + "x".repeat(40_000)
        val s = ContentDiag.summarize(text)
        assertEquals(text.length, s.chars)
        assertEquals(3, s.paragraphs)
        assertFalse(s.hasTable)
    }
}
