package com.openminis.app.ui.git

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * Pinned bottom bar: draft field + AI generator, then stage-all / AI text /
 * commit(N). Every action is disabled while a mutation is in flight; the
 * commit button additionally needs a staged entry and a non-blank message.
 */
@Composable
internal fun GitPanelCommitBar(state: GitPanelState, viewModel: GitPanelViewModel) {
    val stagedCount = state.status?.entries?.count { it.staged } ?: 0
    val hasUnstaged = state.status?.entries?.any { !it.staged } == true
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        OutlinedTextField(
            value = state.draftMessage,
            onValueChange = viewModel::setDraftMessage,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.git_panel_commit_hint)) },
            minLines = 1,
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = viewModel::stageAll,
                enabled = !state.busy && hasUnstaged,
            ) {
                Text(stringResource(R.string.git_panel_stage_all))
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = viewModel::generateCommitMessage,
                enabled = !state.generating && !state.busy && stagedCount > 0,
            ) {
                if (state.generating) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.git_panel_generate))
            }
            Button(
                onClick = viewModel::commit,
                enabled = !state.busy && stagedCount > 0 && state.draftMessage.isNotBlank(),
            ) {
                Text(stringResource(R.string.git_panel_commit) + " ($stagedCount)")
            }
        }
    }
}
