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
 * [T-tool-card-semantic-detail v337] Apple-style tool-card label.
 *
 * Two distinct meanings, two distinct typographic roles — never merged:
 *   Primary   semantic title (model `tool_title` or friendly tool name),
 *             13sp / Medium / onSurface — the "detail" lost after #334/#336.
 *   Secondary full argument summary (path / pattern / command), 11sp /
 *             regular / onSurfaceVariant — the value that used to REPLACE
 *             the headline.
 *
 * The detail sheet optionally adds a small tool-type eyebrow ("Minis is
 * reading File") above both, preserving the pre-#334 affordance in Apple's
 * three-tier header style without growing any baseline-capped UI file.
 * Empty detail collapses to a single line so thinking cards and tools with
 * no arguments keep their old density.
 */
@Composable
internal fun ToolCardLabel(
    presentation: ToolCardPresentation,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
    eyebrow: String = "",
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
        if (presentation.detail.isNotEmpty()) {
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
