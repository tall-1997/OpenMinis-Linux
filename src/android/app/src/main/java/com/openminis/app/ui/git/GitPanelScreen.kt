package com.openminis.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R

/**
 * [T-git-panel-v2] The screen shell: top bar with branch switcher, sync row
 * (upstream + ahead/behind + push/pull), tabbed Changes/History with search,
 * and the full-screen diff overlay. Rendering lives in GitPanelChanges /
 * GitPanelHistory / GitPanelRows; state lives in [GitPanelViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitPanelScreen(
    viewModel: GitPanelViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var showBranchMenu by remember { androidx.compose.runtime.mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.git_panel_title))
                        val repo = state.repo
                        if (repo != null && repo.isRepo) {
                            val label = repo.relativeRoot.ifBlank {
                                repo.root.substringAfterLast('/').ifBlank { repo.root }
                            }
                            Text(
                                "${repo.branch.ifBlank { "HEAD" }} · $label",
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
                        // Branch switcher row: tap opens the picker; a scrim
                        // behind the list closes it on outside taps.
                        Box {
                            if (showBranchMenu) {
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .background(androidx.compose.ui.graphics.Color.Transparent)
                                        .clickable { showBranchMenu = false },
                                )
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { showBranchMenu = true }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    state.repo?.branch?.ifBlank { "HEAD" } ?: "HEAD",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "  ▾  ${state.branches.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (showBranchMenu) {
                                BranchMenu(
                                    branches = state.branches,
                                    onPick = {
                                        showBranchMenu = false
                                        viewModel.switchBranch(it)
                                    },
                                    onDismiss = { showBranchMenu = false },
                                )
                            }
                        }
                        SyncRow(status)
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
                            HistorySearchRow()
                            HistoryTab(state, viewModel, historyQueryValue.text)
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

/** Shared history search text, hoisted so the row and the tab agree. */
private val historyQuery = androidx.compose.runtime.mutableStateOf(TextFieldValue(""))
private var historyQueryValue by historyQuery

@Composable
private fun HistorySearchRow() {
    OutlinedTextField(
        value = historyQueryValue,
        onValueChange = { historyQueryValue = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(stringResource(R.string.git_panel_search_hint)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

/** Bounded branch dropdown; remote branches are marked and create trackers. */
@Composable
private fun BranchMenu(
    branches: List<com.openminis.app.git.GitBranchRef>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        LazyColumn(Modifier.padding(vertical = 4.dp)) {
            items(branches, key = { it.name }) { branch ->
                Text(
                    text = (if (branch.isCurrent) "● " else "") +
                        (if (branch.name.contains('/')) "⇄ " else "") +
                        branch.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(branch.name) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (branches.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.git_panel_no_branches),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun NotRepoView(error: String?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.git_panel_not_repo),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!error.isNullOrBlank()) {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(12.dp))
            Text(
                error.take(300),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = DiffMetaColor,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiffOverlay(diff: GitDiffView, onClose: () -> Unit) {
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
                        // (#13) colorizeDiff walks the whole diff line-by-line
                        // building spans — memoized per content so an unrelated
                        // state change never re-colors an arbitrary diff.
                        val colored = remember(diff.content) { colorizeDiff(diff.content) }
                        Text(
                            colored,
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
