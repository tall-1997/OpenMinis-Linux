package com.openminis.app.ui.git

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.git.GitCli.GitCommit
import com.openminis.app.git.GitCli.GitFileEntry

// iOS system palette, matching the colours ChatToolDetailUI already uses for
// tool status — the diff view should read like the rest of the app.
private val DiffAddColor = Color(0xFF34C759)
private val DiffDelColor = Color(0xFFFF453A)
private val DiffHunkColor = Color(0xFF007AFF)
private val DiffMetaColor = Color(0xFF8E8E93)

/**
 * Native Git panel for a chat session's workspace — the mobile counterpart
 * of a desktop code client's Source Control view: status with per-file
 * staging, AI-generated commit messages, history, and coloured diffs.
 *
 * Read/commit only — no destructive operations (discard/reset/rebase) are
 * exposed here on purpose; the terminal remains the escape hatch for those.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitPanelScreen(
    viewModel: GitPanelViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.git_panel_title))
                        val repo = state.repo
                        if (repo != null && repo.isRepo) {
                            Text(
                                "${repo.branch.ifBlank { "HEAD" }} · ${repo.root}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.git_panel_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.git_panel_refresh),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.repo == null -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                state.repo?.isRepo != true -> {
                    NotRepoView(state.repo?.error)
                }

                else -> {
                    Column(Modifier.fillMaxSize()) {
                        val status = state.status
                        val changedCount = status?.entries?.size ?: 0
                        TabRow(selectedTabIndex = tab) {
                            Tab(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                text = {
                                    Text(
                                        stringResource(R.string.git_panel_changes) +
                                            if (changedCount > 0) " ($changedCount)" else "",
                                    )
                                },
                            )
                            Tab(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                text = {
                                    Text(
                                        stringResource(R.string.git_panel_history) +
                                            if (state.log.isNotEmpty()) " (${state.log.size})" else "",
                                    )
                                },
                            )
                        }
                        state.notice?.let { notice ->
                            Text(
                                notice,
                                style = MaterialTheme.typography.bodySmall,
                                color = DiffDelColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.clearNotice() }
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                maxLines = 3,
                            )
                        }
                        if (tab == 0) {
                            ChangesTab(state, viewModel)
                        } else {
                            HistoryTab(state, viewModel)
                        }
                    }
                }
            }

            state.diff?.let { diff ->
                DiffOverlay(diff, onClose = viewModel::closeDiff)
            }
        }
    }
}

@Composable
private fun NotRepoView(error: String?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.git_panel_not_repo),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                error.take(300),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = DiffMetaColor,
            )
        }
    }
}

@Composable
private fun ChangesTab(state: GitPanelState, viewModel: GitPanelViewModel) {
    val entries = state.status?.entries.orEmpty()
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            if (entries.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.git_panel_clean),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(entries, key = { "${if (it.staged) "s" else "w"}:${it.path}:${it.code}" }) { entry ->
                FileEntryRow(
                    entry = entry,
                    enabled = !state.busy,
                    onToggleStage = { viewModel.toggleStage(entry) },
                    onOpenDiff = { viewModel.openFileDiff(entry) },
                )
                HorizontalDivider()
            }
        }
        HorizontalDivider()
        CommitBar(state, viewModel)
    }
}

@Composable
private fun FileEntryRow(
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
                if (entry.origPath != null) {
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
        // checking the box stages (git add), unchecking is a no-op path
        // through the same setStaged(false) → restore --staged, which git
        // accepts for intent-to-add-free untracked entries only after add.
        androidx.compose.material3.Checkbox(
            checked = entry.staged,
            onCheckedChange = if (enabled) { _ -> onToggleStage() } else null,
        )
    }
}

private fun statusColor(code: Char): Color = when (code.uppercaseChar()) {
    'M', 'T' -> Color(0xFFFF9500) // modified — orange
    'A' -> DiffAddColor // added — green
    'D' -> DiffDelColor // deleted — red
    'R', 'C' -> DiffHunkColor // renamed/copied — blue
    '?' -> DiffMetaColor // untracked — gray
    else -> Color(0xFFFF2D55) // conflicts & friends — pink
}

@Composable
private fun CommitBar(state: GitPanelState, viewModel: GitPanelViewModel) {
    val stagedCount = state.status?.entries?.count { it.staged } ?: 0
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
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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

@Composable
private fun HistoryTab(state: GitPanelState, viewModel: GitPanelViewModel) {
    if (state.log.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.git_panel_no_commits),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.log, key = { it.hash }) { commit ->
            CommitRow(commit, onClick = { viewModel.openCommitDiff(commit) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun CommitRow(commit: GitCommit, onClick: () -> Unit) {
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
private fun relativeTime(epochSeconds: Long): String {
    val text = DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1000L,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    )
    return text?.toString() ?: ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffOverlay(diff: GitDiffView, onClose: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(diff.title, maxLines = 1, style = MaterialTheme.typography.titleSmall) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.git_panel_close_diff),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                diff.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                diff.content.isBlank() -> Text(
                    stringResource(R.string.git_panel_empty_diff),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            colorizeDiff(diff.content),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Unified-diff colouring: additions green, deletions red, hunk headers blue,
 * file/index metadata gray. Whole overlay is one selectable Text so users can
 * copy hunks — same interaction the chat's inline diff preview offers.
 */
internal fun colorizeDiff(diff: String) = buildAnnotatedString {
    diff.lineSequence().forEachIndexed { index, line ->
        if (index > 0) append("\n")
        val style = when {
            line.startsWith("+++") || line.startsWith("---") ->
                SpanStyle(color = DiffMetaColor, fontWeight = FontWeight.Bold)
            line.startsWith("+") -> SpanStyle(color = DiffAddColor)
            line.startsWith("-") -> SpanStyle(color = DiffDelColor)
            line.startsWith("@@") -> SpanStyle(color = DiffHunkColor)
            line.startsWith("diff ") || line.startsWith("index ") ||
                line.startsWith("new file") || line.startsWith("deleted file") ||
                line.startsWith("similarity") || line.startsWith("rename") ||
                line.startsWith("old mode") || line.startsWith("new mode") ||
                line.startsWith("Binary files") -> SpanStyle(color = DiffMetaColor)
            else -> SpanStyle()
        }
        withStyle(style) { append(line) }
    }
}

/**
 * Navigation entry point: builds the session-scoped [GitPanelViewModel].
 * providerRepository is threaded from AppNavigation (same instance the chat
 * uses) so AI commit-message generation picks the user's configured models.
 */
@Composable
fun GitPanelRoute(
    sessionId: String,
    providerRepository: com.openminis.app.data.repository.ProviderRepository,
    onBack: () -> Unit,
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
        as android.app.Application
    val viewModel: GitPanelViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                GitPanelViewModel(app, sessionId, providerRepository) as T
        },
    )
    GitPanelScreen(viewModel = viewModel, onBack = onBack)
}
