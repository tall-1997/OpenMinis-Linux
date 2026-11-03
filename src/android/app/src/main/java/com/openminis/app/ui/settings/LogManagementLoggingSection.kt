package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R

/**
 * [T-logcat-panel] The "Logging" card of the logs screen: the on/off recording
 * switch plus the entry into the live logcat panel.
 *
 * Extracted from LogManagementScreen.kt (which had run past its architecture
 * ceiling) as one self-contained card — the two rows answer the same question
 * for a reader, "is anything being written right now, and can I watch it?" —
 * so the split is a real seam rather than a line-count trick.
 */
@Composable
internal fun LoggingSection(
    loggingEnabled: Boolean,
    onToggleLogging: (Boolean) -> Unit,
    onOpenLogcat: () -> Unit,
) {
    SettingsSection(
        header = stringResource(R.string.log_section_logging),
        footer = stringResource(R.string.log_section_logging_footer),
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.log_enable_switch),
            checked = loggingEnabled,
            onCheckedChange = onToggleLogging,
            showDivider = true,
        )
        // Live tail of this process's own logcat stream. Sits under the
        // recording switch because that is the question a reader arrives with.
        SettingsRow(
            title = stringResource(R.string.logcat_panel_entry),
            subtitle = stringResource(R.string.logcat_panel_entry_sub),
            icon = Icons.Default.List,
            onClick = onOpenLogcat,
            showDivider = false,
        )
    }
}
