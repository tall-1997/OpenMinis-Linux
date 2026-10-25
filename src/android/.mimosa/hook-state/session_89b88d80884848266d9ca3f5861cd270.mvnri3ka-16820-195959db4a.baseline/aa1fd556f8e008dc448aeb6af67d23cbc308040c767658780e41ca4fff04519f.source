package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.openminis.app.R

/** [T-android-soul-custom-icon] 头像来源菜单。单独成文件：SoulSettingsScreen 已到行数 ceiling。 */
@Composable
internal fun SoulIconMenu(
    expanded: Boolean,
    hasIcon: Boolean,
    onDismiss: () -> Unit,
    onChooseEmoji: () -> Unit,
    onChooseImage: () -> Unit,
    onUseDefault: () -> Unit,
    onUseModelIcon: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.soul_icon_choose_emoji)) },
            onClick = onChooseEmoji,
        )
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.soul_icon_choose_image))
                    // Stated up front so the square crop isn't a surprise after
                    // picking. (There is no alpha requirement: opaque images are
                    // accepted and the rounded clip is what makes them look right.)
                    Text(
                        text = stringResource(R.string.soul_icon_image_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = onChooseImage,
        )
        // [T-soul-avatar-model-icon] 跟随当前会话模型的厂商图标。
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.soul_icon_use_model))
                    Text(
                        text = stringResource(R.string.soul_icon_use_model_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = onUseModelIcon,
        )
        // Only offered when there is something to clear.
        if (hasIcon) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.soul_icon_use_default)) },
                onClick = onUseDefault,
            )
        }
    }
}
