package com.openminis.app.ui.theme

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * [T-cili-skin-gate] 小黄鱼 charcoal-pink skin runtime gate + literal bridge.
 *
 * The 2.1.12 reskin (53e67886) swept iOS-token hex literals across ~40 UI
 * files. Every swept literal is now `cili(baseArgb)`: the base is the
 * pre-reskin iOS hue, and this bridge returns the 小黄鱼 variant only while
 * the skin is active — dynamic color ON restores the base hue verbatim, so
 * wallpaper theming is never pink. MinisTheme sets [active] from
 * `!dynamicColor && darkTheme` on every composition.
 *
 * Base→skin pairs come from the 53e67886 sweep table (alpha is preserved
 * from the base argb; only the RGB half is remapped). Collapsed sources are
 * intentional: four iOS blues all resolve to the skin's FF7AD9.
 */
object CiliSkin {
    /** True only when dynamic color is OFF **and** dark mode is ON. Set by MinisTheme. */
    val active: MutableState<Boolean> = mutableStateOf(false)

    val isActive: Boolean get() = active.value

    fun color(base: Long): Color {
        if (!isActive) return Color(base)
        val rgb = (base and 0xFFFFFFL).toInt()
        val skin = SKIN[rgb] ?: rgb
        return Color((base and -0x1000000L) or (skin.toLong() and 0xFFFFFFL))
    }

    private val SKIN: Map<Int, Int> = mapOf(
        0x007AFF to 0xFF7AD9,
        0x0A84FF to 0xFF7AD9,
        0x30B0C7 to 0xFF7AD9,
        0x3478F6 to 0xFF7AD9,
        0x34C759 to 0x4CAF50,
        0xFF3B30 to 0xFF6B6B,
        0xFF2D55 to 0xE63C93,
        0x5856D6 to 0xE63C93,
        0xAF52DE to 0xA81E77,
        0xFF9500 to 0xF0A32B,
        0xF09A37 to 0xF0A32B,
        0x00BCD4 to 0xF0A32B,
        0x64D2FF to 0xFFD9E8,
        0x32ADE6 to 0xFFB3D1,
        0xFFCC00 to 0xFFD070,
        0x8E8E93 to 0xB9A3B0,
        0x16161A to 0x160C13,
        0x2C2C2E to 0x1A1116,
        0x1C1C1E to 0x120A0F,
    )

    /** 小黄鱼 primary-button gradient (btn_primary_bg #FFB3D1 -> #FF7AD9), left -> right. */
    val primaryButtonColors = listOf(0xFFFFB3D1, 0xFFFF7AD9)

    /** Left-to-right 小黄鱼 gradient brush for primary buttons / switch tracks. */
    fun primaryButtonBrush(): Brush = Brush.horizontalGradient(
        primaryButtonColors.map { Color(it) },
    )
}

/**
 * Skin-aware literal bridge. Reads the gate state, so composables calling it
 * subscribe for recomposition; plain call sites just see the current value.
 */
fun cili(base: Long): Color = CiliSkin.color(base)
