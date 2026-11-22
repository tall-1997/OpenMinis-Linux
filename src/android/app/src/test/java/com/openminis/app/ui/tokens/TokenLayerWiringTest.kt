package com.openminis.app.ui.tokens

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tokens-wire] The semantic token layer must actually reach the composition.
 *
 * `MinisTheme.colors` reads [LocalMinisColors], a `staticCompositionLocalOf`
 * whose default is `MinisColors.Light`. If nothing in the tree provides it, the
 * getter silently returns Light forever — dark mode reads light tokens, and
 * every screen that uses them looks "almost right", which is the hardest kind
 * of bug to notice. This is the mirror image of GH OpenMinis#126 (a provider
 * mounted with no consumer); the token layer shipped with the reverse problem
 * (consumers, no provider) for two commits.
 *
 * Static checks (`scripts/ci/check_composition_locals.py`) catch "declared but
 * never provided". This test pins the pairing from the other side: if someone
 * removes the `provides` from MinisTheme, or renames the local, the token
 * layer goes dead again — and that must fail here rather than in a screenshot
 * review that nobody does for dark mode.
 */
class TokenLayerWiringTest {

    private fun themeSource(): String {
        val f = File("src/main/java/com/openminis/app/ui/theme/Theme.kt")
        assertTrue("Theme.kt not found (cwd=${File(".").absolutePath})", f.isFile)
        return f.readText()
    }

    @Test
    fun `MinisTheme provides the semantic color local`() {
        val src = themeSource()
        assertTrue(
            "MinisTheme must provide LocalMinisColors, otherwise MinisTheme.colors " +
                "always returns MinisColors.Light and dark mode silently reads light tokens.",
            src.contains("LocalMinisColors provides"),
        )
    }

    @Test
    fun `MinisTheme provides the dimens local`() {
        val src = themeSource()
        assertTrue(
            "MinisTheme must provide LocalMinisDimens, otherwise Minis.dimens always " +
                "returns the default (non-compact) set.",
            src.contains("LocalMinisDimens provides"),
        )
    }

    @Test
    fun `provided colors follow the resolved darkTheme, not the system setting`() {
        val src = themeSource()
        // The token value must be chosen from `darkTheme` (the value Minis
        // resolved from the theme_mode preference), never from
        // isSystemInDarkTheme() — that is what GH OpenMinis#187 was about.
        assertTrue(
            "MinisColors must be selected from the resolved `darkTheme` flag so the " +
                "in-app Appearance setting wins over the OS setting.",
            src.contains("if (darkTheme) MinisColors.Dark else MinisColors.Light"),
        )
        // Only look at the argument of the `provides` call. A bare search of the
        // whole file would match unrelated call sites and turn this into a
        // test that fails for the wrong reason.
        val providesLine = src.lineSequence()
            .firstOrNull { it.contains("LocalMinisColors provides") }
        assertTrue(
            "LocalMinisColors provides … not found — cannot verify what feeds it.",
            providesLine != null,
        )
        assertTrue(
            "isSystemInDarkTheme() must not feed the token layer.",
            !providesLine!!.contains("isSystemInDarkTheme"),
        )
    }

    @Test
    fun `dark and light color sets are not identical`() {
        // Cheap sanity check that the generated pair is not a copy-paste of
        // itself. The generated file is checked in, so this runs without the
        // generator; check_token_semantics.py does the thorough version.
        val dark = MinisColors.Dark
        val light = MinisColors.Light
        assertTrue(
            "MinisColors.Dark is identical to MinisColors.Light — the dark theme " +
                "was never filled in (copy-paste or generator regression).",
            dark.background != light.background,
        )
        assertTrue(
            "MinisColors.Dark.surface == Light.surface — same problem.",
            dark.surface != light.surface,
        )
        assertTrue(
            "MinisColors.Dark.textPrimary == Light.textPrimary — dark text on dark " +
                "background is invisible.",
            dark.textPrimary != light.textPrimary,
        )
    }

    @Test
    fun `dark background is actually darker than light background`() {
        fun luminance(argb: Long): Double {
            fun channel(v: Long): Double {
                val s = v / 255.0
                return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            val r = channel((argb shr 16) and 0xFF)
            val g = channel((argb shr 8) and 0xFF)
            val b = channel(argb and 0xFF)
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }
        assertTrue(
            "Dark.background must be darker than Light.background.",
            luminance(MinisColors.Dark.background) < luminance(MinisColors.Light.background),
        )
        assertTrue(
            "Dark.textPrimary must be lighter than Light.textPrimary.",
            luminance(MinisColors.Dark.textPrimary) > luminance(MinisColors.Light.textPrimary),
        )
    }
}
