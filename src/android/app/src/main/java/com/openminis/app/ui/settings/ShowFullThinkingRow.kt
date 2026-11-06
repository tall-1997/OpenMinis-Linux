package com.openminis.app.ui.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.theme.IosIndigo

/**
 * [T-wave-e-show-full-thinking] "Show full thinking" appearance toggle.
 * Default OFF — collapsed summaries keep the transcript scannable; ON opens
 * every thinking block expanded and renders the ENTIRE content (no 8K tail
 * window) for deep-dive reading.
 */
const val KEY_SHOW_FULL_THINKING = "chat.showFullThinking"  // Boolean, default false

fun showFullThinkingEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_SHOW_FULL_THINKING, false)

@Composable
internal fun ShowFullThinkingRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsSwitchRow(
        icon = Icons.Outlined.ViewAgenda,
        iconColor = IosIndigo,
        title = stringResource(R.string.appearance_show_full_thinking_title),
        subtitle = stringResource(R.string.appearance_show_full_thinking_footer),
        checked = checked,
        onCheckedChange = onCheckedChange,
        showDivider = true,
    )
}
