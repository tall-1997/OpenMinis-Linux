package com.openminis.app.ui.settings.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.theme.IosRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-backup-undo-ui] Undo points, in the two places they matter.
 *
 * `RestoreUndoSection` sits under the restore report — the moment a user
 * realises a package was the wrong one is exactly when the report appears, so
 * the escape hatch has to be there rather than buried in settings. It is a
 * destructive-looking red button behind a confirmation dialog, because it
 * rewrites files and relaunches the app.
 *
 * `RestoreUndoHistorySection` lists what is still on disk (newest first) so a
 * user can reclaim the space or roll back a restore from an earlier session.
 */
@Composable
internal fun RestoreUndoSection(vm: BackupViewModel) {
    val point by vm.undo.point.collectAsStateWithLifecycle()
    val busy by vm.undo.busy.collectAsStateWithLifecycle()
    val error by vm.undo.error.collectAsStateWithLifecycle()
    val p = point ?: return
    var confirming by remember { mutableStateOf(false) }

    val footer = when {
        error != null -> stringResource(R.string.backup_undo_failed, error!!)
        p.partial -> stringResource(R.string.backup_undo_partial, p.skipped.joinToString(", "))
        else -> stringResource(R.string.backup_undo_body)
    }

    SettingsSection(header = stringResource(R.string.backup_undo_title), footer = footer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MinisButton(
                onClick = { confirming = true },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = IosRed.copy(alpha = 0.12f),
                    contentColor = IosRed,
                ),
            ) {
                Text(stringResource(R.string.backup_undo_action))
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.backup_undo_confirm_title)) },
            text = { Text(stringResource(R.string.backup_undo_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    vm.undo.restore(p)
                }) {
                    Text(
                        stringResource(R.string.backup_undo_confirm_action),
                        color = IosRed,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
internal fun RestoreUndoHistorySection(vm: BackupViewModel) {
    val points by vm.undo.points.collectAsStateWithLifecycle()
    if (points.isEmpty()) return
    // Read once here: stringResource is @Composable and cannot be called from
    // inside the per-row builders below.
    val partialShort = stringResource(R.string.backup_undo_partial_short)

    SettingsSection(
        header = stringResource(R.string.backup_undo_kept_title),
        footer = stringResource(R.string.backup_undo_kept_body, points.size),
    ) {
        Column {
            points.forEachIndexed { index, pt ->
                SettingsRow(
                    title = formatUndoTime(pt.createdAtMs),
                    subtitle = buildString {
                        append(humanBytes(pt.bytes))
                        if (pt.partial) {
                            append(" · ")
                            append(partialShort)
                        }
                    },
                    showDivider = index != points.lastIndex,
                    trailing = {
                        TextButton(onClick = { vm.undo.discard(pt) }) {
                            Text(
                                stringResource(R.string.backup_undo_discard),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
    }
}

/** `2026-11-03 14:05` — short enough for a subtitle, no locale-dependent order. */
private fun formatUndoTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
