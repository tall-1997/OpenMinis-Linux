package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.m3Icon
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-tool-undo-replay] ZCode-style per-turn change card. [T-turn-card-collapsed]
 * Default COLLAPSED into a single summary row (tap to expand) — an always-open
 * wall of file rows hurts transcript scannability, and the 8-second auto-collapse
 * this replaced was a re-timed surprise on every process recreation. Destructive
 * actions stay behind an explicit confirmation.
 */
@Composable
internal fun TurnChangesCard(
    paths: List<String>,
    stats: Map<String, FileDiffStat>,
    statsComplete: Boolean,
    revertedKeys: Set<String>,
    messageKeyPrefix: String,
    onRevertFile: (String) -> Unit,
    onRevertAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable("$messageKeyPrefix:expanded") { mutableStateOf(false) }
    var pendingFile by rememberSaveable("$messageKeyPrefix:file") { mutableStateOf<String?>(null) }
    var pendingAll by rememberSaveable("$messageKeyPrefix:all") { mutableStateOf(false) }

    val allStatsAvailable = statsComplete && paths.all { path ->
        stats.containsKey(TurnChanges.revertedKey(messageKeyPrefix, path))
    }
    val totalAdded = if (allStatsAvailable) paths.sumOf { path ->
        stats.getValue(TurnChanges.revertedKey(messageKeyPrefix, path)).added
    } else {
        0
    }
    val totalRemoved = if (allStatsAvailable) paths.sumOf { path ->
        stats.getValue(TurnChanges.revertedKey(messageKeyPrefix, path)).removed
    } else {
        0
    }
    val allReverted = paths.all { path ->
        revertedKeys.contains(TurnChanges.revertedKey(messageKeyPrefix, path))
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable { expanded = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        m3Icon(Icons.AutoMirrored.Filled.Undo),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (allStatsAvailable) {
                            stringResource(
                                R.string.turn_changes_summary,
                                paths.size,
                                totalAdded,
                                totalRemoved,
                            )
                        } else {
                            stringResource(R.string.turn_changes_title, paths.size)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = m3Icon(Icons.Default.ExpandMore),
                        contentDescription = stringResource(R.string.turn_changes_expand),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clickable { expanded = false },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                            modifier = Modifier.weight(1f),
                        )
                        if (allStatsAvailable) {
                            Text(
                                text = stringResource(
                                    R.string.turn_changes_stats,
                                    totalAdded,
                                    totalRemoved,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Icon(
                            imageVector = m3Icon(Icons.Default.ExpandLess),
                            contentDescription = stringResource(R.string.turn_changes_collapse),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    paths.forEach { path ->
                        val reverted = revertedKeys.contains(
                            TurnChanges.revertedKey(messageKeyPrefix, path),
                        )
                        val stat = stats[TurnChanges.revertedKey(messageKeyPrefix, path)]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (reverted) {
                                    ChatColors.tertiaryText
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (stat != null) {
                                Text(
                                    text = stringResource(
                                        R.string.turn_changes_stats,
                                        stat.added,
                                        stat.removed,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (reverted) {
                                        ChatColors.tertiaryText
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                            if (reverted) {
                                Text(
                                    text = stringResource(R.string.turn_changes_reverted),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ChatColors.tertiaryText,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            } else {
                                MinisTextButton(
                                    onClick = { pendingFile = path },
                                    modifier = Modifier.padding(start = 2.dp),
                                ) {
                                    Text(stringResource(R.string.turn_changes_revert_file))
                                }
                            }
                        }
                    }
                    if (!allReverted) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        )
                        MinisTextButton(
                            onClick = { pendingAll = true },
                        ) {
                            Text(stringResource(R.string.turn_changes_revert_all))
                        }
                    }
                }
            }
        }
    }

    pendingFile?.let { path ->
        MinisAlertDialog(
            onDismissRequest = { pendingFile = null },
            title = stringResource(R.string.turn_changes_confirm_file_title),
            text = stringResource(R.string.turn_changes_confirm_file_message, path),
            confirmText = stringResource(R.string.turn_changes_revert_file),
            onConfirm = {
                pendingFile = null
                onRevertFile(path)
            },
            isDestructive = true,
        )
    }

    if (pendingAll) {
        MinisAlertDialog(
            onDismissRequest = { pendingAll = false },
            title = stringResource(R.string.turn_changes_confirm_all_title),
            text = stringResource(R.string.turn_changes_confirm_all_message, paths.size),
            confirmText = stringResource(R.string.turn_changes_revert_all),
            onConfirm = {
                pendingAll = false
                onRevertAll()
            },
            isDestructive = true,
        )
    }
}
