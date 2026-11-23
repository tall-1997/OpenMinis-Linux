package com.openminis.app.ui.chat

// [T-android-split-chat] SwipeToSendHint extracted verbatim from ChatMiscViews.kt
// (architecture ceiling). Self-contained: only touches package-visible symbols and
// its own imports — no file-private helpers were attached to it.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.m3Icon

/// Floating "send arrow + Release to send capsule" hint shown while the
/// user is dragging the input bar upward to send. Mirrors iOS
/// `SwipeToSendHint` in AIChatView.swift. Reuses `ChatColors.sendButton` /
/// `ChatColors.background` for theme-aware contrast (auto-inverts in dark
/// mode without needing a separate palette).
///
/// - `progress` 0..1: drag distance as a fraction of the trigger threshold.
///   Arrow opacity = `min(1, progress * 1.4)`. Capsule opacity ramps in over
///   the band `[armFraction - 0.4, armFraction]` so it reaches full opacity
///   exactly at the haptic / arming point.
/// - `armFraction`: progress at which release fires send (default 0.8 in
///   the caller, matching iOS).
/// - `location`: live fingertip position in the wrapping Box's local px.
///   Hint sits `hoverAbovePx` above this point, ~one thumb-tip away.
/// - `arrowHalfPx`: half the rendered arrow size, used to center the
///   arrow on the fingertip's X.
@Composable
internal fun SwipeToSendHint(
    progress: Float,
    armFraction: Float,
    location: Offset,
    hoverAbovePx: Float,
    arrowHalfPx: Float,
    /** When true, the capsule advertises "Release to queue" instead of
     *  "Release to send" — used while the agent is mid-stream, when the
     *  gesture routes through `enqueuePrompt()` instead of an immediate
     *  send. Lets the user see the gesture still works mid-stream. */
    isEnqueue: Boolean = false,
) {
    if (progress <= 0f) return
    val palette = ChatColors
    val chipBg = palette.sendButton
    val chipFg = palette.background
    // Capsule full opacity at `armFraction`. Linear from
    // `armFraction - 0.4` -> `armFraction`.
    val capsuleStart = (armFraction - 0.4f).coerceAtLeast(0f)
    val capsuleSpan = (armFraction - capsuleStart).coerceAtLeast(0.0001f)
    val capsuleAlpha = ((progress - capsuleStart) / capsuleSpan).coerceIn(0f, 1f)
    val rootAlpha = (progress * 1.4f).coerceIn(0f, 1f)
    val scale = 0.9f + 0.18f * progress
    val density = androidx.compose.ui.platform.LocalDensity.current
    val xDp = with(density) { (location.x - arrowHalfPx).toDp() }
    val yDp = with(density) { (location.y - hoverAbovePx - arrowHalfPx).toDp() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .offset(x = xDp, y = yDp)
            .graphicsLayer {
                alpha = rootAlpha
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            },
    ) {
        // Send arrow disc — paints the same circle background + inverted
        // arrow as the real send button so the gesture reads as "drag the
        // message up to send".
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(chipBg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = m3Icon(Icons.Default.ArrowUpward),
                contentDescription = null,
                tint = chipFg,
                modifier = Modifier.size(18.dp),
            )
        }
        // "Release to send" capsule — same height as the disc, opacity
        // ramps in over the second half of the drag so it doubles as a
        // "you're almost there" confirmation cue.
        Box(
            modifier = Modifier
                .height(34.dp)
                .background(chipBg, RoundedCornerShape(50))
                .padding(horizontal = 14.dp)
                .graphicsLayer { alpha = capsuleAlpha },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (isEnqueue) R.string.composer_swipe_release_to_queue
                    else R.string.composer_swipe_release_to_send,
                ),
                color = chipFg,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
