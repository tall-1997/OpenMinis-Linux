package com.openminis.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.util.expandToggleClickable

/**
 * [T-port-fold-tools] The "› Ran N tools · 38s · 1 failed · 2 cancelled" row
 * a folded run of tool calls shows as (iOS ToolGroupRowView): 13 sp secondary
 * text, a 28 dp row, the chevron turned down while open, "failed" in red.
 * Tapping toggles the run open or closed in place.
 */
@Composable
internal fun ToolGroupRow(item: FlatChatItem.AssistantToolGroup, onToggle: () -> Unit) {
    val title = stringResource(R.string.tool_fold_ran_n_tools, item.toolCount)
    val failed = stringResource(R.string.tool_fold_n_failed, item.failedCount)
    val cancelled = stringResource(R.string.tool_fold_n_cancelled, item.cancelledCount)
    val duration = item.durationMs?.let(::formatToolDuration)
    val hint = stringResource(if (item.expanded) R.string.tool_fold_collapse_hint else R.string.tool_fold_expand_hint)
    val spoken = buildList {
        add(title)
        duration?.let(::add)
        if (item.failedCount > 0) add(failed)
        if (item.cancelledCount > 0) add(cancelled)
    }.joinToString(", ")
    val chevronAngle = animateFloatAsState(if (item.expanded) 90f else 0f, label = "toolGroupChevron")
    val text = buildAnnotatedString {
        append(title)
        if (duration != null) {
            append(" · ")
            withStyle(SpanStyle(fontFeatureSettings = "tnum")) { append(duration) }
        }
        if (item.failedCount > 0) {
            append(" · ")
            withStyle(SpanStyle(color = ToolErrorColor)) { append(failed) }
        }
        if (item.cancelledCount > 0) {
            append(" · ")
            append(cancelled)
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(28.dp)
                // [ISS-017] Expand/collapse row: no ripple (expandToggleClickable).
                .expandToggleClickable(onClick = onToggle)
                // [T-fold-row-chevron-align] No start inset: the chevron's
                // visible left edge sits on the same line as the reply text and
                // the tool pills above and below. The 4 dp moved to the end, so
                // the tap target keeps its old width (was 4 + content + 4).
                .padding(start = 0.dp, end = 8.dp)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    stateDescription = hint
                    role = Role.Button
                    onClick(label = hint) { onToggle(); true }
                },
        ) {
            FoldChevron(angle = chevronAngle.value, color = ChatColors.secondaryText)
            Spacer(Modifier.width(5.dp))
            Text(
                text = text,
                fontSize = 13.sp,
                color = ChatColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * [T-fold-row-chevron-align] The "›" drawn edge to edge in its own box, so its
 * visible left edge is the box's left edge. Material's KeyboardArrowRight
 * carries ~3-4 dp of empty space at 14 dp, which pushed the glyph right of the
 * text column. Rotated in graphicsLayer around the box centre, which is the
 * chevron's own centre, so it turns in place when the run opens (iOS
 * chevron.right, rotationEffect 90°).
 */
@Composable
private fun FoldChevron(angle: Float, color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .size(width = FoldChevronWidth, height = FoldChevronHeight)
            .graphicsLayer { rotationZ = angle },
    ) {
        val stroke = FoldChevronStroke.toPx()
        // Inset by half the stroke, so the round caps end exactly on the box
        // edges: the leftmost painted pixel is x = 0.
        val inset = stroke / 2f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(inset, inset)
            lineTo(size.width - inset, size.height / 2f)
            lineTo(inset, size.height - inset)
        }
        drawPath(
            path = path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

/** Visible size of the chevron (iOS chevron.right at 10 pt semibold). */
internal val FoldChevronWidth = 5.5.dp
internal val FoldChevronHeight = 10.dp
private val FoldChevronStroke = 1.6.dp
