package com.openminis.app.ui.git

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.git.GitCli.GitCommit

/**
 * History tab: local search, self-drawn commit graph (lanes from
 * [graphLayout]) and bottom-triggered pagination. Rows open the
 * commit-detail sheet.
 */
@Composable
internal fun GitPanelHistory(state: GitPanelState, viewModel: GitPanelViewModel) {
    val rows = graphLayout(filterHistory(state.log, state.searchQuery))
    val laneCount = (rows.maxOfOrNull { r -> r.activeLanes.maxOrNull() ?: r.lane } ?: 0) + 1
    Column(Modifier.fillMaxSize()) {
        SearchField(state.searchQuery, viewModel::setSearchQuery)
        when {
            rows.isEmpty() && state.searchQuery.isBlank() -> EmptyHistory()
            rows.isEmpty() -> NoSearchMatch()
            else -> LazyColumn(Modifier.weight(1f)) {
                items(rows.size, key = { rows[it].commit.hash }) { i ->
                    val row = rows[i]
                    CommitRow(
                        row = row,
                        laneCount = laneCount,
                        onClick = { viewModel.openCommitSheet(row.commit.hash) },
                    )
                }
                if (state.searchQuery.isBlank()) {
                    item(key = "footer") {
                        HistoryFooter(
                            loadingMore = state.logLoadingMore,
                            hasMore = state.logHasMore,
                            onLoadMore = viewModel::loadMoreLogs,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.git_panel_search_history)) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ColumnScope.EmptyHistory() {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.git_panel_no_commits),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.NoSearchMatch() {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.git_panel_search_no_match),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CommitRow(row: CommitLaneRow, laneCount: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LaneCanvas(row = row, laneCount = laneCount)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                row.commit.subject,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                "${row.commit.shortHash} · ${row.commit.author} · " +
                    relativeTime(row.commit.epochSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Graph strip for one row: rails for every active lane, then the node —
 * merge double-ring, fork diamond, otherwise a plain dot. Rails are drawn
 * full-height so adjacent rows connect seamlessly.
 */
@Composable
private fun LaneCanvas(row: CommitLaneRow, laneCount: Int) {
    val lanes = laneCount.coerceAtLeast(1)
    Canvas(
        Modifier
            .width((lanes * 14).dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val laneW = size.width / lanes
        val cy = size.height / 2f
        fun laneX(lane: Int) = laneW * lane + laneW / 2f
        for (lane in row.activeLanes) {
            val x = laneX(lane)
            drawLine(
                color = Color(0xFFC7C7CC),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
        val nx = laneX(row.lane)
        when {
            row.isMerge -> {
                drawCircle(
                    color = GitHunkBlue,
                    radius = 6.5.dp.toPx(),
                    center = Offset(nx, cy),
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawCircle(
                    color = GitHunkBlue,
                    radius = 3.dp.toPx(),
                    center = Offset(nx, cy),
                )
            }
            row.isFork -> {
                val r = 5.5.dp.toPx()
                val path = Path().apply {
                    moveTo(nx, cy - r)
                    lineTo(nx + r, cy)
                    lineTo(nx, cy + r)
                    lineTo(nx - r, cy)
                    close()
                }
                drawPath(path, Color(0xFFFF9500))
            }
            else -> drawCircle(
                color = GitHunkBlue,
                radius = 4.5.dp.toPx(),
                center = Offset(nx, cy),
            )
        }
    }
}

@Composable
private fun HistoryFooter(
    loadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            loadingMore -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            hasMore -> LaunchedEffect(Unit) { onLoadMore() }
        }
    }
}

/** Compact relative time: seconds/minutes/hours/days, otherwise absolute. */
internal fun relativeTime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val now = System.currentTimeMillis()
    val then = epochSeconds * 1000L
    val diff = now - then
    val minute = 60_000L
    val hour = 60L * minute
    val day = 24L * hour
    return when {
        diff < minute -> "now"
        diff < hour -> "${diff / minute}m"
        diff < day -> "${diff / hour}h"
        diff < 14L * day -> "${diff / day}d"
        else -> {
            // java.text, not DateUtils.formatDateTime(null, …): the Android
            // overload is not documented to tolerate a null context, and a
            // crash inside a LazyColumn row painter is fatal to the panel.
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            fmt.format(java.util.Date(then))
        }
    }
}
