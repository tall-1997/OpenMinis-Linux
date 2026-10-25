package com.openminis.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn (Canvas) glyphs for the Soul personality prompt UI.
 * Kept off Material icon packs so the new filename / import / provider
 * controls have a distinct, consistent stroke language.
 */
@Composable
fun SoulFileGlyph(
    modifier: Modifier = Modifier,
    tint: Color,
    sizeDp: Dp = 22.dp,
) {
    Canvas(modifier.size(sizeDp)) {
        val w = size.minDimension
        val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val left = w * 0.18f
        val top = w * 0.12f
        val right = w * 0.82f
        val bottom = w * 0.88f
        val fold = w * 0.28f
        val path = Path().apply {
            moveTo(left, top)
            lineTo(right - fold, top)
            lineTo(right, top + fold)
            lineTo(right, bottom)
            lineTo(left, bottom)
            close()
        }
        drawPath(path, color = tint, style = stroke)
        drawLine(
            color = tint,
            start = Offset(right - fold, top),
            end = Offset(right - fold, top + fold),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(right - fold, top + fold),
            end = Offset(right, top + fold),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        val lineLeft = left + w * 0.14f
        val lineRight = right - w * 0.14f
        drawLine(tint, Offset(lineLeft, w * 0.46f), Offset(lineRight, w * 0.46f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(lineLeft, w * 0.60f), Offset(lineRight, w * 0.60f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(lineLeft, w * 0.74f), Offset(lineRight - w * 0.12f, w * 0.74f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun SoulPlusGlyph(
    modifier: Modifier = Modifier,
    tint: Color,
    sizeDp: Dp = 22.dp,
) {
    Canvas(modifier.size(sizeDp)) {
        val w = size.minDimension
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color = tint, radius = w / 2f - stroke.width, style = stroke)
        val pad = w * 0.30f
        drawLine(tint, Offset(center.x, pad), Offset(center.x, w - pad), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(pad, center.y), Offset(w - pad, center.y), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun SoulChevronGlyph(
    modifier: Modifier = Modifier,
    tint: Color,
    sizeDp: Dp = 18.dp,
) {
    Canvas(modifier.size(sizeDp)) {
        val w = size.minDimension
        val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.38f, w * 0.22f)
            lineTo(w * 0.68f, w * 0.50f)
            lineTo(w * 0.38f, w * 0.78f)
        }
        drawPath(path, color = tint, style = stroke)
    }
}

@Composable
fun SoulProviderGlyph(
    modifier: Modifier = Modifier,
    tint: Color,
    sizeDp: Dp = 22.dp,
) {
    Canvas(modifier.size(sizeDp)) {
        val w = size.minDimension
        val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val boxH = w * 0.22f
        val gap = w * 0.08f
        val left = w * 0.16f
        val boxW = w * 0.68f
        var top = w * 0.16f
        repeat(3) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
                style = stroke,
            )
            drawCircle(
                color = tint,
                radius = w * 0.035f,
                center = Offset(left + w * 0.12f, top + boxH / 2f),
            )
            top += boxH + gap
        }
    }
}
