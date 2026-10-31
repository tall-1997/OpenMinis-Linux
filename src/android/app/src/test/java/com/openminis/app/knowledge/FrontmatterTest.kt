package com.openminis.app.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [P2-convergence] Alignment tests for the shared frontmatter reader now used
 * by both app knowledge packs (APP.md) and SOUL.md: the two hand-rolled scans
 * had to survive the merge byte-for-byte, tolerances included.
 */
class FrontmatterTest {

    @Test
    fun happyPathKeepsFieldOrderAndRawBody() {
        val fm = Frontmatter.parse(
            "---\nname: WeChat\ndisplay: \"WeChat\"\npackages: [\"com.tencent.mm\"]\n---\n\n# Body\nhello\n",
        )!!
        assertEquals(
            listOf("name" to "WeChat", "display" to "WeChat", "packages" to "[\"com.tencent.mm\"]"),
            fm.fields,
        )
        // Body is raw: leading blank line and markdown stay untouched here.
        assertEquals("\n# Body\nhello\n", fm.body)
        assertEquals("WeChat", fm.value("name"))
        assertEquals("WeChat", fm.value("display"))
    }

    @Test
    fun missingOpenAndUnterminatedFrontmatterReturnNull() {
        assertNull(Frontmatter.parse("name: x\nbody"))
        assertNull(Frontmatter.parse("---\nname: x\nno closing delimiter"))
        // Empty text does not open with ---
        assertNull(Frontmatter.parse(""))
    }

    @Test
    fun blankCommentAndColonlessLinesAreSkippedNotFatal() {
        val fm = Frontmatter.parse(
            "---\n\n# a comment\nmalformed line\ncolonless\nname: kept\n:startswithcolon\n---\nbody",
        )!!
        assertEquals(listOf("name" to "kept"), fm.fields)
        assertEquals("body", fm.body)
    }

    @Test
    fun valueTakesLastOccurrenceLikeTheOldLoops() {
        val fm = Frontmatter.parse("---\nname: first\nname: second\nname: \n---\nbody")!!
        // Last occurrence wins, even when it is empty — callers apply their own
        // emptiness rules on top (pack: ifEmpty→null; soul: keep old default).
        assertEquals("", fm.value("name"))
    }

    @Test
    fun firstColonSplitsSoDataUrisSurvive() {
        val fm = Frontmatter.parse("---\nicon: data:image/png;base64,AAA\n---\nbody")!!
        assertEquals("data:image/png;base64,AAA", fm.value("icon"))
    }

    @Test
    fun crlfIsNotNormalizedHereCallersOwnIt() {
        // SOUL.md round-trip depends on the reader not rewriting line endings.
        val fm = Frontmatter.parse("---\r\nname: x\r\n---\r\nbody line\r\n")!!
        assertEquals("x", fm.value("name"))
        assertEquals("body line\r\n", fm.body)
    }
}
