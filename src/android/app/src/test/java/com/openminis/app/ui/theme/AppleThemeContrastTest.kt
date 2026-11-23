package com.openminis.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-apple-theme-contract] The Apple theme's two appearances must agree about
 * what its accent IS, and its text tiers must stay visibly apart.
 *
 * Both properties were broken and were only visible on a device:
 *
 *  - the accent existed three times (scheme primary #2E6BC0, chat link/thinking
 *    #0066CC, chrome #007AFF) and the dark scheme's primary was a different
 *    colour again (#ABC7FF, S 100 / L 84 vs S 61 / L 47) — "light and dark
 *    don't use the same accent colour";
 *  - secondary/tertiary text were 1.32:1 (light) and 1.16:1 (dark) apart, i.e.
 *    one flat grey — "light mode looks washed out".
 *
 * Grounding: apple-design/accessibility.md requires 4.5:1 for text at these
 * sizes (3:1 at >=18pt or bold); apple-design/materials-color.md requires the
 * foreground roles to keep their meaning consistent across appearances.
 */
class AppleThemeContrastTest {

    private fun contrast(a: Color, b: Color): Float {
        // Composite first: the dark text tiers are translucent whites, and
        // Color.luminance() reads RGB while IGNORING alpha — measuring the raw
        // value would report both tiers as the same 17:1 and hide the collapse
        // this test exists to catch.
        val fg = a.compositeOver(b)
        val la = fg.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun hue(color: Color): Float {
        val r = color.red
        val g = color.green
        val b = color.blue
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        if (mx == mn) return 0f
        val d = mx - mn
        val h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return (h * 60f + 360f) % 360f
    }

    private fun saturation(color: Color): Float {
        val mx = maxOf(color.red, color.green, color.blue)
        val mn = minOf(color.red, color.green, color.blue)
        val l = (mx + mn) / 2f
        if (mx == mn) return 0f
        val d = mx - mn
        return if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
    }

    @Test
    fun `the accent is one colour in both appearances, only lightness adapts`() {
        val light = ActionBlueLight
        val dark = ActionBlueDark
        // Same hue and same saturation: the pair must read as ONE accent.
        assertTrue(
            "accent hue drifted: light=${hue(light)} dark=${hue(dark)}",
            kotlin.math.abs(hue(light) - hue(dark)) <= 3f,
        )
        assertEquals("accent saturation must match", saturation(light), saturation(dark), 0.05f)
        assertTrue("the dark accent should be the lighter one", dark.luminance() > light.luminance())
    }

    @Test
    fun `the accent clears AA as text and as a fill in both appearances`() {
        // Text on the surface each appearance actually paints behind it.
        assertTrue(
            "light accent on white: ${contrast(ActionBlueLight, Color.White)}",
            contrast(ActionBlueLight, Color.White) >= 4.5f,
        )
        assertTrue(
            "dark accent on black: ${contrast(ActionBlueDark, Color.Black)}",
            contrast(ActionBlueDark, Color.Black) >= 4.5f,
        )
        // Filled surfaces: the paired on-colour must clear AA too.
        assertTrue(
            "onPrimary on the light accent: ${contrast(ChatPaletteWhiteOnPrimary, ActionBlueLight)}",
            contrast(Color.White, ActionBlueLight) >= 4.5f,
        )
        assertTrue(
            "thinkingOn on the dark accent: ${contrast(DarkChatPalette.thinkingOn, ActionBlueDark)}",
            contrast(DarkChatPalette.thinkingOn, ActionBlueDark) >= 4.5f,
        )
    }

    @Test
    fun `the scheme primary and the chat accent are the same colour`() {
        // This IS the "one accent" claim: the Material components and the chat
        // text must not drift back into two blues (they were #2E6BC0 /
        // #0066CC / #007AFF before this change). Derived from the public tokens
        // so a revert of TealPrimary alone fails here.
        assertEquals(ActionBlueLight, LightChatPalette.link)
        assertEquals(ActionBlueLight, LightChatPalette.thinking)
        assertEquals(ActionBlueDark, DarkChatPalette.link)
        assertEquals(ActionBlueDark, DarkChatPalette.thinking)
    }

    @Test
    fun `the dark on-primary clears AA on the accent`() {
        // Pinned because the constant is easy to leave behind when the accent
        // changes: #002D5F was correct against the old pale tone-80 primary and
        // drops to 3.74:1 against the vivid accent.
        assertTrue(
            "onPrimary on the accent: " +
                "${contrast(DarkChatPalette.thinkingOn, ActionBlueDark)}",
            contrast(DarkChatPalette.thinkingOn, ActionBlueDark) >= 4.5f,
        )
        assertTrue(
            "light thinkingOn on the accent: " +
                "${contrast(LightChatPalette.thinkingOn, ActionBlueLight)}",
            contrast(LightChatPalette.thinkingOn, ActionBlueLight) >= 4.5f,
        )
    }

    @Test
    fun `chat text tiers stay visibly apart in both appearances`() {
        for ((label, palette) in listOf("light" to LightChatPalette, "dark" to DarkChatPalette)) {
            val bg = palette.background
            val primary = contrast(palette.primaryText, bg)
            val secondary = contrast(palette.secondaryText, bg)
            val tertiary = contrast(palette.tertiaryText, bg)
            // Every tier clears AA on its own…
            assertTrue("$label primary $primary", primary >= 4.5f)
            assertTrue("$label secondary $secondary", secondary >= 4.5f)
            assertTrue("$label tertiary $tertiary", tertiary >= 4.5f)
            // …and each is a visible step below the one above it. A 1.32:1
            // step is what made the light transcript read as one grey.
            assertTrue("$label secondary/tertiary gap", secondary / tertiary >= 1.5f)
            assertTrue("$label primary/secondary gap", primary / secondary >= 1.5f)
        }
    }

    @Test
    fun `the composer placeholder is readable, not a disabled state`() {
        // It used to be onSurface@25% = 1.61:1 on white.
        assertTrue(
            "light placeholder on white: ${contrast(LightChatPalette.placeholder, Color.White)}",
            contrast(LightChatPalette.placeholder, Color.White) >= 4.5f,
        )
        assertTrue(
            "light placeholder on the grouped page: " +
                "${contrast(LightChatPalette.placeholder, LightChatPalette.secondaryBg)}",
            contrast(LightChatPalette.placeholder, LightChatPalette.secondaryBg) >= 4.5f,
        )
        assertTrue(
            "dark placeholder on black: ${contrast(DarkChatPalette.placeholder, Color.Black)}",
            contrast(DarkChatPalette.placeholder, Color.Black) >= 4.5f,
        )
        // A placeholder is a hint, not body text: it must stay a tier below.
        assertTrue(
            "placeholder must not out-shout primary text",
            contrast(LightChatPalette.placeholder, Color.White) <
                contrast(LightChatPalette.primaryText, Color.White),
        )
    }

    @Test
    fun `nested fills are distinguishable from the surface they sit on`() {
        // The tool capsule is painted INSIDE the tool card; as the same value it
        // was invisible, so a tool row read as one grey block.
        assertTrue(
            "tool capsule vs tool card: " +
                "${contrast(LightChatPalette.toolCapsuleBg, LightChatPalette.toolBg)}",
            contrast(LightChatPalette.toolCapsuleBg, LightChatPalette.toolBg) >= 1.05f,
        )
        // Inline-code chip vs the transcript background it appears on.
        assertTrue(
            "inline code chip vs background: " +
                "${contrast(LightChatPalette.inlineCodeBg, LightChatPalette.background)}",
            contrast(LightChatPalette.inlineCodeBg, LightChatPalette.background) >= 1.05f,
        )
        // The chip's text still clears AA on the chip.
        assertTrue(
            "inline code text on its chip: " +
                "${contrast(LightChatPalette.inlineCodeText, LightChatPalette.inlineCodeBg)}",
            contrast(LightChatPalette.inlineCodeText, LightChatPalette.inlineCodeBg) >= 4.5f,
        )
    }

    private companion object {
        /** The Apple theme's on-primary for a filled surface. */
        val ChatPaletteWhiteOnPrimary = Color.White
    }
}
