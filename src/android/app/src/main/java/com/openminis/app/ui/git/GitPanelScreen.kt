package com.openminis.app.ui.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R

// iOS system palette, matching the colours ChatToolDetailUI already uses for
// tool status — the diff view should read like the rest of the app.
internal val GitAddGreen = Color(0xFF34C759)
internal val GitDelRed = Color(0xFFFF453A)
internal val GitHunkBlue = Color(0xFF007AFF)
internal val GitMetaGrey = Color(0xFF8E8E93)
internal val GitHairline = Color(0xFFE5E5EA)

/**
 * Native Git panel for a chat session's workspace — the mobile counterpart
 * of a desktop code client's Source Control view: status with per-file
 * staging, AI-generated commit messages, history graph, and coloured diffs.
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showBranchMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.git_panel_title))
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
                    // Disabled while a mutation settles — the old always-on
                    // button fired refresh() which silently returned (busy
                    // gate), leaving a tap with zero feedback.
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !state.busy,
                    ) {
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
                        GitPanelHeader(
                            state = state,
                            viewModel = viewModel,
                            onBranchClick = {
                    showBranchMenu = !showBranchMenu
                    // A closed switcher resets its filter — reopening with a
                    // stale search box would look like branches vanished.
                    if (!showBranchMenu) viewModel.setBranchSearch("")
                },
                        )
                        if (showBranchMenu) {
                            BranchMenu(
                                state = state,
                                onPick = { name ->
                                    viewModel.switchBranch(name)
                                    showBranchMenu = false
                                },
                                onSearch = viewModel::setBranchSearch,
                                onCreate = { name ->
                                    viewModel.createBranchAndCheckout(name)
                                    showBranchMenu = false
                                },
                            )
                        }
                        val changedCount = state.status?.entries?.size ?: 0
                        TabRow(selectedTabIndex = state.tab.ordinal) {
                            Tab(
                                selected = state.tab == GitPanelTab.Changes,
                                onClick = { viewModel.selectTab(GitPanelTab.Changes) },
                                text = {
                                    Text(
                                        stringResource(R.string.git_panel_changes) +
                                            if (changedCount > 0) " ($changedCount)" else "",
                                    )
                                },
                            )
                            Tab(
                                selected = state.tab == GitPanelTab.History,
                                onClick = { viewModel.selectTab(GitPanelTab.History) },
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
                                color = GitDelRed,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.clearNotice() }
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                maxLines = 3,
                            )
                        }
                        when (state.tab) {
                            GitPanelTab.Changes -> GitPanelChanges(state, viewModel)
                            GitPanelTab.History -> GitPanelHistory(state, viewModel)
                        }
                    }
                }
            }
        }
    }

    state.diff?.let { diff ->
        DiffOverlay(diff = diff, onClose = viewModel::closeDiff)
    }
    state.commitSheet?.let { sheet ->
        GitPanelCommitSheet(
            sheet = sheet,
            onDismiss = viewModel::closeCommitSheet,
            onOpenFile = { path -> viewModel.openCommitFileDiff(sheet.hash, path) },
        )
    }
}

/** Non-repo fallback: one line of what's wrong, plus the only sane action. */
@Composable
private fun NotRepoView(error: String?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.git_panel_not_repo),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (!error.isNullOrBlank()) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
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
