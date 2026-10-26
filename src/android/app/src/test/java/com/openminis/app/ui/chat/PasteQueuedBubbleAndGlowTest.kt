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
 * inert below 85%, ramping to full at 98%, rotation accelerating in three
 * quantised bands.
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
    fun `rotation accelerates in three bands`() {
        assertEquals(COMPOSER_GLOW_PERIOD_CALM_MS, composerGlowPeriodMs(0.0f))
        assertEquals(COMPOSER_GLOW_PERIOD_CALM_MS, composerGlowPeriodMs(0.89f))
        assertEquals(COMPOSER_GLOW_PERIOD_FAST_MS, composerGlowPeriodMs(0.90f))
        assertEquals(COMPOSER_GLOW_PERIOD_FAST_MS, composerGlowPeriodMs(0.94f))
        assertEquals(COMPOSER_GLOW_PERIOD_URGENT_MS, composerGlowPeriodMs(0.95f))
        assertEquals(COMPOSER_GLOW_PERIOD_URGENT_MS, composerGlowPeriodMs(1.0f))
        assertTrue(COMPOSER_GLOW_PERIOD_CALM_MS > COMPOSER_GLOW_PERIOD_FAST_MS)
        assertTrue(COMPOSER_GLOW_PERIOD_FAST_MS > COMPOSER_GLOW_PERIOD_URGENT_MS)
    }
}
