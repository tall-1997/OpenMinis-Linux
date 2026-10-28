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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.data.model.ContextUsage

/**
 * [T-android-composer-context-glow] Marquee chase light on the composer's
 * outer border while the session's context usage approaches the model
 * window: one bright bead travels along the rounded-rect border path at
 * constant speed, dragging a fading comet trail behind it — a light running
 * along a rail, not a rotating frame or a sweep over the whole box.
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
 *  - The phase State is read inside the cached draw lambda only, so each
 *    frame invalidates just the draw phase of this one node — never
 *    recomposition or layout.
 *  - Geometry lives in `drawWithCache`: the border [Path] and its
 *    [PathMeasure] are built once per size, not per frame. The trail is
 *    stroked from a reusable scratch Path (`rewind` before every segment),
 *    so steady-state frames allocate nothing.
 *  - Speed-up near the cap is quantised to three bands (see
 *    [composerGlowPeriodMs]); a band change restarts the lap, which is
 *    imperceptible at these periods and keeps the spec stable otherwise.
 */

/** Context ratio where the glow starts (85% of the window). */
internal const val COMPOSER_GLOW_ACTIVATE_RATIO = 0.85f

/** Ratio at which the glow reaches full strength. */
internal const val COMPOSER_GLOW_FULL_RATIO = 0.98f

/** Lap periods per intensity band: calm → urgent (near 100%). */
internal const val COMPOSER_GLOW_PERIOD_CALM_MS = 2400
internal const val COMPOSER_GLOW_PERIOD_FAST_MS = 1500
internal const val COMPOSER_GLOW_PERIOD_URGENT_MS = 900

/** Bands: below 90% calm, below 95% fast, then urgent. */
internal const val COMPOSER_GLOW_FAST_BAND = 0.90f
internal const val COMPOSER_GLOW_URGENT_BAND = 0.95f

/** Trail density: stroked path segments chasing the head. */
internal const val COMPOSER_GLOW_TRAIL_SEGMENTS = 7

/** Trail length as a fraction of the border perimeter. */
internal const val COMPOSER_GLOW_TRAIL_FRACTION = 0.30f

/** Head bead radius in dp (the bright dot riding the border). */
internal const val COMPOSER_GLOW_HEAD_RADIUS_DP = 3.5f

/** Ranges shorter than this are sub-pixel noise; drop them. */
private const val GLOW_TRAIL_RANGE_EPSILON = 0.01f

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
 * Lap period for the current glow [intensity] (0..1), quantised to the three
 * bands so the animation spec does not churn on every usage tick. Pure —
 * JVM-tested.
 */
internal fun composerGlowPeriodMs(intensity: Float): Int = when {
    intensity >= COMPOSER_GLOW_URGENT_BAND -> COMPOSER_GLOW_PERIOD_URGENT_MS
    intensity >= COMPOSER_GLOW_FAST_BAND -> COMPOSER_GLOW_PERIOD_FAST_MS
    else -> COMPOSER_GLOW_PERIOD_CALM_MS
}

/** One stroked piece of the comet trail: path range [start, end] (end >= start) at [alpha]. */
internal data class GlowTrailSegment(val start: Float, val end: Float, val alpha: Float)

/** Value % [pathLength] mapped into [0, pathLength) — the path seam handler. */
private fun perimeterMod(value: Float, pathLength: Float): Float {
    val r = value % pathLength
    return if (r < 0f) r + pathLength else r
}

/**
 * Unroll the comet trail behind a head at [headDistance] into strokable path
 * ranges. The trail runs backwards along the perimeter for [trailLength] px
 * (clamped to [pathLength]), split into [segmentCount] pieces whose alpha
 * tapers quadratically from the head (1.0) to the tail (1/n²). Pieces that
 * cross the path seam wrap into two ranges, so every emitted range satisfies
 * `0 <= start <= end <= pathLength` and zero-length seam stubs are dropped.
 * Pure — JVM-tested.
 */
internal fun composerGlowTrailSegments(
    headDistance: Float,
    trailLength: Float,
    pathLength: Float,
    segmentCount: Int,
): List<GlowTrailSegment> {
    if (pathLength <= 0f || segmentCount <= 0 || trailLength <= 0f) return emptyList()
    val head = perimeterMod(headDistance, pathLength)
    val trail = trailLength.coerceAtMost(pathLength)
    val segments = ArrayList<GlowTrailSegment>(segmentCount)
    for (i in 0 until segmentCount) {
        val near = perimeterMod(head - trail * i / segmentCount, pathLength)
        val far = perimeterMod(head - trail * (i + 1) / segmentCount, pathLength)
        val taper = (segmentCount - i).toFloat() / segmentCount
        val alpha = taper * taper
        if (far <= near) {
            // Contiguous piece inside one lap: [far, near].
            if (near - far > GLOW_TRAIL_RANGE_EPSILON) {
                segments.add(GlowTrailSegment(far, near, alpha))
            }
        } else {
            // Piece crosses the path seam: [0, near] + [far, pathLength].
            if (near > GLOW_TRAIL_RANGE_EPSILON) {
                segments.add(GlowTrailSegment(0f, near, alpha))
            }
            if (pathLength - far > GLOW_TRAIL_RANGE_EPSILON) {
                segments.add(GlowTrailSegment(far, pathLength, alpha))
            }
        }
    }
    return segments
}

/**
 * One frame of the chase light on the composer border. [headDistance] is the
 * bead's arc position on the pre-measured border path.
 *
 * Layers, back to front:
 *  1. A constant hairline border — the rail the light runs along, so the
 *     effect reads as chasing on the card edge rather than floating.
 *  2. The comet trail: stroked path segments behind the head, alpha and
 *     width tapering towards the tail.
 *  3. The head bead: a soft halo plus a bright core.
 */
private fun DrawScope.drawContextGlow(
    headDistance: Float,
    glowColor: Color,
    strokePx: Float,
    glowAlpha: Float,
    headRadiusPx: Float,
    borderPath: Path,
    pathMeasure: PathMeasure,
    pathLength: Float,
    trailLength: Float,
    scratchPath: Path,
) {
    if (pathLength <= 0f) return
    // 1. The rail: the border itself stays a thin visible line.
    drawPath(
        path = borderPath,
        color = glowColor,
        alpha = glowAlpha * 0.22f,
        style = Stroke(width = strokePx),
    )
    // 2. The comet trail, unrolled around the seam by the pure helper.
    for (segment in composerGlowTrailSegments(
        headDistance,
        trailLength,
        pathLength,
        COMPOSER_GLOW_TRAIL_SEGMENTS,
    )) {
        scratchPath.rewind()
        if (!pathMeasure.getSegment(segment.start, segment.end, scratchPath, true)) continue
        drawPath(
            path = scratchPath,
            color = glowColor,
            alpha = glowAlpha * segment.alpha,
            style = Stroke(width = strokePx * (0.6f + 0.4f * segment.alpha)),
        )
    }
    // 3. The head bead: halo + core.
    val head = pathMeasure.getPosition(perimeterMod(headDistance, pathLength))
    drawCircle(color = glowColor, radius = headRadiusPx * 3f, center = head, alpha = glowAlpha * 0.25f)
    drawCircle(color = glowColor, radius = headRadiusPx, center = head, alpha = glowAlpha)
}

/**
 * Attach the marquee chase light to the composer's outer box. Inert (returns
 * the receiver unchanged) while the appearance pref is off or the context
 * ratio has not crossed the activate threshold — including when the model
 * window is unknown ([ContextUsage.ratio] is 0 there).
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
    val headRadiusPx = with(LocalDensity.current) { COMPOSER_GLOW_HEAD_RADIUS_DP.dp.toPx() }
    val phase by rememberInfiniteTransition(label = "composerContextGlow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = composerGlowPeriodMs(intensity), easing = LinearEasing),
        ),
        label = "composerContextGlowPhase",
    )
    val glowAlpha = 0.35f + 0.65f * intensity
    return this.drawWithCache {
        // Rebuilt only when the size (or a captured param) changes — never
        // per animation frame. The path is padded outwards by 1.5x the
        // stroke so the light straddles the card edge like the old sweep
        // did; the corner radius grows by the same pad to stay concentric.
        val pad = strokePx * 1.5f
        val borderPath = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(
                        offset = Offset(-pad, -pad),
                        size = Size(size.width + pad * 2f, size.height + pad * 2f),
                    ),
                    cornerRadius = CornerRadius(radiusPx + pad),
                ),
            )
        }
        val pathMeasure = PathMeasure().apply { setPath(borderPath, forceClosed = true) }
        val pathLength = pathMeasure.length
        val trailLength = pathLength * COMPOSER_GLOW_TRAIL_FRACTION
        val scratchPath = Path()
        onDrawBehind {
            drawContextGlow(
                headDistance = phase * pathLength,
                glowColor = glowColor,
                strokePx = strokePx,
                glowAlpha = glowAlpha,
                headRadiusPx = headRadiusPx,
                borderPath = borderPath,
                pathMeasure = pathMeasure,
                pathLength = pathLength,
                trailLength = trailLength,
                scratchPath = scratchPath,
            )
        }
    }
}
