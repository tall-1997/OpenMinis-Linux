package com.openminis.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.git.GitCli.GitFileEntry
import com.openminis.app.git.GitCli.GitStashEntry
import com.openminis.app.git.GitCli.NumStat

/**
 * Changes tab: stats bar, staged/working sections and per-file rows with
 * numstat counts. The list is driven by `git status` entries; numstat maps
 * are keyed by path and read per row so binary/untracked rows degrade
 * gracefully to badges instead of fake numbers.
 */
@Composable
internal fun GitPanelChanges(state: GitPanelState, viewModel: GitPanelViewModel) {
    val entries = state.status?.entries.orEmpty()
    Column(Modifier.fillMaxSize()) {
        if (entries.isEmpty() && state.stashes.isEmpty()) {
            // Clean tree, nothing stashed: keep the calm empty state but
            // still answer the question "is there a stash?" — the section
            // label stays visible so the area doesn't vanish entirely.
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.git_panel_clean),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.git_panel_no_stashes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 14.dp),
                )
            }
        } else {
            val stagedRows = entries.filter { it.staged }
            val changeRows = entries.filter { !it.staged }
            LazyColumn(Modifier.weight(1f)) {
                if (entries.isNotEmpty()) {
                    item(key = "stats") {
                        StatsBar(changesTotals(state.status, state.stagedNumstat, state.unstagedNumstat))
                    }
                    if (stagedRows.isNotEmpty()) {
                        item(key = "staged-header") {
                            SectionHeader(stringResource(R.string.git_panel_staged), stagedRows.size)
                        }
                        items(stagedRows, key = { "s:${it.path}:${it.code}" }) { entry ->
                            FileEntryRow(
                                entry = entry,
                                stat = state.stagedNumstat[entry.path],
                                enabled = !state.busy,
                                onToggleStage = { viewModel.toggleStage(entry) },
                                onOpenDiff = { viewModel.openFileDiff(entry) },
                            )
                            HorizontalDivider(color = GitHairline)
                        }
                    }
                    if (changeRows.isNotEmpty()) {
                        item(key = "changes-header") {
                            SectionHeader(
                                stringResource(R.string.git_panel_changes_section),
                                changeRows.size,
                            )
                        }
                        items(changeRows, key = { "w:${it.path}:${it.code}" }) { entry ->
                            FileEntryRow(
                                entry = entry,
                                stat = state.unstagedNumstat[entry.path],
                                enabled = !state.busy,
                                onToggleStage = { viewModel.toggleStage(entry) },
                                onOpenDiff = { viewModel.openFileDiff(entry) },
                            )
                            HorizontalDivider(color = GitHairline)
                        }
                    }
                }
                if (state.stashes.isNotEmpty()) {
                    item(key = "stashes-header") {
                        SectionHeader(stringResource(R.string.git_panel_section_stashes), state.stashes.size)
                    }
                    items(state.stashes, key = { "stash:${it.index}" }) { stash ->
                        StashRowView(stash)
                        HorizontalDivider(color = GitHairline)
                    }
                }
            }
        }
        HorizontalDivider(color = GitHairline)
        GitPanelCommitBar(state, viewModel)
    }
}

@Composable
private fun StashRowView(stash: GitStashEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Bookmark,
            contentDescription = null,
            tint = GitHunkBlue,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "stash@{${stash.index}}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                stash.subject,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatsBar(totals: FileStatTotals) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            stringResource(R.string.git_panel_stats_files, totals.files),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (totals.added > 0) {
            Text(
                "+${totals.added}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = GitAddGreen,
            )
        }
        if (totals.deleted > 0) {
            Spacer(Modifier.width(10.dp))
            Text(
                "-${totals.deleted}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = GitDelRed,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "($count)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun FileEntryRow(
    entry: GitFileEntry,
    stat: NumStat?,
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
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildString {
                val dir = entry.path.substringBeforeLast('/', "")
                val renameSource = entry.origPath.takeIf { it != entry.path }
                if (dir.isNotEmpty()) append(dir)
                if (renameSource != null) {
                    if (dir.isNotEmpty()) append(" · ")
                    append(renameSource)
                }
            }
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (entry.untracked) {
            Surface(
                color = GitHunkBlue.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    stringResource(R.string.git_panel_untracked_badge),
                    color = GitHunkBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (stat != null && stat.binary) {
            Text(
                "BIN",
                style = MaterialTheme.typography.bodySmall,
                color = GitMetaGrey,
            )
            Spacer(Modifier.width(8.dp))
        } else if (stat != null && (stat.added > 0 || stat.deleted > 0)) {
            Text(
                "+${stat.added}",
                style = MaterialTheme.typography.bodySmall,
                color = GitAddGreen,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "-${stat.deleted}",
                style = MaterialTheme.typography.bodySmall,
                color = GitDelRed,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
        }
        Checkbox(
            checked = entry.staged,
            onCheckedChange = if (enabled) { _ -> onToggleStage() } else null,
        )
    }
}

/** Status-letter badge colour — shared by changes rows and diff headers. */
internal fun statusColor(code: Char): Color = when (code.uppercaseChar()) {
    'M', 'T' -> Color(0xFFFF9500) // modified — orange
    'A' -> GitAddGreen // added — green
    'D' -> GitDelRed // deleted — red
    'R', 'C' -> GitHunkBlue // renamed/copied — blue
    '?' -> GitMetaGrey // untracked — gray
    else -> Color(0xFFFF2D55) // conflicts & friends — pink
}
