package com.openminis.app.ui.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.theme.IosIndigo

/**
 * [T-turn-card-visibility-toggle] Show the per-turn changes card
 * (TurnChangesCard) in the transcript. Default OFF — the transcript stays
 * clean; the card is an opt-in surface for users who want one-tap file
 * undo visible per turn.
 */
const val KEY_SHOW_TURN_CHANGES_CARD = "chat.showTurnChangesCard"  // Boolean, default false

/** Default OFF — see [KEY_SHOW_TURN_CHANGES_CARD]. */
fun showTurnChangesCardEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_SHOW_TURN_CHANGES_CARD, false)

@Composable
internal fun ShowTurnChangesCardRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsSwitchRow(
        icon = Icons.Outlined.History,
        iconColor = IosIndigo,
        title = stringResource(R.string.appearance_show_turn_changes_card_title),
        subtitle = stringResource(R.string.appearance_show_turn_changes_card_subtitle),
        checked = checked,
        onCheckedChange = onCheckedChange,
        showDivider = false,
    )
}
