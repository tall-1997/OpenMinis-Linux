package com.openminis.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.git.GitCli.GitFileEntry

// iOS system palette, matching the colours ChatToolDetailUI already uses for
// tool status — the diff view should read like the rest of the app.
internal val DiffAddColor = Color(0xFF34C759)
internal val DiffDelColor = Color(0xFFFF453A)
internal val DiffHunkColor = Color(0xFF007AFF)
internal val DiffMetaColor = Color(0xFF8E8E93)

internal fun statusColor(code: Char): Color = when (code.uppercaseChar()) {
    'M', 'T' -> Color(0xFFFF9500) // modified — orange
    'A' -> DiffAddColor // added — green
    'D' -> DiffDelColor // deleted — red
    'R', 'C' -> DiffHunkColor // renamed/copied — blue
    '?' -> DiffMetaColor // untracked — gray
    else -> Color(0xFFFF2D55) // conflicts & friends — pink
}

/** One working-tree entry: status badge + path + rename source + stage checkbox. */
@Composable
internal fun FileEntryRow(
    entry: GitFileEntry,
    enabled: Boolean,
    onToggleStage: () -> Unit,
    onOpenDiff: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onOpenDiff)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(statusColor(entry.code), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                entry.code.toString(),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            val sub = buildString {
                val dir = entry.path.substringBeforeLast('/', "")
                if (dir.isNotEmpty()) append(dir)
                if (entry.origPath != null && entry.origPath != entry.path) {
                    if (isNotEmpty()) append(" · ")
                    append(entry.origPath)
                }
            }
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        // Staged toggle. Untracked files show an "add" affordance instead:
        // checking the box stages (git add), unchecking restores --staged.
        Checkbox(
            checked = entry.staged,
            onCheckedChange = if (enabled) { _ -> onToggleStage() } else null,
        )
    }
}

/** Small pill used by the branch bar (ahead/behind counters). */
@Composable
internal fun CountPill(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontSize = 11.sp,
        )
    }
}

/** Empty-state row shared by tabs. */
@Composable
internal fun EmptyRows(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/** Section header inside a tab (e.g. "Stashes"). */
@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** Compact action row used for stash entries. */
@Composable
internal fun StashRow(
    title: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}
