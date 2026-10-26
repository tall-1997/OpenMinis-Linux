package com.openminis.app.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
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

    @Test
    fun `settings menu hues resolve to the wallpaper roles when dynamic color is on`() {
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
            assertEquals(amberWallpaper.primary, resolved.tint)
            assertEquals(amberWallpaper.primaryContainer, resolved.tile)
            assertEquals(amberWallpaper.onPrimaryContainer, resolved.content)
        }
    }

    @Test
    fun `session category hues resolve through the same table when dynamic color is on`() {
        // Mirrors SessionListScreen.categoryStyle minus the deliberate statics.
        val sessionHues = listOf(
            Color(0xFFF09A37), // code, travel
            Color(0xFF3478F6), // writing, education
            Color(0xFF30B0C7), // research
            Color(0xFF5856D6), // analysis
            Color(0xFFFF2D55), // creative, design
            Color(0xFF34C759), // chat
            Color(0xFF9B59B6), // math
            Color(0xFF00BCD4), // translation
            Color(0xFF00C7BE), // finance
            Color(0xFFFFCC00), // productivity
            Color(0xFF8B6914), // support
        )
        for (hue in sessionHues) {
            val resolved = resolve(hue, dynamicActive = true)
            assertEquals("tint for $hue", amberWallpaper.primary, resolved.tint)
            assertEquals("tile for $hue", amberWallpaper.primaryContainer, resolved.tile)
            assertEquals("content for $hue", amberWallpaper.onPrimaryContainer, resolved.content)
        }
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
