package com.openminis.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.data.model.ContextUsage

/**
 * [T-android-composer-context-glow] Rotating marquee glow on the composer's
 * outer edge while the session's context usage approaches the model window.
 *
 * Data source is the session-menu ring's [ChatViewModel.contextUsage]
 * StateFlow — the same [ContextUsage] the Token Usage sheet renders.
 * [ChatViewModel.refreshContextUsage] prefers the provider-reported
 * `latestContextTokens` over the char estimate, so the glow tracks the REAL
 * context size, not a guess.
 *
 * Rendering notes:
 *  - The animation only exists while the glow is active: below the activate
 *    threshold (or with the appearance pref off) the modifier returns the
 *    receiver unchanged, so no InfiniteTransition is ever composed.
 *  - The angle State is read inside the draw lambda, so rotation invalidates
 *    only the draw phase of this one node — never recomposition or layout.
 *  - Speed-up near the cap is quantised to three bands (see
 *    [composerGlowPeriodMs]); a band change restarts the rotation, which is
 *    imperceptible at these periods and keeps the spec stable otherwise.
 *  - The stroke rotates around the box centre with a sweep gradient that has
 *    one bright sector, producing the marquee chase; three stacked strokes
 *    fake the soft glow without a blur pass.
 */

/** Context ratio where the glow starts (85% of the window). */
internal const val COMPOSER_GLOW_ACTIVATE_RATIO = 0.85f

/** Ratio at which the glow reaches full strength. */
internal const val COMPOSER_GLOW_FULL_RATIO = 0.98f

/** Rotation periods per intensity band: calm → urgent (near 100%). */
internal const val COMPOSER_GLOW_PERIOD_CALM_MS = 2400
internal const val COMPOSER_GLOW_PERIOD_FAST_MS = 1500
internal const val COMPOSER_GLOW_PERIOD_URGENT_MS = 900

/** Bands: below 90% calm, below 95% fast, then urgent. */
internal const val COMPOSER_GLOW_FAST_BAND = 0.90f
internal const val COMPOSER_GLOW_URGENT_BAND = 0.95f

/**
 * 0..1 glow strength for a context [ratio]. 0 while at or below the activate
 * threshold, ramping linearly to 1 at [COMPOSER_GLOW_FULL_RATIO]. Pure —
 * JVM-tested.
 */
internal fun composerGlowIntensity(ratio: Float): Float = when {
    ratio < COMPOSER_GLOW_ACTIVATE_RATIO -> 0f
    ratio >= COMPOSER_GLOW_FULL_RATIO -> 1f
    else -> (ratio - COMPOSER_GLOW_ACTIVATE_RATIO) /
        (COMPOSER_GLOW_FULL_RATIO - COMPOSER_GLOW_ACTIVATE_RATIO)
}

/**
 * Rotation period for the current glow [intensity] (0..1), quantised to the
 * three bands so the animation spec does not churn on every usage tick.
 * Pure — JVM-tested.
 */
internal fun composerGlowPeriodMs(intensity: Float): Int = when {
    intensity >= COMPOSER_GLOW_URGENT_BAND -> COMPOSER_GLOW_PERIOD_URGENT_MS
    intensity >= COMPOSER_GLOW_FAST_BAND -> COMPOSER_GLOW_PERIOD_FAST_MS
    else -> COMPOSER_GLOW_PERIOD_CALM_MS
}

/**
 * One marquee stroke set: a sweep gradient with a single bright sector,
 * rotated around the box centre. [glowAlpha] scales every stroke so the glow
 * fades in from the activate threshold instead of popping.
 */
private fun DrawScope.drawContextGlow(
    angle: Float,
    glowColor: Color,
    strokePx: Float,
    radiusPx: Float,
    glowAlpha: Float,
) {
    val pad = strokePx * 1.5f
    rotate(degrees = angle, pivot = Offset(size.width / 2f, size.height / 2f)) {
        val brush = Brush.sweepGradient(
            colors = listOf(
                glowColor.copy(alpha = 0f),
                glowColor.copy(alpha = 0f),
                glowColor.copy(alpha = 0.9f),
                glowColor,
                glowColor.copy(alpha = 0f),
            ),
            center = Offset(size.width / 2f, size.height / 2f),
        )
        // Three stacked strokes: a crisp edge line plus two soft halos. All
        // share the same rotating brush, so the "light" chases as one shape.
        val rings = listOf(0f to 0.85f, 1f to 0.30f, 2f to 0.12f)
        for ((ring, alpha) in rings) {
            val grow = ring * strokePx
            drawRoundRect(
                brush = brush,
                topLeft = Offset(-pad - grow, -pad - grow),
                size = Size(size.width + pad * 2f + grow * 2f, size.height + pad * 2f + grow * 2f),
                cornerRadius = CornerRadius(radiusPx + grow),
                style = Stroke(width = strokePx + grow),
                alpha = glowAlpha * alpha,
            )
        }
    }
}

/**
 * Attach the marquee glow to the composer's outer box. Inert (returns the
 * receiver unchanged) while the appearance pref is off or the context ratio
 * has not crossed the activate threshold — including when the model window
 * is unknown ([ContextUsage.ratio] is 0 there).
 */
@Composable
internal fun Modifier.composerContextGlow(
    enabled: Boolean,
    viewModel: ChatViewModel,
): Modifier {
    if (!enabled) return this
    val usage by viewModel.contextUsage.collectAsStateWithLifecycle()
    // [T-android-composer-context-glow] Keep the reading live within a turn:
    // refreshContextUsage() is normally invoked at turn boundaries only, but
    // the context grows per API call while tools run. Recompute on every
    // structural `messages` change (the canonical flow is quiet per token —
    // the streaming side-channel carries that churn), so the glow reacts to
    // the same signal the session-menu ring would show after a refresh.
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.messages.collect { viewModel.refreshContextUsage() }
    }
    val intensity = composerGlowIntensity(usage.ratio)
    if (intensity <= 0f) return this
    val glowColor = MaterialTheme.colorScheme.primary
    val strokePx = with(LocalDensity.current) { 2.5.dp.toPx() }
    val radiusPx = with(LocalDensity.current) { 20.dp.toPx() }
    val angle by rememberInfiniteTransition(label = "composerContextGlow").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = composerGlowPeriodMs(intensity), easing = LinearEasing),
        ),
        label = "composerContextGlowAngle",
    )
    val glowAlpha = 0.35f + 0.65f * intensity
    return this.drawBehind {
        drawContextGlow(angle, glowColor, strokePx, radiusPx, glowAlpha)
    }
}
