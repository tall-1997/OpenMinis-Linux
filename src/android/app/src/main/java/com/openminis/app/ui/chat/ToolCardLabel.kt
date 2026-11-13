package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * [T-tool-card-row-single-line v338] Apple-style tool-card label.
 *
 * Two distinct meanings, two distinct typographic roles — never merged:
 *   Primary   semantic title (model `tool_title` or friendly tool name),
 *             13sp / Medium / onSurface — the "detail" lost after #334/#336.
 *   Secondary full argument summary (path / pattern / command), 11sp /
 *             regular / onSurfaceVariant — detail-sheet-only since v338.
 *
 * v338: the COLLAPSED row in the message list renders the primary line only
 * (user feedback: the path line under the headline hurt the list's visual
 * rhythm). The argument summary survives in the detail sheet, where the
 * three-tier header (eyebrow / headline / argument line) keeps the pre-#334
 * affordance. [collapsedRowLabel] is the single settlement point for what the
 * collapsed row consumes; ChatProcessCards routes through it.
 *
 * Empty detail collapses to a single line so thinking cards and tools with
 * no arguments keep their old density.
 */

/** What the collapsed message-list row renders: the semantic headline only. */
internal fun collapsedRowLabel(presentation: ToolCardPresentation): String = presentation.title

@Composable
internal fun ToolCardLabel(
    presentation: ToolCardPresentation,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
    eyebrow: String = "",
    showDetail: Boolean = true,
) {
    Column(modifier = modifier) {
        if (eyebrow.isNotEmpty()) {
            Text(
                text = eyebrow,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        Text(
            text = presentation.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (failed) ToolErrorColor else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showDetail && presentation.detail.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = presentation.detail,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
