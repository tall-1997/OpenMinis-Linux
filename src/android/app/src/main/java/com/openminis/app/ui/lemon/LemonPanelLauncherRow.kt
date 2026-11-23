package com.openminis.app.ui.lemon

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.theme.m3Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Widgets

/**
 * [T-lemon-integration] The single settings row that launches the bundled tool
 * panel.
 *
 * Lives here rather than inline in `MCPIntegrationsScreen`: that screen is on
 * the architecture baseline, and three lines of new UI are enough to break it.
 * Keeping the row next to the panel it opens also means the launch contract
 * (start the server if it is off, then navigate with the live port) has one
 * home instead of being re-derived by every caller.
 */
@Composable
internal fun LemonPanelLauncherRow(
    port: Int,
    enabled: Boolean,
    onEnableServer: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    SettingsRow(
        title = stringResource(R.string.mcp_lemon_panel),
        subtitle = stringResource(R.string.mcp_lemon_panel_sub, port),
        icon = m3Icon(Icons.Outlined.Widgets),
        showDivider = false,
        onClick = {
            // Starting on demand is the least surprising behaviour here:
            // this row is a launcher, not a second on/off switch.
            if (!enabled) onEnableServer()
            onOpen(port)
        },
    )
}
