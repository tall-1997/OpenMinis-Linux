package com.openminis.app.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.BranchNode
import com.openminis.app.data.repository.branchTree
import com.openminis.app.ui.theme.m3Icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BranchTreeSheet(
    sessionId: String,
    viewModel: SessionListViewModel,
    onOpenSession: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tree by remember(sessionId) { mutableStateOf<Pair<BranchNode, List<BranchNode>>?>(null) }
    var branching by remember { mutableStateOf(false) }
    LaunchedEffect(sessionId) { tree = viewModel.chatRepository.branchTree(sessionId) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.sessionlist_branch_tree),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            val t = tree
            if (t == null) {
                Text(
                    stringResource(R.string.sessionlist_branch_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val (source, children) = t
                if (source.id != sessionId) {
                    Text(
                        stringResource(R.string.sessionlist_branch_source),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SheetSessionRow(source) { onOpenSession(source.id) }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    stringResource(R.string.sessionlist_branch_children),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (children.isEmpty()) {
                    Text(
                        "-",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    children.forEach { child ->
                        SheetSessionRow(child) { onOpenSession(child.id) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = {
                    branching = true
                    viewModel.branchAtLatest(sessionId)
                    branching = false
                    onDismiss()
                },
                enabled = !branching,
            ) {
                Text(stringResource(R.string.sessionlist_branch_from_here))
            }
        }
    }
}

@Composable
private fun SheetSessionRow(entity: BranchNode, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(m3Icon(Icons.Default.AccountTree), contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entity.title ?: entity.id.take(12),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entity.branchPoint?.take(8) ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}
