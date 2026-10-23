package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.theme.m3Icon

/**
 * Providers list footer: put the built-in OpenCode Zen free lane back on a
 * device that no longer has one.
 *
 * Shown whenever the instance is missing, whether an upgrade removed it or the
 * user deleted it themselves — the row is the answer to "where did my free
 * models go", and refusing to show it in one of those cases is how a device ends
 * up with no way back. It disappears the moment the instance exists again, which
 * is the only feedback the action needs.
 *
 * Apple-shaped by inheritance: this is a [SettingsRow] inside a [SettingsSection],
 * so the 56dp single-line rhythm, the 0.5dp hairline, the 14/12dp padding and the
 * 24dp inter-section gap all come from the shared primitives instead of being
 * restated (and drift-prone) here. The icon is a registered Filled/Outlined pair,
 * so it follows the dynamic-colour icon mode like every other settings row.
 */
@Composable
internal fun ZenFreeLaneRestoreRow(visible: Boolean, onRestore: () -> Unit) {
    if (!visible) return
    SettingsSection(header = stringResource(R.string.zen_restore_section)) {
        SettingsRow(
            title = stringResource(R.string.zen_restore_title),
            subtitle = stringResource(R.string.zen_restore_subtitle),
            icon = m3Icon(Icons.Filled.CloudDownload),
            onClick = onRestore,
            showDivider = false,
        )
    }
}
