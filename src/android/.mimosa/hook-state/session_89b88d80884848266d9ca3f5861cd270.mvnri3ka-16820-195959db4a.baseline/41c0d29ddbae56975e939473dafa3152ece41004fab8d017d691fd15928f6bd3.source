package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.theme.m3Icon

/**
 * Provider detail footer: bring back the model rows deleted on THIS instance.
 *
 * Deletion is a per-instance tombstone (ProviderConfig.deletedModelIds), so the
 * restore is scoped the same way — a global "restore everything" would undo
 * deliberate deletions on other providers the user never asked about. The count
 * in the title is the same number the tombstone holds, so the row states exactly
 * what it will do and vanishes when there is nothing left to undo.
 *
 * Same primitive inheritance as [FreeLaneRestoreRow]: [SettingsRow] supplies
 * the 56dp rhythm, hairline and padding; [m3Icon] keeps the icon in step with
 * the dynamic-colour icon mode.
 */
@Composable
internal fun FreeLaneDeletedModelsRestoreRow(count: Int, onRestore: () -> Unit) {
    if (count <= 0) return
    SettingsRow(
        title = stringResource(R.string.zen_deleted_models_title, count),
        subtitle = stringResource(R.string.zen_deleted_models_subtitle),
        icon = m3Icon(Icons.Filled.History),
        onClick = onRestore,
        showDivider = false,
    )
}
