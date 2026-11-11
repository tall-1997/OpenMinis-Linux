package com.openminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#189/#266/#276] CJK runs must be typeset as `\text{}` text (KaTeX's own
 * fonts have no CJK glyphs), without double-wrapping text the model already
 * wrote as `\text{…}` and without touching formulas that contain no CJK.
 */
class MathCjkLayoutTest {

    @Test
    fun `plain cjk run is wrapped in text`() {
        assertEquals("\\text{中文} + x", MathCjkLayout.normalize("中文 + x"))
    }

    @Test
    fun `already text-wrapped cjk is left alone`() {
        assertEquals("\\text{中文}", MathCjkLayout.normalize("\\text{中文}"))
    }

    @Test
    fun `cjk inside a fraction is wrapped in place`() {
        assertEquals("\\frac{\\text{中文}}{2}", MathCjkLayout.normalize("\\frac{中文}{2}"))
    }

    @Test
    fun `ascii-only formulas are returned unchanged`() {
        val latex = "\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}"
        assertEquals(latex, MathCjkLayout.normalize(latex))
        assertFalse(MathCjkLayout.containsCjk(latex))
    }

    @Test
    fun `normalization is idempotent`() {
        val once = MathCjkLayout.normalize("x^2 + 中文^3")
        assertEquals(once, MathCjkLayout.normalize(once))
        assertTrue(once.contains("\\text{中文}"))
    }

    @Test
    fun `render cache key is normalization invariant`() {
        // MarkdownMathFlow reads the cache with the span's RAW latex while the
        // renderer writes it with the normalized form; both must agree or a CJK
        // formula stays stuck in the split-Column warming fallback.
        val raw = "E = mc^2 + 中文"
        val normalized = MathCjkLayout.normalize(raw)
        assertEquals(
            KaTeXRendererCache.cacheKey(raw, displayMode = false, isDark = true),
            KaTeXRendererCache.cacheKey(normalized, displayMode = false, isDark = true),
        )
    }
}
