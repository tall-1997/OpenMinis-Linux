package com.openminis.app.ui.git

import android.text.format.DateUtils
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.git.GitCli.GitCommit

/**
 * [T-git-panel-v2] Commit list with graph-ish indents (the first row of each
 * day gets a date separator), search filtering and a per-commit diff.
 */
@Composable
internal fun HistoryTab(state: GitPanelState, viewModel: GitPanelViewModel, query: String) {
    val commits = rememberCommits(state.log, query)
    if (commits.isEmpty()) {
        EmptyRows(
            if (query.isBlank()) stringResource(R.string.git_panel_no_commits)
            else stringResource(R.string.git_panel_no_search_hits, query),
            Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(commits, key = { it.hash }) { commit ->
            val firstOfGroup = commit.epochSeconds / 86_400L !=
                (commits.getOrNull(commits.indexOf(commit) - 1)?.epochSeconds ?: 0L) / 86_400L
            if (firstOfGroup) DaySeparator(commit.epochSeconds)
            CommitRow(commit, onClick = { viewModel.openCommitDiff(commit) })
            HorizontalDivider()
        }
    }
}

/** Case-insensitive subject/hash/author filter, memoized per (log, query). */
@Composable
private fun rememberCommits(log: List<GitCommit>, query: String): List<GitCommit> =
    androidx.compose.runtime.remember(log, query) {
        if (query.isBlank()) log
        else log.filter {
            it.subject.contains(query, true) ||
                it.shortHash.contains(query, true) ||
                it.author.contains(query, true)
        }
    }

@Composable
private fun DaySeparator(epochSeconds: Long) {
    val label = DateUtils.formatDateTime(
        androidx.compose.ui.platform.LocalContext.current,
        epochSeconds * 1000L,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = DiffMetaColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
internal fun CommitRow(commit: GitCommit, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(commit.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        Spacer(Modifier.height(2.dp))
        Text(
            buildString {
                append(commit.shortHash)
                append(" · ")
                append(commit.author)
                append(" · ")
                append(relativeTime(commit.epochSeconds))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
internal fun relativeTime(epochSeconds: Long): String {
    val text = DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1000L,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    )
    return text?.toString() ?: ""
}
