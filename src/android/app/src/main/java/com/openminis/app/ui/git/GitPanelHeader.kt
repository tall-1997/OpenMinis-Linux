package com.openminis.app.ui.git

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.WarningAmber
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

/**
 * Repository head card, pinned above the tab row: branch pill, ↑↓
 * divergence badges, remote and the most recent commit — the three facts
 * every git screen should answer at a glance but the old panel never showed.
 */
@Composable
internal fun GitPanelHeader(state: GitPanelState) {
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
                BranchPill(repo.branch)
                if (state.status != null) {
                    Spacer(Modifier.width(6.dp))
                    DivergenceBadge(up = state.status?.ahead ?: 0, kind = "up")
                    Spacer(Modifier.width(4.dp))
                    DivergenceBadge(up = state.status?.behind ?: 0, kind = "down")
                }
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
                        stringResource(R.string.git_panel_no_identity),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB36A00),
                    )
                }
            }
        }
    }
}

@Composable
private fun BranchPill(branch: String) {
    Box(
        Modifier
            .background(GitHunkBlue, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            branch.ifBlank { "HEAD" },
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
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
            if (kind == "up") Icons.Filled.CallMerge else Icons.Filled.CallMerge,
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

/** Header title: relative repo path preferred, absolute root as fallback. */
internal fun repoName(repo: com.openminis.app.git.GitCli.GitRepoInfo): String {
    if (repo.relativeRoot.isNotBlank()) return repo.relativeRoot
    val name = repo.root.trimEnd('/').substringAfterLast('/')
    return name.ifBlank { repo.root }
}
