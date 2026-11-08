package com.openminis.app.ui.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.git.GitCli.CommitFileStat

/**
 * Commit-detail bottom sheet: subject, author/committer/time, body, then the
 * file list with numstat counts. Tapping a file opens its diff; the hash row
 * copies to the clipboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitPanelCommitSheet(
    sheet: GitCommitSheet,
    onDismiss: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            val error = sheet.error
            val detail = sheet.detail
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (detail != null) {
                Text(
                    detail.subject,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(10.dp))
                MetaRow(stringResource(R.string.git_panel_author_label), detail.author)
                MetaRow(
                    stringResource(R.string.git_panel_committer),
                    detail.committer,
                )
                MetaRow(
                    stringResource(R.string.git_panel_committed_at),
                    relativeTime(detail.committerEpoch),
                )
                HashRow(detail.hash)
                if (detail.body.isNotBlank()) {
                    Spacer(Modifier.size(10.dp))
                    Text(
                        detail.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.git_panel_commit_files, sheet.files.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(4.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(sheet.files, key = { it.path + it.renameFrom.orEmpty() }) { f ->
                        FileStatRow(f) { onOpenFile(f.path) }
                        HorizontalDivider(color = GitHairline)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HashRow(hash: String) {
    val clipboard = LocalClipboardManager.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(
            stringResource(R.string.git_panel_hash_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            hash.take(12),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
        Spacer(Modifier.width(4.dp))
        IconButton(
            onClick = { clipboard.setText(AnnotatedString(hash)) },
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.git_panel_copy_hash),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun FileStatRow(file: CommitFileStat, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .background(commitFileColor(file), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                commitFileCode(file).toString(),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            file.path,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (file.binary) {
            Text(
                "BIN",
                style = MaterialTheme.typography.bodySmall,
                color = GitMetaGrey,
            )
        } else {
            Text(
                "+${file.added}",
                style = MaterialTheme.typography.bodySmall,
                color = GitAddGreen,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "-${file.deleted}",
                style = MaterialTheme.typography.bodySmall,
                color = GitDelRed,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Status letter derived from numstat alone (commit lists have no status). */
private fun commitFileCode(file: CommitFileStat): Char = when {
    file.renameFrom != null -> 'R'
    file.added > 0 && file.deleted == 0 -> 'A'
    file.deleted > 0 && file.added == 0 -> 'D'
    else -> 'M'
}

private fun commitFileColor(file: CommitFileStat): Color = statusColor(commitFileCode(file))
