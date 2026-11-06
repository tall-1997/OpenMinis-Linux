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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.decorLinearSweep
import com.openminis.app.ui.theme.rememberDecorClockConsumer

/**
 * [T-wave-e-group-visuals] Home-screen group visuals:
 *
 *  - [GroupRecentGridAvatar] — the group avatar is a 2×2 mosaic of the four
 *    most recent member sessions (tiles carry the session's initial on a
 *    category-tinted ground), replacing the single category glyph so the
 *    header previews what is actually inside.
 *  - [CometRing] — while a member is running, a comet orbits the avatar
 *    edge. The angle reads the SHARED DecorClock phase (one 30fps clock for
 *    every decorative animation, stopped automatically in background and
 *    when no consumer is mounted) — no self-started frame coroutine, which
 *    is exactly what the old SpinningRing used to run per row.
 *  - [UnreadDot] — red dot for a session (row) or a group (header) with an
 *    unseen reply.
 */

private const val GROUP_GRID_TILES = 4

/** The group's four most recent sessions as display seeds. */
internal fun recentGridMembers(members: List<String?>): List<String?> =
    members.take(GROUP_GRID_TILES)

private fun initialOf(title: String?): String =
    title?.trim()?.take(1)?.uppercase()?.ifEmpty { "·" } ?: "·"

@Composable
internal fun GroupRecentGridAvatar(
    members: List<String?>,
    modifier: Modifier = Modifier,
) {
    val tiles = remember(members) { recentGridMembers(members) }
    Box(
        modifier = modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            tiles.chunked(2).forEach { rowTiles ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    // 1-3 members: fill the grid so the mosaic never shows a
                    // ragged hole; 4+: exactly the recent four.
                    val shown = if (rowTiles.size < 2 && tiles.size <= 2) List(2) { rowTiles.getOrNull(0) }
                    else rowTiles
                    shown.forEach { s ->
                        Box(
                            modifier = Modifier.size(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = s?.let { initialOf(it) } ?: "·",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * [T-wave-e-group-visuals] Comet orbiting the avatar edge while a member
 * runs. Angle = the shared DecorClock phase swept over [ORBIT_PERIOD_MS];
 * background stops the clock, so nothing runs off-screen.
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
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width)
        val topLeft = Offset(inset, inset)
        // Tail: fading arc behind the head.
        drawArc(
            color = color.copy(alpha = 0.35f),
            startAngle = -90f + sweep * 360f - TAIL_SWEEP_DEGREES,
            sweepAngle = TAIL_SWEEP_DEGREES,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        // Head: a short bright cap at the comet's nose.
        drawArc(
            color = color,
            startAngle = -90f + sweep * 360f - HEAD_SWEEP_DEGREES,
            sweepAngle = HEAD_SWEEP_DEGREES,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke.width * 2f, cap = StrokeCap.Round),
        )
    }
}

private const val ORBIT_PERIOD_MS = 2_400
private const val TAIL_SWEEP_DEGREES = 70f
private const val HEAD_SWEEP_DEGREES = 22f

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
