package com.openminis.app.ui.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.theme.IosIndigo
import com.openminis.app.ui.theme.m3Icon

/**
 * [T-turn-card-visibility-toggle] Show the per-turn changes card
 * (TurnChangesCard) in the transcript. Default OFF — the transcript stays
 * clean; the card is an opt-in surface for users who want one-tap file
 * undo visible per turn.
 */
const val KEY_SHOW_TURN_CHANGES_CARD = "chat.showTurnChangesCard"  // Boolean, default false

/** Default OFF — see [KEY_SHOW_TURN_CHANGES_CARD]. */
fun showTurnChangesCardEnabled(context: Context): Boolean =
    getAppearancePrefs(context).bool(KEY_SHOW_TURN_CHANGES_CARD, false)

/** [T-turn-card-visibility-toggle] Reactive read: observes the appearance
 *  prefs key so a settings toggle takes effect on the next recomposition,
 *  not only after the list is rebuilt. */
@Composable
internal fun rememberShowTurnChangesCardEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(showTurnChangesCardEnabled(context)) }
    DisposableEffect(context) {
        val prefs = getAppearancePrefs(context)
        val stop = prefs.observe { key ->
            if (key == KEY_SHOW_TURN_CHANGES_CARD) {
                enabled = prefs.bool(KEY_SHOW_TURN_CHANGES_CARD, false)
            }
        }
        onDispose { stop() }
    }
    return enabled
}

@Composable
internal fun ShowTurnChangesCardRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsSwitchRow(
        icon = m3Icon(Icons.Outlined.History),
        iconColor = IosIndigo,
        title = stringResource(R.string.appearance_show_turn_changes_card_title),
        subtitle = stringResource(R.string.appearance_show_turn_changes_card_subtitle),
        checked = checked,
        onCheckedChange = onCheckedChange,
        showDivider = false,
    )
}

/**
 * [T-pref-reactive-read] Reactive read of an appearance boolean.
 *
 * `remember { xxxEnabled(context) }` has no key: it reads once at mount, so a
 * settings toggle only applies after the list is rebuilt. Two of these shipped
 * that way (Turn-changes card, auto-expand thinking) and both looked like the
 * switch was broken. This helper observes the key instead, so the change lands
 * on the next recomposition.
 */
@Composable
internal fun rememberAppearanceBool(key: String, default: Boolean): Boolean {
    val context = LocalContext.current
    // [T-pref-reactive-read] `remember(key)` not plain `remember`: when the
    // CALLER swaps which key it watches, the initial value must be re-read for
    // the new key. With a keyless remember the state kept the old key's value
    // for one frame and only the DisposableEffect corrected it.
    var value by remember(context, key, default) {
        mutableStateOf(getAppearancePrefs(context).bool(key, default))
    }
    DisposableEffect(context, key, default) {
        val prefs = getAppearancePrefs(context)
        value = prefs.bool(key, default)
        val stop = prefs.observe { changed ->
            if (changed == key) value = prefs.bool(key, default)
        }
        onDispose { stop() }
    }
    return value
}
