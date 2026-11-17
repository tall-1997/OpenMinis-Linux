package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-tool-undo-replay] ZCode-style "changes this turn" card: the per-path
 * net file changes of the last completed turn, with per-file undo and a
 * one-tap rollback of the whole turn. Restore semantics (pre-image from the
 * earliest turn-window snapshot, restore|delete two-state) live in
 * ChatViewModelUndoExt.kt — this composable is presentation only.
 */
@Composable
internal fun TurnChangesCard(
    paths: List<String>,
    revertedKeys: Set<String>,
    messageKeyPrefix: String,
    onRevertFile: (String) -> Unit,
    onRevertAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    m3Icon(Icons.AutoMirrored.Filled.Undo),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.turn_changes_title, paths.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            paths.forEach { path ->
                val reverted = revertedKeys.contains(
                    TurnChanges.revertedKey(messageKeyPrefix, path),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (reverted) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (reverted) {
                        Text(
                            text = stringResource(R.string.turn_changes_reverted),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        )
                    } else {
                        MinisTextButton(
                            onClick = { onRevertFile(path) },
                        ) {
                            Text(stringResource(R.string.turn_changes_revert_file))
                        }
                    }
                }
            }
            val allReverted = paths.all { p ->
                revertedKeys.contains(TurnChanges.revertedKey(messageKeyPrefix, p))
            }
            if (!allReverted) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )
                MinisTextButton(
                    onClick = onRevertAll,
                ) {
                    Text(stringResource(R.string.turn_changes_revert_all))
                }
            }
        }
    }
}
