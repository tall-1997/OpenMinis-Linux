package com.openminis.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R

/**
 * Repository head card, pinned above the tab row: branch pill, ↑↓
 * divergence badges, remote and the most recent commit — the three facts
 * every git screen should answer at a glance but the old panel never showed.
 */
@Composable
internal fun GitPanelHeader(
    state: GitPanelState,
    viewModel: GitPanelViewModel,
    onBranchClick: () -> Unit,
) {
    val repo = state.repo ?: return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            // Row 1: repo name + branch pill + divergence badges
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    repoName(repo),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                BranchPill(repo.branch, onClick = onBranchClick)
                if (state.status != null) {
                    Spacer(Modifier.width(6.dp))
                    DivergenceBadge(up = state.status?.ahead ?: 0, kind = "up")
                    Spacer(Modifier.width(4.dp))
                    DivergenceBadge(up = state.status?.behind ?: 0, kind = "down")
                }
            }

            // Row 2: sync — upstream + push/pull
            state.status?.let { status ->
                Spacer(Modifier.height(8.dp))
                SyncRow(status = status, busy = state.busy, viewModel = viewModel)
            }

            // Row 2: remote (only when one exists)
            if (!state.remote.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        state.remote.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Row 3: most recent commit
            state.lastCommit?.let { last ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "${last.shortHash} ${last.subject}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Row 4: identity warning — commits fall back to hostname guesses
            if (repo.userName.isNullOrBlank() || repo.userEmail.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color(0x33FF9500),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = Color(0xFFFF9500),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        // Same amber as the icon — the darker tone was
                        // unreadable on dark-theme surfaces.
                        stringResource(R.string.git_panel_no_identity),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9500),
                    )
                }
            }
        }
    }
}

@Composable
private fun BranchPill(branch: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(GitHunkBlue, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            // Detached HEAD has no branch name — label it honestly instead
            // of showing an empty pill (ZCode's detached label).
            branch.ifBlank { stringResource(R.string.git_panel_detached_head) },
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Filled.CallMerge,
            contentDescription = stringResource(R.string.git_panel_switch_branch),
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(11.dp),
        )
    }
}

/**
 * Upstream line + push/pull actions. The pull button is disabled without an
 * upstream; push stays enabled — git asks for one on first push.
 */
@Composable
private fun SyncRow(status: com.openminis.app.git.GitCli.GitStatus, busy: Boolean, viewModel: GitPanelViewModel) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            status.upstream ?: stringResource(R.string.git_panel_no_upstream),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = viewModel::pull,
            enabled = !busy && status.upstream != null,
        ) {
            Text(stringResource(R.string.git_panel_pull))
        }
        Spacer(Modifier.width(4.dp))
        TextButton(
            onClick = viewModel::push,
            enabled = !busy,
        ) {
            Text(stringResource(R.string.git_panel_push))
        }
    }
}

/**
 * Branch switcher, ZCode GitBranchSwitcher shape: search box over the
 * branch list, the current branch first with its uncommitted-changes count,
 * remote refs with a ⇄ glyph, and a "create and check out" entry. Picking
 * one checks it out (remote picks create the local tracking branch).
 */
@Composable
internal fun BranchMenu(
    state: GitPanelState,
    onPick: (String) -> Unit,
    onSearch: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    val query = state.branchSearch.trim()
    val visible = if (query.isEmpty()) {
        state.branches
    } else {
        state.branches.filter { it.name.contains(query, ignoreCase = true) }
    }
    val uncommitted = state.status?.entries
        ?.distinctBy { it.path }
        ?.size ?: 0
    var showCreate by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            TextField(
                value = state.branchSearch,
                onValueChange = onSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.git_panel_branch_search)) },
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
            // Capped: an unconstrained LazyColumn here would expand to the whole
            // viewport and push the tab row and its content off-screen.
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(visible, key = { it.name }) { branch ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(branch.name) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        if (branch.isCurrent) {
                            Text("●", color = GitAddGreen, fontSize = 11.sp)
                        } else if (branch.name.contains('/')) {
                            Text("⇄", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        } else {
                            Text("○", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                branch.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (branch.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (branch.isCurrent && uncommitted > 0) {
                                Text(
                                    // ZCode's "未提交的更改：N 个文件" line under
                                    // the current branch.
                                    stringResource(R.string.git_panel_uncommitted_count, uncommitted),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else if (branch.lastSubject.isNotBlank()) {
                                Text(
                                    branch.lastSubject,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (branch.isCurrent) {
                            Text("✓", color = GitAddGreen, fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    HorizontalDivider(color = GitHairline)
                }
                if (visible.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.git_panel_no_branches),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
            // ZCode's "创建并检出新分支…" entry.
            Text(
                stringResource(R.string.git_panel_create_branch),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCreate = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
    if (showCreate) {
        CreateBranchDialog(
            onDismiss = { showCreate = false },
            onCreate = { name ->
                showCreate = false
                onCreate(name)
            },
        )
    }
}

@Composable
private fun CreateBranchDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_panel_create_branch)) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.git_panel_branch_name_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun DivergenceBadge(up: Int, kind: String) {
    if (up <= 0) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(GitHairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Icon(
            if (kind == "up") Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
            contentDescription = null,
            tint = if (kind == "up") GitAddGreen else GitDelRed,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(
            "$up",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Header title: discovery basename preferred, root basename as fallback. */
internal fun repoName(repo: com.openminis.app.git.GitCli.GitRepoInfo): String {
    if (repo.relativeRoot.isNotBlank()) return repo.relativeRoot
    val name = repo.root.trimEnd('/').substringAfterLast('/')
    return name.ifBlank { repo.root }
}
