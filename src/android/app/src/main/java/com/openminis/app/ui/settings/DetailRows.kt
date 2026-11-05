package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.i18n.uppercaseForDisplay
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-detail-rows-unify] Shared row/section scaffolding for the settings
 * package. Three screens each carried a private copy of this furniture —
 * SkillsManagementScreen (section card + clickable row + divider),
 * BackupHistoryDetailScreen (LabeledContent-style row + inset divider) and
 * UsageStatsScreen (compact stat row) — that had drifted independently. This
 * file is the single home; each screen keeps its exact visuals through the
 * parameterization below:
 *
 *   - [DetailSection]  — iOS grouped-card section header (SkillsManagement)
 *   - [DetailRow]      — icon/label row with click + trailing content slot
 *                        (SkillsManagement detail page)
 *   - [DetailValueRow] — label left, value/content right, optional chevron +
 *                        trailing divider (BackupHistoryDetail, the peer of
 *                        iOS LabeledContent)
 *   - [StatDetailRow]  — compact two-text SpaceBetween row (UsageStats)
 *   - [DetailDivider]  — inset hairline; per-screen inset/alpha via params
 */

/** A grouped card section matching iOS List section style (SkillsManagement). */
@Composable
internal fun DetailSection(
    header: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    ) {
        if (header != null) {
            Text(
                text = header.uppercaseForDisplay(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            content()
        }
    }
}

/** Row with optional click + arbitrary content (SkillsManagement detail page). */
@Composable
internal fun DetailRow(
    clickable: Boolean = false,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Label on the left, value on the right — the peer of iOS LabeledContent
 *  (BackupHistoryDetail). */
@Composable
internal fun DetailValueRow(
    label: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    content: @Composable (() -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                when {
                    content != null -> content()
                    // The package name is long enough to need two lines; every
                    // other value is short. Right-aligned so both read as one
                    // trailing column.
                    value != null -> Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
            if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    m3Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) DetailDivider(insetStart = 14.dp)
    }
}

/** Compact label/value stat row (UsageStats). */
@Composable
internal fun StatDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Default)
    }
}

/** Inset hairline divider. [insetEnd] and [alpha] keep the variants that used
 *  to live separately in SkillsManagement (both insets + half alpha) and
 *  BackupHistoryDetail (start-only inset, full alpha) on one implementation. */
@Composable
internal fun DetailDivider(
    insetStart: Dp = 14.dp,
    insetEnd: Dp = 0.dp,
    alpha: Float = 1f,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = insetStart, end = insetEnd)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha)),
    )
}
