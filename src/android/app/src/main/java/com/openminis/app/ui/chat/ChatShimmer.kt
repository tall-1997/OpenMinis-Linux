package com.openminis.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * [T-running-shimmer] Sweep-light for in-flight entries ("执行中的条目"):
 * a soft diagonal highlight band crosses the surface once every 2 seconds
 * (user direction: "2秒钟扫一次").
 *
 * Apple-style restraint: a low-alpha white band over the existing surface —
 * no scale, no pulse, no color shift. Inactive entries take no modifier at
 * all (the infinite transition is never created), so settled cards cost
 * zero frames.
 */
internal fun Modifier.runningShimmerSweep(active: Boolean): Modifier = composed {
    if (!active) {
        Modifier
    } else {
        val transition = rememberInfiniteTransition(label = "runningShimmer")
        val progress by transition.animateFloat(
            initialValue = -0.4f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = LinearEasing),
            ),
            label = "runningShimmerProgress",
        )
        this.drawWithContent {
            drawContent()
            val band = size.width * 0.4f
            val x = size.width * progress
            drawRect(
                brush = Brush.linearGradient(
                    0.0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = 0.12f),
                    1.0f to Color.Transparent,
                    start = Offset(x, 0f),
                    end = Offset(x + band, size.height),
                ),
            )
        }
    }
}
