package com.openminis.app.ui.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.git.GitCli.GitStatus
import com.openminis.app.git.GitStashEntry

/**
 * [T-git-panel-v2] Working tree grouped into Staged / Unstaged / Untracked, a
 * stashes section, and the sticky commit bar. Grouping answers "what would this
 * commit contain" at a glance — the flat list made staged and unstaged files
 * indistinguishable beyond checkbox state.
 */
@Composable
internal fun ChangesTab(state: GitPanelState, viewModel: GitPanelViewModel) {
    val entries = state.status?.entries.orEmpty()
    if (entries.isEmpty() && state.stashes.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            EmptyRows(stringResource(R.string.git_panel_clean), Modifier.weight(1f))
            CommitBar(state, viewModel)
        }
        return
    }
    val staged = entries.filter { it.staged && !it.untracked }
    val unstaged = entries.filter { !it.staged && !it.untracked }
    val untracked = entries.filter { it.untracked }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            if (staged.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.git_panel_section_staged)) }
                items(staged, key = { "s:${it.path}" }) { entry ->
                    FileEntryRow(
                        entry = entry,
                        enabled = !state.busy,
                        onToggleStage = { viewModel.toggleStage(entry) },
                        onOpenDiff = { viewModel.openFileDiff(entry) },
                    )
                    HorizontalDivider()
                }
            }
            if (unstaged.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.git_panel_section_unstaged)) }
                items(unstaged, key = { "u:${it.path}" }) { entry ->
                    FileEntryRow(
                        entry = entry,
                        enabled = !state.busy,
                        onToggleStage = { viewModel.toggleStage(entry) },
                        onOpenDiff = { viewModel.openFileDiff(entry) },
                    )
                    HorizontalDivider()
                }
            }
            if (untracked.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.git_panel_section_untracked)) }
                items(untracked, key = { "n:${it.path}" }) { entry ->
                    FileEntryRow(
                        entry = entry,
                        enabled = !state.busy,
                        onToggleStage = { viewModel.toggleStage(entry) },
                        onOpenDiff = { viewModel.openFileDiff(entry) },
                    )
                    HorizontalDivider()
                }
            }
            if (state.stashes.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.git_panel_section_stashes)) }
                items(state.stashes, key = { "stash:${it.index}" }) { stash ->
                    StashRowView(stash, enabled = !state.busy)
                }
            }
        }
        HorizontalDivider()
        CommitBar(state, viewModel)
    }
}

@Composable
private fun StashRowView(stash: GitStashEntry, enabled: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            "stash@{${stash.index}}",
            style = MaterialTheme.typography.labelSmall,
            color = DiffHunkColor,
        )
        Text(
            stash.subject,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Sticky bottom bar: AI message, stage-all, commit. */
@Composable
internal fun CommitBar(state: GitPanelState, viewModel: GitPanelViewModel) {
    val stagedCount = state.status?.entries?.count { it.staged } ?: 0
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.draftMessage,
                    onValueChange = viewModel::setDraftMessage,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.git_panel_commit_hint)) },
                    minLines = 1,
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = viewModel::generateCommitMessage,
                    enabled = !state.generating && !state.busy && stagedCount > 0,
                ) {
                    if (state.generating) {
                        CircularProgressIndicator(
                            Modifier.height(20.dp).width(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = stringResource(R.string.git_panel_generate),
                            tint = DiffHunkColor,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = viewModel::stageAll,
                    enabled = !state.busy && (state.status?.entries?.any { !it.staged } == true),
                ) {
                    Text(stringResource(R.string.git_panel_stage_all))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = viewModel::commit,
                    enabled = !state.busy && stagedCount > 0 && state.draftMessage.isNotBlank(),
                ) {
                    Text(stringResource(R.string.git_panel_commit) + " ($stagedCount)")
                }
            }
        }
    }
}

/** The status line above the tabs: branch · ahead/behind pills. */
@Composable
internal fun SyncRow(status: GitStatus?) {
    if (status == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            status.upstream ?: stringResource(R.string.git_panel_no_upstream),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (status.ahead > 0) CountPill("↑${status.ahead}", DiffAddColor)
        Spacer(Modifier.width(6.dp))
        if (status.behind > 0) CountPill("↓${status.behind}", DiffHunkColor)
    }
}
