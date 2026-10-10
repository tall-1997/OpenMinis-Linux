package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import kotlin.math.roundToInt

/**
 * [T-android-chat-customization] Chat Area section: conversation-column gutter
 * (the shared rail the message list, floating tool bar and composer all sit on)
 * plus user-bubble / chat-background color overrides.
 *
 * Self-contained: state, prefs writes and the swatch dialog all live here, so
 * AppearanceScreen only needs a single call. Color overrides are stored as
 * Long ARGB with NO_COLOR_OVERRIDE = follow theme (the resolved ChatPalette
 * owns the slot again).
 */
@Composable
internal fun ChatAreaSection(prefs: android.content.SharedPreferences) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var chatGutter by remember { mutableIntStateOf(chatGutterDp(context)) }
    var userBubbleColor by remember { mutableLongStateOf(prefs.getLong(KEY_CHAT_USER_BUBBLE_COLOR, NO_COLOR_OVERRIDE)) }
    var chatBgColor by remember { mutableLongStateOf(prefs.getLong(KEY_CHAT_BG_COLOR, NO_COLOR_OVERRIDE)) }
    var colorPickerTarget by remember { mutableStateOf<String?>(null) }

    val tileTeal = Color(0xFF5AC8FA)
    val tileBlue = Color(0xFF007AFF)
    val tilePurple = Color(0xFF5856D6)

    SettingsSection(
        header = stringResource(R.string.appearance_section_chat_area),
        footer = stringResource(R.string.appearance_chat_area_footer),
    ) {
        SettingsRow(
            icon = Icons.Outlined.CompareArrows,
            iconColor = tileTeal,
            title = stringResource(R.string.appearance_chat_gutter_title),
            subtitle = stringResource(R.string.appearance_chat_gutter_subtitle),
            onClick = null,
            showChevron = false,
            showDivider = true,
        )
        GutterSliderRow(
            level = chatGutter,
            onLevelChange = {
                chatGutter = it
                prefs.edit().putInt(KEY_CHAT_GUTTER_DP, it).apply()
            },
            showDivider = true,
        )
        SettingsRow(
            icon = Icons.Outlined.ChatBubble,
            iconColor = tileBlue,
            title = stringResource(R.string.appearance_chat_user_bubble_color),
            subtitle = null,
            onClick = { colorPickerTarget = KEY_CHAT_USER_BUBBLE_COLOR },
            showDivider = true,
            trailing = {
                ColorSwatch(
                    color = if (userBubbleColor == NO_COLOR_OVERRIDE) null
                    else Color(userBubbleColor.toLong() and 0xFFFFFFFFL),
                )
            },
        )
        SettingsRow(
            icon = Icons.Outlined.Wallpaper,
            iconColor = tilePurple,
            title = stringResource(R.string.appearance_chat_bg_color),
            subtitle = null,
            onClick = { colorPickerTarget = KEY_CHAT_BG_COLOR },
            showDivider = false,
            trailing = {
                ColorSwatch(
                    color = if (chatBgColor == NO_COLOR_OVERRIDE) null
                    else Color(chatBgColor.toLong() and 0xFFFFFFFFL),
                )
            },
        )
    }

    colorPickerTarget?.let { target ->
        val current = if (target == KEY_CHAT_USER_BUBBLE_COLOR) userBubbleColor else chatBgColor
        ChatColorPickerDialog(
            title = stringResource(
                if (target == KEY_CHAT_USER_BUBBLE_COLOR) R.string.appearance_chat_user_bubble_color
                else R.string.appearance_chat_bg_color
            ),
            current = current,
            isBubble = target == KEY_CHAT_USER_BUBBLE_COLOR,
            onPick = { value ->
                if (target == KEY_CHAT_USER_BUBBLE_COLOR) userBubbleColor = value else chatBgColor = value
                prefs.edit().putLong(target, value).apply()
            },
            onFollowTheme = {
                if (target == KEY_CHAT_USER_BUBBLE_COLOR) userBubbleColor = NO_COLOR_OVERRIDE
                else chatBgColor = NO_COLOR_OVERRIDE
                prefs.edit().putLong(target, NO_COLOR_OVERRIDE).apply()
            },
            onDismiss = { colorPickerTarget = null },
        )
    }
}

/** [T-android-chat-customization] Gutter slider — mirrors FontScaleSliderRow's
 *  look (label + live value on top, slider below) but over a plain Int dp
 *  range 8..32 with the default at 20. */
@Composable
private fun GutterSliderRow(
    level: Int,
    onLevelChange: (Int) -> Unit,
    showDivider: Boolean,
) {
    var sliderPos by remember(level) { mutableFloatStateOf(level.toFloat()) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.appearance_chat_gutter_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${sliderPos.roundToInt()} dp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = sliderPos,
            onValueChange = { sliderPos = it },
            onValueChangeFinished = {
                val newDp = sliderPos.roundToInt().coerceIn(8, 32)
                sliderPos = newDp.toFloat()
                onLevelChange(newDp)
            },
            valueRange = 8f..32f,
            steps = 23,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (showDivider) {
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 14.dp)
                .height(0.5.dp)
                .background(dividerColor),
        )
    }
}

/** Small round swatch shown at the trailing edge of a color row.
 *  null = follow theme, rendered as the theme's own userBubble so the row
 *  previews what the chat currently uses. */
@Composable
private fun ColorSwatch(color: Color?) {
    val resolved = color ?: com.openminis.app.ui.theme.ChatColors.userBubble
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(resolved, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/** Swatch presets. Bubble set leans on the iOS system-color family the chat
 *  palette is built from; background set adds near-white / near-black neutrals
 *  plus pastel tints that keep dark-mode text legible. */
private val BubbleColorPresets = listOf(
    0xFF007AFF, 0xFF34C759, 0xFFFF9500, 0xFFFF3B30,
    0xFF5856D6, 0xFFFF2D55, 0xFF5AC8FA, 0xFFFFD60E,
    0xFFF2F2F7, 0xFFE5E5EA, 0xFF787880, 0xFF2F3A5C,
    0xFF2C2C2E, 0xFF1C1C1E, 0xFFA2845E, 0xFF6D6D72,
)
private val BgColorPresets = listOf(
    0xFFFFFFFF, 0xFFF2F2F7, 0xFFE5E5EA, 0xFFD1D1D6,
    0xFFEAF4FF, 0xFFEAFBF0, 0xFFFFF6E5, 0xFFF5EEFF,
    0xFF000000, 0xFF1C1C1E, 0xFF2C2C2E, 0xFF26262A,
    0xFF16213E, 0xFF1E2A25, 0xFF2B1E1E, 0xFF241F2E,
)

/** Swatch picker dialog: 4x4 preset grid + "follow theme" reset.
 *  current is the stored Long (NO_COLOR_OVERRIDE = follow theme). */
@Composable
private fun ChatColorPickerDialog(
    title: String,
    current: Long,
    isBubble: Boolean,
    onPick: (Long) -> Unit,
    onFollowTheme: () -> Unit,
    onDismiss: () -> Unit,
) {
    val presets = if (isBubble) BubbleColorPresets else BgColorPresets
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                presets.chunked(4).forEach { rowColors ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        rowColors.forEach { c ->
                            val selected = current != NO_COLOR_OVERRIDE &&
                                (current and 0xFFFFFFFFL) == (c.toLong() and 0xFFFFFFFFL)
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color(c), CircleShape)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        onPick(c.toLong() and 0xFFFFFFFFL)
                                        onDismiss()
                                    },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onFollowTheme()
                            onDismiss()
                        }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.appearance_color_follow_theme),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

/** [T-android-font-scale] Font-scale slider row (moved verbatim from
 *  AppearanceScreen.kt during the T-android-chat-customization ratchet
 *  extraction — behavior unchanged). Label + live scale label on top,
 *  A-slider-A below, optional trailing divider. */
@Composable
internal fun FontScaleSliderRow(
    label: String,
    level: Int,
    onLevelChange: (Int) -> Unit,
    showDivider: Boolean,
) {
    val idx = fontScaleValues.indexOf(level).coerceIn(0, fontScaleValues.lastIndex)
    var sliderPos by remember(level) { mutableFloatStateOf(idx.toFloat()) }
    val currentLabel = fontScaleLabels.getOrElse(sliderPos.roundToInt()) { "Default" }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("A", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = sliderPos,
                onValueChange = { sliderPos = it },
                onValueChangeFinished = {
                    val newIdx = sliderPos.roundToInt().coerceIn(0, fontScaleValues.lastIndex)
                    sliderPos = newIdx.toFloat()
                    onLevelChange(fontScaleValues[newIdx])
                },
                valueRange = 0f..5f,
                steps = 4,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text("A", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showDivider) {
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 14.dp)
                .height(0.5.dp)
                .background(dividerColor),
        )
    }
}
