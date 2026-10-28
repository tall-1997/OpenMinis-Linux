package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-paste-queued-bubble] The painted content of a message whose
 * `[Pasted#N]` markers were folded out to disk must not keep the literal
 * markers — the paste renders as the standard tappable file chip instead.
 * Hand-typed markers for ids that were never folded stay verbatim.
 */
class PasteQueuedBubbleTest {

    @Test
    fun `strips every consumed marker and trims the gutter`() {
        val text = "look at this [Pasted#1] and [Pasted#2] what do you think"
        assertEquals(
            "look at this  and  what do you think",
            stripConsumedPasteMarkers(text, setOf(1, 2)),
        )
    }

    @Test
    fun `marker-only message becomes empty`() {
        assertEquals("", stripConsumedPasteMarkers("[Pasted#3]", setOf(3)))
    }

    @Test
    fun `id suffixes never collide`() {
        // Exact-literal replace on "[Pasted#2]" must not touch "[Pasted#20]":
        // the trailing ']' makes the match exact (same contract the composer
        // chip removal relies on).
        val text = "a [Pasted#2] b [Pasted#20] c"
        assertEquals("a  b [Pasted#20] c", stripConsumedPasteMarkers(text, setOf(2)))
    }

    @Test
    fun `unconsumed ids stay verbatim`() {
        // A hand-typed marker whose mediaRef does not exist is just text.
        val text = "hand-typed [Pasted#99] stays"
        assertEquals(text, stripConsumedPasteMarkers(text, emptySet()))
        assertEquals(
            "hand-typed [Pasted#99] stays",
            stripConsumedPasteMarkers(text, setOf(1)),
        )
    }
}

/**
 * [T-android-composer-context-glow] Thresholds for the composer edge glow:
 * inert below 85%, ramping to full at 98%, chase speed quantised in three
 * bands; the comet trail unrolls along the border perimeter and wraps the
 * path seam as pure geometry.
 */
class ComposerContextGlowTest {

    @Test
    fun `inert below the activate threshold`() {
        assertEquals(0f, composerGlowIntensity(0f), 1e-6f)
        assertEquals(0f, composerGlowIntensity(0.5f), 1e-6f)
        // Unknown window renders ratio 0 — must stay inert.
        assertEquals(0f, composerGlowIntensity(0f), 1e-6f)
    }

    @Test
    fun `ramps linearly between 85 and 98 percent`() {
        assertEquals(0.5f, composerGlowIntensity(0.915f), 0.01f)
        assertEquals(1f, composerGlowIntensity(0.98f), 1e-6f)
        assertEquals(1f, composerGlowIntensity(1.0f), 1e-6f)
    }

    @Test
    fun `chase speeds up in three bands`() {
        assertEquals(COMPOSER_GLOW_PERIOD_CALM_MS, composerGlowPeriodMs(0.0f))
        assertEquals(COMPOSER_GLOW_PERIOD_CALM_MS, composerGlowPeriodMs(0.89f))
        assertEquals(COMPOSER_GLOW_PERIOD_FAST_MS, composerGlowPeriodMs(0.90f))
        assertEquals(COMPOSER_GLOW_PERIOD_FAST_MS, composerGlowPeriodMs(0.94f))
        assertEquals(COMPOSER_GLOW_PERIOD_URGENT_MS, composerGlowPeriodMs(0.95f))
        assertEquals(COMPOSER_GLOW_PERIOD_URGENT_MS, composerGlowPeriodMs(1.0f))
        assertTrue(COMPOSER_GLOW_PERIOD_CALM_MS > COMPOSER_GLOW_PERIOD_FAST_MS)
        assertTrue(COMPOSER_GLOW_PERIOD_FAST_MS > COMPOSER_GLOW_PERIOD_URGENT_MS)
    }

    @Test
    fun `trail segments chase the head without wrapping`() {
        // Perimeter 100, head at 50, trail 30, three pieces: each covers a
        // tenth of the perimeter, brightest nearest the head.
        assertEquals(
            listOf(
                GlowTrailSegment(40f, 50f, 1f),
                GlowTrailSegment(30f, 40f, (2f / 3f) * (2f / 3f)),
                GlowTrailSegment(20f, 30f, (1f / 3f) * (1f / 3f)),
            ),
            composerGlowTrailSegments(50f, 30f, 100f, 3),
        )
    }

    @Test
    fun `trail wraps around the perimeter seam`() {
        // Head 10 px into a 100 px lap with a 30 px trail: the newest piece
        // runs 0..10, the tail wraps to 90..100, the middle sits 80..90. The
        // zero-length seam stub is dropped, so no piece crosses start > end.
        assertEquals(
            listOf(
                GlowTrailSegment(0f, 10f, 1f),
                GlowTrailSegment(90f, 100f, (2f / 3f) * (2f / 3f)),
                GlowTrailSegment(80f, 90f, (1f / 3f) * (1f / 3f)),
            ),
            composerGlowTrailSegments(10f, 30f, 100f, 3),
        )
    }

    @Test
    fun `trail clamped to the perimeter covers every pixel exactly once`() {
        // A 200 px trail on a 100 px lap clamps to the full perimeter: the
        // four pieces tile the path without overlap or gap.
        val segments = composerGlowTrailSegments(25f, 200f, 100f, 4)
        assertEquals(100.0, segments.sumOf { (it.end - it.start).toDouble() }, 1e-3)
        // Alpha tapers strictly from head (1.0) to tail (1/n²).
        assertEquals(listOf(1f, 0.5625f, 0.25f, 0.0625f), segments.map { it.alpha })
    }

    @Test
    fun `degenerate geometry yields no trail`() {
        assertTrue(composerGlowTrailSegments(0f, 30f, 0f, 7).isEmpty())
        assertTrue(composerGlowTrailSegments(0f, 0f, 100f, 7).isEmpty())
        assertTrue(composerGlowTrailSegments(0f, 30f, 100f, 0).isEmpty())
    }
}
