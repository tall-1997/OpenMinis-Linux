package com.openminis.app.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-dynamic-accent-icons] The accent bridge's mapping contract.
 *
 * `resolveMonetTile` is the pure core every icon-decoration entry point
 * ([monetIconTint], [monetIconTileColor], [monetIconTileContentColor]) is a
 * thin composition wrapper around, so the whole input -> output table is
 * testable on the JVM without a composable host. The wallpaper fixture is
 * deliberately nothing like the iOS palette it must never leak (same trick
 * as [DynamicColorPaletteTest]).
 *
 * [T-monet-harmonize] Since 2026-11-23 the dynamic branch HARMONIZES each
 * tile toward the wallpaper (35% RGB lerp) instead of collapsing all hues
 * onto the single primary family: the first implementation turned all
 * fifteen table hues into one color and deleted the settings-group and
 * session-category color coding. The tests below pin the harmonize contract:
 * every hue keeps a majority of itself, two different hues never collapse to
 * the same tint, and the tile/content pairing is luminance-based.
 */
class AccentTokensTest {

    private val amberWallpaper = lightColorScheme(
        primary = Color(0xFFB58500),
        onPrimary = Color.Black,
        primaryContainer = Color(0xFFFFE08D),
        onPrimaryContainer = Color(0xFF231A00),
    )

    // Sanity: the fixture shares no slot value with the iOS hues below, so a
    // static leak through the dynamic path is unambiguous.
    private val iosBlue = Color(0xFF007AFF)

    private fun resolve(static: Color, dynamicActive: Boolean) =
        resolveMonetTile(static, dynamicActive, amberWallpaper)

    /** WCAG 2.x ratio, so the pairing contract can be asserted directly. */
    private fun contrastRatioText(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    @Test
    fun `settings menu hues harmonize toward the wallpaper when dynamic color is on`() {
        val settingsHues = listOf(
            iosBlue,           // providers / model groups / usage / skills ...
            Color(0xFF34C759), // web search / terminal / backup
            Color(0xFFFF9500), // soul / plugin market / mounted folders
            Color(0xFF5856D6), // appearance / multi-agent / memory
            Color(0xFFAF52DE),
            Color(0xFF30B0C7), // mcp
            Color(0xFF5AC8FA),
        )
        for (hue in settingsHues) {
            val resolved = resolve(hue, dynamicActive = true)
            // Harmonized toward the scheme…
            assertEquals(lerp(hue, amberWallpaper.primary, 0.35f), resolved.tint)
            assertEquals(lerp(hue, amberWallpaper.primaryContainer, 0.35f), resolved.tile)
            // [T-monet-tile-pairing] Assert the CONTRACT (the glyph reads at the
            // WCAG 3:1 non-text floor), not a copy of the implementation. The
            // previous version mirrored the old `luminance() > 0.5` branch, so it
            // stayed green while nine of the fifteen hues shipped White at
            // 1.93–3.17:1.
            assertTrue(
                "glyph on the harmonized tile must reach 3:1, got " +
                    "${contrastRatioText(resolved.content, resolved.tile)} for $hue",
                contrastRatioText(resolved.content, resolved.tile) >= 3.0f,
            )
        }
    }

    @Test
    fun `distinct hues never collapse to one color under dynamic color`() {
        // The regression this pins: the first bridge mapped every table hue to
        // the SAME primaryContainer family, so the session list's category
        // circles all became one indistinguishable color.
        val a = resolve(iosBlue, dynamicActive = true)
        val b = resolve(Color(0xFF34C759), dynamicActive = true)
        val c = resolve(Color(0xFFFF9500), dynamicActive = true)
        val d = resolve(Color(0xFF5856D6), dynamicActive = true)
        val tints = listOf(a.tint, b.tint, c.tint, d.tint)
        tints.forEach { tint ->
            // Each keeps a majority of its own hue…
            assertNotEquals(amberWallpaper.primary, tint)
        }
        // …and they stay pairwise distinguishable.
        assertEquals(4, tints.toSet().size)
        assertEquals(4, listOf(a.tile, b.tile, c.tile, d.tile).toSet().size)
    }

    @Test
    fun `harmonized tint stays recognizable as the original hue`() {
        // 65% of the original survives the lerp: the green stays green, the
        // orange stays orange (channel-wise, not a hue-domain claim).
        val green = resolve(Color(0xFF34C759), dynamicActive = true).tint
        assertTrue("green must keep a green channel lead, got $green", green.green > green.red)
        val orange = resolve(Color(0xFFFF9500), dynamicActive = true).tint
        assertTrue("orange must keep a red channel lead, got $orange", orange.red > orange.blue)
    }

    @Test
    fun `red and gray keep their hue even when dynamic color is on`() {
        // Their hue IS the message: health red / destructive red, neutral gray.
        val semanticHues = listOf(Color(0xFFFF3B30), Color(0xFF8E8E93))
        for (hue in semanticHues) {
            val resolved = resolve(hue, dynamicActive = true)
            assertEquals(hue, resolved.tint)
            assertEquals(hue, resolved.tile)
            assertEquals(Color.White, resolved.content)
        }
    }

    @Test
    fun `gate off returns the static colors and never reads the scheme`() {
        val hues = listOf(
            iosBlue,
            Color(0xFF34C759),
            Color(0xFFFF9500),
            Color(0xFF5856D6),
            Color(0xFFFF3B30),
            Color(0xFF8E8E93),
            Color(0xFFF09A37),
            Color(0xFF3478F6),
        )
        for (hue in hues) {
            val resolved = resolve(hue, dynamicActive = false)
            assertEquals(hue, resolved.tint)
            assertEquals(hue, resolved.tile)
            assertEquals(Color.White, resolved.content)
        }
    }

    @Test
    fun `off-path tile pairing is pinned to the hardcoded White glyph`() {
        // The OFF rendering must stay byte-identical to the pre-bridge tiles:
        // static fill + White glyph (SettingsScreen row icon contract).
        val off = resolve(iosBlue, dynamicActive = false)
        assertEquals(iosBlue, off.tile)
        assertEquals(Color.White, off.content)
    }

    @Test
    fun `unknown hues pass through unchanged even when dynamic color is on`() {
        // Status feedback colors (success green check, error red, info blue
        // variants outside the table) are not chrome accents — they ride the
        // static path by default so new hardcoded hues fail safe.
        val unknown = Color(0xFF123456)
        val resolved = resolve(unknown, dynamicActive = true)
        assertEquals(unknown, resolved.tint)
        assertEquals(unknown, resolved.tile)
        assertEquals(Color.White, resolved.content)
    }
}
