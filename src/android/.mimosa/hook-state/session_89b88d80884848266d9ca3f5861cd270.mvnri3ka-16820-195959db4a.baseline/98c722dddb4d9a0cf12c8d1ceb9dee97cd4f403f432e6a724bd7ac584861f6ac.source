package com.openminis.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [T-android-monet-dynamic-color] The RESTYLE contract, not the framework
 * call. `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)`
 * need a real Context and only resolve on API 31+, so they are not unit-
 * testable here. What IS testable — and what actually matters — is the chat
 * palette mapping: the whole app restyles from the wallpaper scheme except
 * the slots that carry semantics (terminal-green code blocks, warning orange,
 * blockquote bar, dark-mode elevation shadow).
 *
 * The wallpaper fixture is deliberately nothing like Minis' desaturated blue,
 * so a leak from either side is unambiguous.
 */
class DynamicColorPaletteTest {

    private fun wallpaperScheme(dark: Boolean): ColorScheme {
        val amber = Color(0xFFB58500)
        return if (dark) {
            darkColorScheme(
                primary = amber,
                onPrimary = Color.Black,
                primaryContainer = Color(0xFF3A3000),
                onPrimaryContainer = Color(0xFFFFE08D),
                background = Color(0xFF141218),
                onSurface = Color(0xFFE6E1E5),
                onSurfaceVariant = Color(0xFFCAC4D0),
                outline = Color(0xFF948F99),
                outlineVariant = Color(0xFF49454E),
                surfaceContainerLowest = Color(0xFF0F0D13),
                surfaceContainerLow = Color(0xFF1F1D23),
                surfaceContainer = Color(0xFF211F26),
                surfaceContainerHigh = Color(0xFF2B2930),
            )
        } else {
            lightColorScheme(
                primary = amber,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFFFE08D),
                onPrimaryContainer = Color(0xFF231A00),
                background = Color(0xFFFFFBF4),
                onSurface = Color(0xFF1C1B1F),
                onSurfaceVariant = Color(0xFF49454F),
                outline = Color(0xFF79747E),
                outlineVariant = Color(0xFFCAC4D0),
                surfaceContainerLowest = Color(0xFFFFFDF8),
                surfaceContainerLow = Color(0xFFF7F2EC),
                surfaceContainer = Color(0xFFF3EDE7),
                surfaceContainerHigh = Color(0xFFECE6E0),
            )
        }
    }

    @Test
    fun `chat palette follows the wallpaper scheme end to end`() {
        val scheme = wallpaperScheme(dark = false)
        val merged = dynamicChatPalette(scheme, LightChatPalette)
        // Surfaces…
        assertEquals(scheme.background, merged.background)
        assertEquals(scheme.surfaceContainerLow, merged.secondaryBg)
        assertEquals(scheme.surfaceContainerLowest, merged.inputBg)
        assertEquals(scheme.surfaceContainerHigh, merged.inputIconBg)
        assertEquals(scheme.surfaceContainer, merged.toolBg)
        assertEquals(scheme.surfaceContainerLow, merged.toolCapsuleBg)
        assertEquals(scheme.surfaceContainerLow, merged.inlineCodeBg)
        assertEquals(scheme.surfaceContainerLow, merged.sheetHeaderBg)
        // …text pairs come with the scheme…
        assertEquals(scheme.onSurface, merged.primaryText)
        assertEquals(scheme.onSurfaceVariant, merged.secondaryText)
        assertEquals(scheme.outline, merged.tertiaryText)
        // …and the accents pair by construction.
        assertEquals(scheme.primaryContainer, merged.userBubble)
        assertEquals(scheme.primary, merged.sendButton)
        assertEquals(scheme.primary, merged.link)
        assertEquals(scheme.primary, merged.thinking)
        assertEquals(scheme.primary.copy(alpha = 0.18f), merged.toastBg)
        assertEquals(scheme.primaryContainer, merged.fabAccent)
        assertEquals(scheme.onPrimaryContainer, merged.fabOnAccent)
        // Chrome derives from the scheme's outline ramp.
        assertEquals(scheme.outlineVariant, merged.separator)
        assertEquals(scheme.outlineVariant.copy(alpha = 0.5f), merged.toolBorder)
    }

    @Test
    fun `semantic and identity slots stay with Minis`() {
        val scheme = wallpaperScheme(dark = true)
        val merged = dynamicChatPalette(scheme, DarkChatPalette)
        // Terminal-green code identity…
        assertEquals(DarkChatPalette.codeBlockBg, merged.codeBlockBg)
        assertEquals(DarkChatPalette.codeBlockText, merged.codeBlockText)
        assertEquals(DarkChatPalette.inlineCodeText, merged.inlineCodeText)
        // …semantic hues…
        assertEquals(DarkChatPalette.blockquoteBar, merged.blockquoteBar)
        assertEquals(DarkChatPalette.warningBg, merged.warningBg)
        assertEquals(DarkChatPalette.warningText, merged.warningText)
        // …and the dark-mode elevation shadow.
        assertEquals(DarkChatPalette.inputShadow, merged.inputShadow)
        // Mode marker survives the copy.
        assertEquals(true, merged.isDark)
    }

    @Test
    fun `off-path palettes stay byte-identical`() {
        // dynamicChatPalette and the wallpaper scheme only run behind the
        // Android 12 gate; when they do not run, the palettes are the statics.
        // Pinned so a future "always derive" shortcut has to argue with it.
        assertEquals(Color.White, LightChatPalette.fabOnAccent)
        assertEquals(Color.White, DarkChatPalette.fabOnAccent)
        assertEquals(Color(0xFFB7AF96), LightChatPalette.fabAccent)
        assertEquals(Color(0xFF504C42), DarkChatPalette.fabAccent)
        assertNotEquals(LightChatPalette.background, DarkChatPalette.background)
    }
}
