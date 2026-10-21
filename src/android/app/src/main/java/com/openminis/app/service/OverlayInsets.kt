package com.openminis.app.service

import android.content.Context
import com.openminis.app.data.repository.BackgroundSettingsRepository

/**
 * 悬浮胶囊的安全区计算，从 ToolOverlayController 抽出以控制该文件行数。
 */
internal fun clampToSafeArea(
    context: Context,
    x: Int,
    y: Int,
    capsuleWidth: Int,
    capsuleHeight: Int,
): Pair<Int, Int> {
    val dm = context.resources.displayMetrics
    val (top, bottom) = systemBarInsets(context)
    return OverlayBounds.clamp(
        x, y, capsuleWidth, capsuleHeight,
        OverlayBounds.Screen(dm.widthPixels, dm.heightPixels, top, bottom),
    )
}

internal fun systemBarInsets(context: Context): Pair<Int, Int> {
    val res = context.resources
    fun dimen(name: String): Int {
        val id = res.getIdentifier(name, "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }
    val top = dimen("status_bar_height").takeIf { it > 0 }
        ?: ((24 * res.displayMetrics.density + 0.5f).toInt())
    val bottom = dimen("navigation_bar_height").takeIf { it > 0 }
        ?: ((24 * res.displayMetrics.density + 0.5f).toInt())
    return top to bottom
}
