package com.openminis.app.ui.sessions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.theme.decorLinearSweep
import com.openminis.app.ui.theme.monetIconTint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * [T-wave-e-group-visuals] Home-screen group visuals:
 *
 *  - [FolderCategoryAvatar] — the folder avatar IS the category panel's own
 *    iconography: one session wears that session's category glyph, two and
 *    more compose 2/4/9 grids of their newest members' category glyphs.
 *  - [CometRing] — while a member is running, a comet rides the avatar's
 *    own rounded-square outline ([roundedRectOutlinePoint] walks the exact
 *    rect the tile draws, corners included). The phase reads the SHARED
 *    DecorClock (one 30fps clock for every decorative animation, stopped
 *    automatically in background and when no consumer is mounted) — no
 *    self-started frame coroutine, which is exactly what the old
 *    SpinningRing used to run per row.
 *  - [UnreadDot] — red dot for a session (row) or a group (header) with an
 *    unseen reply.
 */

// The group avatar tile's silhouette. [CometRing] rides this exact outline,
// so the running comet hugs the folder shape instead of a circle that
// ignores the rounded corners.
private val GROUP_AVATAR_SIZE_DP = 44.dp
private val GROUP_AVATAR_CORNER_DP = 10.dp

/**
 * [T-android-folder-category-icon] Grid a folder icon composes its members
 * into. One session → that session's own category icon (the folder reads as
 * the session); two → a 2×1 pair; three to four → 2×2; five to nine → 3×3.
 * Past nine the extras do not participate — the icon is a preview, not a
 * census, and a 4×3 cell grid at 44dp would be unreadable.
 */
internal data class FolderIconLayout(val columns: Int, val rows: Int) {
    val capacity: Int get() = columns * rows
}

internal fun folderIconLayout(count: Int): FolderIconLayout = when {
    count <= 1 -> FolderIconLayout(columns = 1, rows = 1)
    count == 2 -> FolderIconLayout(columns = 2, rows = 1)
    count <= 4 -> FolderIconLayout(columns = 2, rows = 2)
    else -> FolderIconLayout(columns = 3, rows = 3)
}

/**
 * [T-android-folder-category-icon] Recency-ordered member categories truncated
 * to the layout's capacity. `null` entries survive: they are a real state
 * (an uncategorised session) and fall back to the neutral category glyph.
 */
internal fun folderIconCategories(categories: List<String?>): List<String?> =
    categories.take(folderIconLayout(categories.size).capacity)

/**
 * [T-android-folder-category-icon] Folder avatar composed from the SAME
 * category iconography as the category panel — no invented folder silhouette,
 * no initials mosaic. A one-session folder wears that session's own category
 * icon at the session row's scale (identical circle, identical 0.18 tint), so
 * the folder simply IS the session; two and more compose 2/4/9 app-folder
 * grids of their newest members' category glyphs.
 */
@Composable
internal fun FolderCategoryAvatar(
    categories: List<String?>,
    modifier: Modifier = Modifier,
) {
    val shown = remember(categories) { folderIconCategories(categories) }
    if (shown.size <= 1) {
        val style = categoryStyle(if (shown.isEmpty()) "other" else shown.first())
        Box(
            modifier = modifier
                .size(GROUP_AVATAR_SIZE_DP)
                .background(monetIconTint(style.color).copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = monetIconTint(style.color),
                modifier = Modifier.size(20.dp),
            )
        }
        return
    }
    val layout = remember(shown.size) { folderIconLayout(shown.size) }
    val tile = if (layout.columns == 3) 11.dp else 16.dp
    val gap = if (layout.columns == 3) 1.5.dp else 4.dp
    val glyph = if (layout.columns == 3) 7.dp else 10.dp
    Box(
        modifier = modifier
            .size(GROUP_AVATAR_SIZE_DP)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(GROUP_AVATAR_CORNER_DP),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            shown.chunked(layout.columns).forEach { rowCategories ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowCategories.forEach { category ->
                        val style = categoryStyle(category)
                        Box(
                            modifier = Modifier
                                .size(tile)
                                .background(
                                    monetIconTint(style.color).copy(alpha = 0.18f),
                                    RoundedCornerShape(tile * 0.3f),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = style.icon,
                                contentDescription = null,
                                tint = monetIconTint(style.color),
                                modifier = Modifier.size(glyph),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * [T-wave-e-group-visuals] Comet riding the avatar's own outline while a
 * member runs. Phase = the shared DecorClock sweep over [ORBIT_PERIOD_MS];
 * background stops the clock, so nothing runs off-screen. The path is
 * [roundedRectOutlinePoint] over the tile's rounded rect — the comet stays
 * glued to the folder silhouette through the corners, where the old
 * circular drawArc orbit floated up to ~4.7dp off the shape.
 */
@Composable
internal fun CometRing(
    color: Color,
    modifier: Modifier = Modifier,
) {
    // decorLinearSweep registers the shared-clock consumer itself and stops
    // the frame loop when the consumer count drops (background included).
    val sweep = decorLinearSweep(from = 0f, to = 1f, periodMs = ORBIT_PERIOD_MS)
    Canvas(modifier = modifier.semantics { contentDescription = "" }) {
        val strokePx = 1.5.dp.toPx()
        // Ride the tile's own silhouette: the canvas is slightly larger than
        // the tile, so center the tile rect inside it and walk that outline.
        val avatarPx = GROUP_AVATAR_SIZE_DP.toPx()
        val inset = ((size.minDimension - avatarPx) / 2f).coerceAtLeast(0f)
        val outlineSize = size.minDimension - 2f * inset
        val cornerPx = GROUP_AVATAR_CORNER_DP.toPx().coerceAtMost(outlineSize / 2f)
        val origin = Offset(inset, inset)

        fun pointAt(t: Float): Offset =
            roundedRectOutlinePoint(outlineSize, outlineSize, cornerPx, t) + origin

        // Tail: fading trace the head leaves behind on the outline.
        for (i in 1..TAIL_SEGMENTS) {
            val f = i.toFloat() / TAIL_SEGMENTS
            drawLine(
                color = color.copy(alpha = TAIL_ALPHA * f),
                start = pointAt(sweep - TAIL_FRACTION * (1f - (i - 1f) / TAIL_SEGMENTS)),
                end = pointAt(sweep - TAIL_FRACTION * (1f - f)),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
        // Head: short bright nose at the sweep point.
        for (i in 1..HEAD_SEGMENTS) {
            drawLine(
                color = color,
                start = pointAt(sweep - HEAD_FRACTION * (1f - (i - 1f) / HEAD_SEGMENTS)),
                end = pointAt(sweep - HEAD_FRACTION * (1f - i.toFloat() / HEAD_SEGMENTS)),
                strokeWidth = strokePx * 2f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * One point on a rounded rectangle's outline, walked clockwise from the top
 * edge's left end `(radius, 0)`. [t] is the 0..1 phase of the perimeter;
 * values outside wrap modulo one, so an animation sweep can loop freely.
 * Boundary phases fall through to the next segment's start — the same
 * point — which keeps the walk continuous through the corners.
 */
internal fun roundedRectOutlinePoint(
    width: Float,
    height: Float,
    radius: Float,
    t: Float,
): Offset {
    if (width <= 0f || height <= 0f) return Offset.Zero
    val r = radius.coerceIn(0f, minOf(width, height) / 2f)
    val phase = t - floor(t)
    val straightW = width - 2f * r
    val straightH = height - 2f * r
    val arcLen = (PI.toFloat() / 2f) * r
    var d = phase * (2f * straightW + 2f * straightH + 4f * arcLen)

    if (d < straightW) return Offset(r + d, 0f)
    d -= straightW
    if (d < arcLen) return arcPoint(width - r, r, -PI.toFloat() / 2f, d, arcLen, r)
    d -= arcLen
    if (d < straightH) return Offset(width, r + d)
    d -= straightH
    if (d < arcLen) return arcPoint(width - r, height - r, 0f, d, arcLen, r)
    d -= arcLen
    if (d < straightW) return Offset(width - r - d, height)
    d -= straightW
    if (d < arcLen) return arcPoint(r, height - r, PI.toFloat() / 2f, d, arcLen, r)
    d -= arcLen
    if (d < straightH) return Offset(0f, height - r - d)
    d -= straightH
    if (d < arcLen) return arcPoint(r, r, PI.toFloat(), d, arcLen, r)
    // Float drift past the last arc lands back on the walk's start point.
    return Offset(r, 0f)
}

/** Point on a quarter-circle corner arc: [startAngle] plus the traveled fraction. */
private fun arcPoint(
    centerX: Float,
    centerY: Float,
    startAngle: Float,
    traveled: Float,
    arcLen: Float,
    r: Float,
): Offset {
    val sweep = if (arcLen > 0f) traveled / arcLen * (PI.toFloat() / 2f) else 0f
    val angle = startAngle + sweep
    return Offset(centerX + r * cos(angle), centerY + r * sin(angle))
}

private const val ORBIT_PERIOD_MS = 2_400
// Tail/head lengths as fractions of the outline perimeter — the old orbit
// swept 70°/22° of a circle; the same fractions carry over to the rect walk.
private const val TAIL_FRACTION = 70f / 360f
private const val HEAD_FRACTION = 22f / 360f
private const val TAIL_SEGMENTS = 22
private const val HEAD_SEGMENTS = 6
private const val TAIL_ALPHA = 0.35f

/** Red dot with a hairline surface ring, anchored bottom-end by the caller. */
@Composable
internal fun UnreadDot(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.cd_unread_reply)
    // Hoisted: MaterialTheme.colorScheme is a @Composable read, not legal
    // inside the Canvas draw scope.
    val surfaceColor = MaterialTheme.colorScheme.surface
    Canvas(
        modifier = modifier
            .size(11.dp)
            .semantics { contentDescription = label },
    ) {
        val ringWidth = 1.dp.toPx()
        // Hairline surface ring separates the dot from row ink behind it.
        drawCircle(
            color = surfaceColor,
            radius = size.minDimension / 2f,
            style = Stroke(width = ringWidth),
        )
        drawCircle(
            color = Color(0xFFFF3B30),
            radius = size.minDimension / 2f - ringWidth,
        )
    }
}
