package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared "no content yet" placeholder: a centered icon above one (optionally
 * two) muted line(s), inside a full-size 32dp-padded box.
 *
 * [v3-P2-4] Replaces the three ad-hoc private empty states (scheduled tasks,
 * scheduled task runs, mounted folders) that each re-implemented the same
 * Box(padding 32) + Column(center) + Icon(onSurfaceVariant) + muted text
 * rhythm. The component encodes their common denominator:
 *   - 64dp icon (override with [iconSize]) tinted onSurfaceVariant
 *   - 16dp gap before [text], rendered centered in onSurfaceVariant
 *   - optional [subText] rendered 4dp below in bodyMedium
 *
 * Pass the icon exactly as it should render — the component does not wrap it
 * in [com.openminis.app.ui.theme.m3Icon]; callers keep that decision at the
 * call site (e.g. `EmptyStateHint(m3Icon(Icons.Outlined.FolderShared), ...)`).
 */
@Composable
fun EmptyStateHint(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    subText: String? = null,
    iconSize: Dp = 64.dp,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (subText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
