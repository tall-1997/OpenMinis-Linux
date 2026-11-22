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
 * [T-running-shimmer] Sweep-light for in-flight entries ("执行中的条目").
 *
 * User direction (2.1.13): the band starts at the RIGHT edge and travels
 * right -> left, one crossing every 3 seconds, and the loop is continuous —
 * a second band is drawn one travel-length ahead so when the leading band
 * exits the left edge the next one is already entering the right edge, with
 * zero dead time between cycles. Thinking entries never mount this modifier
 * (user: "对思考条目不生效" — ChatProcessCards no longer applies it).
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
            // 1.0f -> -0.4f: band starts fully off the RIGHT edge and exits
            // fully past the LEFT edge (x = width * progress, reversed).
            initialValue = 1.0f,
            targetValue = -0.4f,
            animationSpec = infiniteRepeatable(
                // [T-shimmer-2s] user: 扫光效果从3秒缩回2秒一次.
                animation = tween(durationMillis = 2000, easing = LinearEasing),
            ),
            label = "runningShimmerProgress",
        )
        this.drawWithContent {
            drawContent()
            val band = size.width * 0.4f
            val travel = size.width + band
            val x = size.width * progress
            val brush = Brush.linearGradient(
                0.0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.12f),
                1.0f to Color.Transparent,
                start = Offset(x, 0f),
                end = Offset(x + band, size.height),
            )
            drawRect(brush = brush)
            // Continuity band: one travel-length ahead of the leading band.
            // When the leading band finishes exiting left, this one sits
            // exactly at the right-edge start position of the next cycle.
            val wrapX = x + travel
            drawRect(
                brush = Brush.linearGradient(
                    0.0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = 0.12f),
                    1.0f to Color.Transparent,
                    start = Offset(wrapX, 0f),
                    end = Offset(wrapX + band, size.height),
                ),
            )
        }
    }
}
