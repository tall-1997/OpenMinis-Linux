package com.openminis.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.CiliSkin

/**
 * Shared switch with the 小黄鱼 treatment.
 *
 * [T-cili-skin-gate] While the skin gate is inactive (dynamic color ON or
 * light mode) this is a plain Material3 [Switch] — zero visual difference.
 * While the gate is active the track carries the 小黄鱼 left -> right
 * gradient (#FFB3D1 -> #FF7AD9) and the thumb gets the pale gradient.
 *
 * Geometry mirrors M3: 52x32dp track, 16dp unchecked / 24dp checked thumb.
 */
@Composable
fun MinisGradientSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!CiliSkin.isActive) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
        return
    }
    val trackWidth = 52.dp
    val trackHeight = 32.dp
    val corner = trackHeight / 2
    val thumbSize: Dp by animateDpAsState(
        targetValue = if (checked) 24.dp else 16.dp,
        animationSpec = tween(durationMillis = 150),
        label = "ciliSwitchThumbSize",
    )
    val thumbOffset: Dp by animateDpAsState(
        targetValue = if (checked) trackWidth - 24.dp - 4.dp else 4.dp,
        animationSpec = tween(durationMillis = 150),
        label = "ciliSwitchThumbOffset",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = trackWidth, height = trackHeight)
                .clip(RoundedCornerShape(corner))
                .then(
                    if (checked) {
                        Modifier.background(
                            Brush.horizontalGradient(
                                CiliSkin.primaryButtonColors.map { Color(it) },
                            ),
                        )
                    } else {
                        Modifier
                            .background(Color(0xFF2A1620))
                            .border(1.dp, Color(0x40B9A3B0), RoundedCornerShape(corner))
                    },
                )
                .clickable(enabled = enabled && onCheckedChange != null) {
                    onCheckedChange?.invoke(!checked)
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(
                        if (checked) {
                            Brush.horizontalGradient(listOf(Color(0xFFFFEFF6), Color(0xFFFFD9E8)))
                        } else {
                            Brush.horizontalGradient(listOf(Color(0xFFB9A3B0), Color(0xFFD3BDC9)))
                        },
                    ),
            )
        }
    }
}
