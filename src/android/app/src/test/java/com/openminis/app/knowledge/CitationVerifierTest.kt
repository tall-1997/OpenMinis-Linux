package com.openminis.app.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-m8-knowledge-workbench] Citation checking for the AI answer box in the
 * knowledge detail page. The model receives numbered segments [1]..[k] and
 * must cite them; out-of-range citations trigger a correction prompt instead
 * of silently showing a wrong source.
 */
class CitationVerifierTest {

    @Test
    fun `single valid citation is ok`() {
        assertEquals(
            CitationVerifier.Check.Ok(listOf(1)),
            CitationVerifier.verify("see [1]", segmentCount = 3),
        )
    }

    @Test
    fun `multiple separate citations are ok`() {
        assertEquals(
            CitationVerifier.Check.Ok(listOf(1, 2)),
            CitationVerifier.verify("a [1] and b [2]", segmentCount = 3),
        )
    }

    @Test
    fun `comma list citation is ok`() {
        assertEquals(
            CitationVerifier.Check.Ok(listOf(1, 2)),
            CitationVerifier.verify("see [1,2]", segmentCount = 3),
        )
    }

    @Test
    fun `out of range citation is flagged`() {
        assertEquals(
            CitationVerifier.Check.OutOfRange(bad = listOf(5), citations = listOf(5)),
            CitationVerifier.verify("see [5]", segmentCount = 3),
        )
    }

    @Test
    fun `mixed citations report only the bad ones`() {
        assertEquals(
            CitationVerifier.Check.OutOfRange(bad = listOf(9), citations = listOf(2, 9)),
            CitationVerifier.verify("ok [2] but not [9]", segmentCount = 3),
        )
    }

    @Test
    fun `zero is an invalid citation number`() {
        assertEquals(
            CitationVerifier.Check.OutOfRange(bad = listOf(0), citations = listOf(0)),
            CitationVerifier.verify("see [0]", segmentCount = 3),
        )
    }

    @Test
    fun `no citations at all`() {
        assertEquals(
            CitationVerifier.Check.None,
            CitationVerifier.verify("no sources mentioned", segmentCount = 3),
        )
    }

    @Test
    fun `empty reply is none`() {
        assertEquals(CitationVerifier.Check.None, CitationVerifier.verify("", segmentCount = 3))
    }

    @Test
    fun `markdown links are not citations`() {
        assertEquals(
            CitationVerifier.Check.None,
            CitationVerifier.verify("see [docs](https://example.com)", segmentCount = 3),
        )
    }

    @Test
    fun `zero segments makes every citation out of range`() {
        assertEquals(
            CitationVerifier.Check.OutOfRange(bad = listOf(1), citations = listOf(1)),
            CitationVerifier.verify("see [1]", segmentCount = 0),
        )
    }

    @Test
    fun `duplicate citation numbers collapse`() {
        assertEquals(
            CitationVerifier.Check.Ok(listOf(1)),
            CitationVerifier.verify("a [1] b [1]", segmentCount = 3),
        )
    }

    @Test
    fun `correction prompt names the bad numbers and the range`() {
        val prompt = CitationVerifier.correctionPrompt(bad = listOf(5, 9), segmentCount = 3)
        assertTrue(prompt.contains("5"))
        assertTrue(prompt.contains("9"))
        assertTrue(prompt.contains("3"))
    }
}
