package com.openminis.app.browser

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Bound native pixels BEFORE allocating a bitmap, including live previews. */
internal object ScreenshotBudget {
    const val MAX_PIXELS = 4_000_000L
    const val MAX_EDGE = 4096

    fun size(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = min(1.0, min(
            sqrt(MAX_PIXELS.toDouble() / (width.toLong() * height)),
            MAX_EDGE.toDouble() / maxOf(width, height),
        ))
        return maxOf(1, floor(width * scale).toInt()) to
            maxOf(1, floor(height * scale).toInt())
    }
}
