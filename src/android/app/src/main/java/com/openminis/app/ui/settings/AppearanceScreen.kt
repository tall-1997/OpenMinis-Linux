package com.openminis.app.ui.settings

import com.openminis.app.config.KEY_LANGUAGE
import com.openminis.app.config.PREF_APPEARANCE
import com.openminis.app.R
import com.openminis.app.ui.theme.*
import com.openminis.app.ui.theme.IosBlue
import com.openminis.app.ui.theme.IosGreen
import com.openminis.app.ui.theme.IosIndigo
import com.openminis.app.ui.theme.IosOrange
import com.openminis.app.data.repository.AppIconRepository
import com.openminis.app.ui.components.MinisTextButton

import android.content.Context
import android.content.SharedPreferences
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt

const val KEY_THEME_MODE = "theme_mode"            // 0=System, 1=Light, 2=Dark
const val KEY_LAUNCH_SESSION = "launch_session"    // 0=Auto, 1=LastSession, 2=NewChat, 3=Home
// iOS-aligned key names (match ContentView.swift @AppStorage keys).
const val KEY_RETURN_KEY_BEHAVIOR = "returnKeyBehavior"  // Int 0=Newline (default), 1=Send
const val KEY_KEEP_SCREEN_AWAKE = "keepScreenAwakeDuringTasks"  // Boolean, default false
const val KEY_TOOL_PREVIEW = "tool_preview"        // Boolean, default true
const val KEY_SHOW_FLOATING_TOOL_BAR = "chat.showFloatingToolBar"  // Boolean, default true
const val KEY_SHOW_COMPLETED_TOOL_CARDS = "chat.showCompletedToolCards"  // Boolean, default false
const val KEY_SHOW_SUBAGENT_BAR = "chat.showSubAgentBar"
// [T-composer-mic-toggle] Single source for the sub-agent bar default.
const val DEFAULT_SHOW_SUB_AGENT_BAR = true
const val KEY_AUTO_FOCUS_AFTER_REPLY = "chat.autoFocusAfterReply"  // Boolean, default true
// T-chat-title-pill: sticky session-title pill above the chat list while scrolling back through history.
const val KEY_SHOW_CHAT_TITLE = "appearance.show_chat_title"  // Boolean, default true
// [T-thinking-auto-expand-toggle] A new streaming thinking block auto-expands while the model reasons (default); read at block-mount time.
const val KEY_AUTO_EXPAND_THINKING = "chat.autoExpandThinking"  // Boolean, default true

const val KEY_FOLD_AI_PROCESS = "chat.foldAiProcess"
const val DEFAULT_FOLD_AI_PROCESS = true
// [T-composer-mic-toggle] Show the composer's mic button. Default OFF; voice mode re-shows it so hiding never strands the user.
const val KEY_SHOW_MIC_BUTTON = "chat.showMicButton"
const val DEFAULT_SHOW_MIC_BUTTON = false
const val KEY_SHOW_MESSAGE_TOKEN_USAGE = "chat.showMessageTokenUsage"  // Boolean, default true
// [T-android-composer-context-glow] Rotating composer-edge glow near the model window. Default ON.
const val KEY_COMPOSER_CONTEXT_GLOW = "chat.composerContextGlow"  // Boolean, default true
const val KEY_DYNAMIC_COLOR = "chat.dynamicColor"  // Boolean, default false
// [T-android-auto-grouping] File a chat into a matching EXISTING group on first title generation (no extra round-trip). Default ON; only files into user-created groups, once per chat.
const val KEY_AUTO_GROUPING = "autoGroupingEnabled"  // Boolean, default true
const val KEY_FONT_CHAT_INPUT = "font_chat_input"  // Int scale level -2..3
const val KEY_FONT_MESSAGE = "font_message"        // Int scale level -2..3
const val KEY_FONT_APP_BASE = "font_app_base"      // Int scale level -2..3
/** True when Enter (without Shift) sends the message (iOS returnKeyBehavior == 1); default 0 = newline. */
fun returnKeySendsMessage(context: Context): Boolean = getAppearancePrefs(context).getInt(KEY_RETURN_KEY_BEHAVIOR, 0) == 1

fun keepScreenAwakeEnabled(context: Context): Boolean = getAppearancePrefs(context).getBoolean(KEY_KEEP_SCREEN_AWAKE, false)

/** [T-android-auto-grouping] Default ON — see [KEY_AUTO_GROUPING]. */
fun autoGroupingEnabled(context: Context): Boolean = getAppearancePrefs(context).getBoolean(KEY_AUTO_GROUPING, true)

/** Default ON — pill shows up on scroll for everyone unless explicitly disabled. */
fun showChatTitleEnabled(context: Context): Boolean = getAppearancePrefs(context).getBoolean(KEY_SHOW_CHAT_TITLE, true)

/** [T-thinking-auto-expand-toggle] Default ON: a new streaming thinking block opens expanded. */
fun autoExpandThinkingEnabled(context: Context): Boolean = getAppearancePrefs(context).getBoolean(KEY_AUTO_EXPAND_THINKING, true)

/** Font scale levels matching iOS: XS(-2) Small(-1) Default(0) Medium(1) Large(2) XL(3) */
// [T-android-app-icon-tile-max-width] Upper bound for one icon tile (matches the 192px rasterization; uncapped tiles blurred at ~380dp on tablets).
private val APP_ICON_TILE_MAX_WIDTH = 192.dp

private val fontScaleLabels = listOf("XS", "Small", "Default", "Medium", "Large", "XL")
private val fontScaleValues = listOf(-2, -1, 0, 1, 2, 3)
private val fontScaleMultipliers = listOf(0.88f, 0.94f, 1.0f, 1.06f, 1.12f, 1.21f)

private data class LanguageOption(val code: String, val flag: String, val label: String)
// "System" is resolved via stringResource; the rest are self-name literals.
private val languageOptions = listOf(
    LanguageOption("", "\uD83C\uDF10", ""),
    LanguageOption("en", "\uD83C\uDDFA\uD83C\uDDF8", "English"),
    LanguageOption("zh", "\uD83C\uDDE8\uD83C\uDDF3", "简体中文"),
    // zh-Hant: BCP-47 on purpose (resolves to values-zh-rTW). [T-android-zh-hant-flag] Flag is HK, matching iOS; TW rendered as an empty box on Chinese-market ROMs.
    LanguageOption("zh-Hant", "\uD83C\uDDED\uD83C\uDDF0", "\u7E41\u9AD4\u4E2D\u6587"),
    LanguageOption("ja", "\uD83C\uDDEF\uD83C\uDDF5", "日本語"),
    LanguageOption("ko", "\uD83C\uDDF0\uD83C\uDDF7", "한국어"),
    LanguageOption("fr", "\uD83C\uDDEB\uD83C\uDDF7", "Français"),
    LanguageOption("de", "\uD83C\uDDE9\uD83C\uDDEA", "Deutsch"),
    // ru: flag \uD83C\uDDF7\uD83C\uDDFA (RU), self-name \u0420\u0443\u0441\u0441\u043A\u0438\u0439 (Russkiy)
    LanguageOption("ru", "\uD83C\uDDF7\uD83C\uDDFA", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439"),
    // es: flag \uD83C\uDDEA\uD83C\uDDF8 (ES), self-name Espanol
    LanguageOption("es", "\uD83C\uDDEA\uD83C\uDDF8", "Espa\u00F1ol"),
    // id: tag is "id" but resources live in res/values-in (Android normalises Indonesian to "in"), so keep the tag modern and the directory legacy.
    LanguageOption("id", "\uD83C\uDDEE\uD83C\uDDE9", "Bahasa Indonesia"),
    // ms: flag MY, self-name Bahasa Melayu (tag and values-ms agree; distinct from Indonesian).
    LanguageOption("ms", "\uD83C\uDDF2\uD83C\uDDFE", "Bahasa Melayu"),
    // fil: tag "fil" (not "tl") resolves to values-fil; standardised national code.
    LanguageOption("fil", "\uD83C\uDDF5\uD83C\uDDED", "Filipino"),
    // th: values-th serves both "th" and "th-TH".
    LanguageOption("th", "\uD83C\uDDF9\uD83C\uDDED", "\u0E44\u0E17\u0E22"),
    // tr: see LocaleAwareCase.uppercaseForDisplay (dotted/dotless i).
    LanguageOption("tr", "\uD83C\uDDF9\uD83C\uDDF7", "T\u00FCrk\u00E7e"),
    // pl: flag \uD83C\uDDF5\uD83C\uDDF1 (PL), self-name Polski.
    LanguageOption("pl", "\uD83C\uDDF5\uD83C\uDDF1", "Polski"),
    // ro: flag \uD83C\uDDF7\uD83C\uDDF4 (RO), self-name Rom\u00E2n\u0103.
    LanguageOption("ro", "\uD83C\uDDF7\uD83C\uDDF4", "Rom\u00E2n\u0103"),
    // pt-BR: region-qualified on purpose (resources live in values-pt-rBR).
    LanguageOption("pt-BR", "\uD83C\uDDE7\uD83C\uDDF7", "Portugu\u00EAs (Brasil)"),
)

fun getAppearancePrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)

fun getThemeMode(context: Context): Int =
    getAppearancePrefs(context).getInt(KEY_THEME_MODE, 0)

fun getFontScale(context: Context, key: String): Float =
    fontScaleForLevel(getAppearancePrefs(context).getInt(key, 0))

fun fontScaleForLevel(level: Int): Float {
    val idx = fontScaleValues.indexOf(level).coerceIn(0, fontScaleMultipliers.lastIndex)
    return fontScaleMultipliers[idx]
}

@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    onThemeChanged: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    // [T-android-sessionlist-prefs-io] Lazy prefs handle; first touch runs on IO so the first frame shows defaults (no main-thread XML parse).
    val prefs = remember { lazy { getAppearancePrefs(context) } }

    var themeMode by remember { mutableIntStateOf(0) }
    var launchSession by remember { mutableIntStateOf(0) }
    var returnKeyBehavior by remember { mutableIntStateOf(0) }
    var keepScreenAwake by remember { mutableStateOf(false) }
    var highRefresh by remember { mutableStateOf(true) }
    var showFloatingToolBar by remember { mutableStateOf(true) }
    var showCompletedToolCards by remember { mutableStateOf(false) }
    var showSubAgentBar by remember { mutableStateOf(DEFAULT_SHOW_SUB_AGENT_BAR) }
    var showMicButton by remember { mutableStateOf(DEFAULT_SHOW_MIC_BUTTON) }
    var showMessageUsage by remember { mutableStateOf(true) }
    var composerContextGlow by remember { mutableStateOf(true) }
    var autoFocusAfterReply by remember { mutableStateOf(true) }
    var autoExpandThinking by remember { mutableStateOf(true) }
    var showFullThinking by remember { mutableStateOf(false) }
    var foldAiProcess by remember { mutableStateOf(DEFAULT_FOLD_AI_PROCESS) }
    // Material You is Android 12+; the row renders disabled below S.
    val monetSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var dynamicColor by remember { mutableStateOf(false) }
    var showChatTitle by remember { mutableStateOf(true) }
    var autoGrouping by remember { mutableStateOf(true) }
    var chatInputLevel by remember { mutableIntStateOf(0) }
    var messageLevel by remember { mutableIntStateOf(0) }
    var appBaseLevel by remember { mutableIntStateOf(0) }
    var selectedLanguage by remember { mutableStateOf("") }
    var selectedAppIcon by remember { mutableStateOf(AppIconRepository.Variant.Auto) }

    LaunchedEffect(Unit) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val p = prefs.value
            themeMode = p.getInt(KEY_THEME_MODE, 0)
            launchSession = p.getInt(KEY_LAUNCH_SESSION, 0)
            returnKeyBehavior = p.getInt(KEY_RETURN_KEY_BEHAVIOR, 0)
            keepScreenAwake = p.getBoolean(KEY_KEEP_SCREEN_AWAKE, false)
            highRefresh = com.openminis.app.ui.HighRefreshRate.enabled(context)
            showFloatingToolBar = p.getBoolean(KEY_SHOW_FLOATING_TOOL_BAR, true)
            showCompletedToolCards = p.getBoolean(KEY_SHOW_COMPLETED_TOOL_CARDS, false)
            showSubAgentBar = p.getBoolean(KEY_SHOW_SUBAGENT_BAR, DEFAULT_SHOW_SUB_AGENT_BAR)
            showMicButton = p.getBoolean(KEY_SHOW_MIC_BUTTON, DEFAULT_SHOW_MIC_BUTTON)
            showMessageUsage = p.getBoolean(KEY_SHOW_MESSAGE_TOKEN_USAGE, true)
            composerContextGlow = p.getBoolean(KEY_COMPOSER_CONTEXT_GLOW, true)
            autoFocusAfterReply = p.getBoolean(KEY_AUTO_FOCUS_AFTER_REPLY, true)
            autoExpandThinking = p.getBoolean(KEY_AUTO_EXPAND_THINKING, true)
            showFullThinking = p.getBoolean(KEY_SHOW_FULL_THINKING, false)
            foldAiProcess = p.getBoolean(KEY_FOLD_AI_PROCESS, DEFAULT_FOLD_AI_PROCESS)
            dynamicColor = p.getBoolean(KEY_DYNAMIC_COLOR, false)
            showChatTitle = p.getBoolean(KEY_SHOW_CHAT_TITLE, true)
            autoGrouping = p.getBoolean(KEY_AUTO_GROUPING, true)
            chatInputLevel = p.getInt(KEY_FONT_CHAT_INPUT, 0)
            messageLevel = p.getInt(KEY_FONT_MESSAGE, 0)
            appBaseLevel = p.getInt(KEY_FONT_APP_BASE, 0)
            selectedLanguage = p.getString(KEY_LANGUAGE, "") ?: ""
            selectedAppIcon = AppIconRepository.current(context)
        }
    }

    val fontsModified = chatInputLevel != 0 || messageLevel != 0 || appBaseLevel != 0

    val tilePurple = IosIndigo
    val tileBlue = IosBlue
    val tileOrange = IosOrange
    val tileGreen = IosGreen
    val tileTeal = Color(0xFF5AC8FA)

    SettingsScaffold(title = stringResource(R.string.appearance_title), onBack = null) {

        // -- Theme --
        SettingsSection(
            header = stringResource(R.string.appearance_section_theme),
            footer = stringResource(R.string.appearance_theme_footer),
        ) {
            data class ThemeRow(val label: String, val icon: ImageVector, val tint: Color)
            val themeRows = listOf(
                ThemeRow(stringResource(R.string.appearance_theme_system), Icons.Outlined.BrightnessAuto, tilePurple),
                ThemeRow(stringResource(R.string.appearance_theme_light), Icons.Outlined.LightMode, tileOrange),
                ThemeRow(stringResource(R.string.appearance_theme_dark), Icons.Outlined.DarkMode, tilePurple),
            )
            themeRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = themeMode == idx,
                    onSelect = {
                        themeMode = idx
                        prefs.value.edit().putInt(KEY_THEME_MODE, idx).apply()
                        onThemeChanged(idx)
                    },
                    leading = {
                        androidx.compose.material3.Icon(
                            row.icon,
                            contentDescription = null,
                            tint = monetIconTint(row.tint),
                        )
                    },
                    showDivider = true,
                )
            }
            // [T-android-monet-dynamic-color] Fourth theme option (after system/light/dark); last row so showDivider is false.
            SettingsSwitchRow(
                icon = Icons.Outlined.Palette,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_monet_title),
                subtitle = stringResource(R.string.appearance_monet_subtitle),
                checked = dynamicColor && monetSupported,
                onCheckedChange = {
                    dynamicColor = it
                    prefs.value.edit().putBoolean(KEY_DYNAMIC_COLOR, it).apply()
                },
                enabled = monetSupported,
                showDivider = false,
            )
        }

        // -- Launch Session --
        // Bolt=Auto, History=Last Session, ChatBubble=New Chat, Home=Home.
        SettingsSection(
            header = stringResource(R.string.appearance_section_launch),
            footer = stringResource(R.string.appearance_launch_footer),
        ) {
            data class LaunchRow(val label: String, val icon: ImageVector, val tint: Color)
            val launchRows = listOf(
                LaunchRow(stringResource(R.string.appearance_launch_auto), Icons.Outlined.Bolt, tileBlue),
                LaunchRow(stringResource(R.string.appearance_launch_last), Icons.Outlined.History, tileTeal),
                LaunchRow(stringResource(R.string.appearance_launch_new), Icons.Outlined.ChatBubbleOutline, tileGreen),
                LaunchRow(stringResource(R.string.appearance_launch_home), Icons.Outlined.Home, tileBlue),
            )
            launchRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = launchSession == idx,
                    onSelect = {
                        launchSession = idx
                        prefs.value.edit().putInt(KEY_LAUNCH_SESSION, idx).apply()
                    },
                    leading = {
                        androidx.compose.material3.Icon(
                            row.icon,
                            contentDescription = null,
                            tint = monetIconTint(row.tint),
                        )
                    },
                    showDivider = idx < launchRows.size - 1,
                )
            }
        }

        // -- Return Key --
        // 0=Newline (default), 1=Send; Shift+Enter always inserts a newline.
        SettingsSection(
            header = stringResource(R.string.appearance_section_return_key),
            footer = stringResource(R.string.appearance_return_key_footer),
        ) {
            data class ReturnRow(val label: String, val value: Int)
            val returnRows = listOf(
                ReturnRow(stringResource(R.string.appearance_return_key_newline), 0),
                ReturnRow(stringResource(R.string.appearance_return_key_send), 1),
            )
            returnRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = returnKeyBehavior == row.value,
                    onSelect = {
                        returnKeyBehavior = row.value
                        prefs.value.edit().putInt(KEY_RETURN_KEY_BEHAVIOR, row.value).apply()
                    },
                    leading = {
                        androidx.compose.material3.Icon(
                            m3Icon(if (idx == 0) Icons.AutoMirrored.Outlined.KeyboardReturn else Icons.AutoMirrored.Outlined.Send),
                            contentDescription = null,
                            tint = monetIconTint(if (idx == 0) tilePurple else tileGreen), // [T-android-dynamic-accent-icons]
                        )
                    },
                    showDivider = idx < returnRows.size - 1,
                )
            }
        }

        // -- Keep Screen Awake --
        // Holds FLAG_KEEP_SCREEN_ON while a session has an active task (mirrors iOS). Default off.
        SettingsSection(
            header = stringResource(R.string.appearance_section_keep_awake),
            footer = stringResource(R.string.appearance_keep_awake_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.ScreenLockPortrait,
                iconColor = tileGreen,
                title = stringResource(R.string.appearance_keep_awake_title),
                checked = keepScreenAwake,
                onCheckedChange = {
                    keepScreenAwake = it
                    prefs.value.edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, it).apply()
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.high_refresh_title),
            footer = stringResource(R.string.high_refresh_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Bolt,
                iconColor = tileGreen,
                title = stringResource(R.string.high_refresh_title),
                subtitle = stringResource(R.string.high_refresh_sub),
                checked = highRefresh,
                onCheckedChange = {
                    highRefresh = it
                    com.openminis.app.ui.HighRefreshRate.setEnabled(context, it)
                    (context as? android.app.Activity)?.let { a -> com.openminis.app.ui.HighRefreshRate.apply(a) }
                },
                showDivider = false,
            )
        }

        // -- Tool Status Bar --
        SettingsSection(
            header = stringResource(R.string.appearance_section_chat_chrome),
            footer = stringResource(R.string.appearance_chat_chrome_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Visibility,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_show_floating_tool_bar),
                checked = showFloatingToolBar,
                onCheckedChange = {
                    showFloatingToolBar = it
                    prefs.value.edit().putBoolean(KEY_SHOW_FLOATING_TOOL_BAR, it).apply()
                },
            )
            // [P0-4] "Tool Preview Window" switch removed (ToolActivityPill has no preview); KEY_TOOL_PREVIEW stays for compat.
            SettingsSwitchRow(
                icon = Icons.Outlined.ViewAgenda,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_show_completed_tool_cards),
                subtitle = stringResource(R.string.appearance_show_completed_tool_cards_subtitle),
                checked = showCompletedToolCards,
                onCheckedChange = {
                    showCompletedToolCards = it
                    prefs.value.edit().putBoolean(KEY_SHOW_COMPLETED_TOOL_CARDS, it).apply()
                },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.Groups,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_show_subagent_bar),
                checked = showSubAgentBar,
                onCheckedChange = {
                    showSubAgentBar = it
                    prefs.value.edit().putBoolean(KEY_SHOW_SUBAGENT_BAR, it).apply()
                },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.Mic,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_show_mic_button_title),
                subtitle = stringResource(R.string.appearance_show_mic_button_subtitle),
                checked = showMicButton,
                onCheckedChange = {
                    showMicButton = it
                    prefs.value.edit().putBoolean(KEY_SHOW_MIC_BUTTON, it).apply()
                },
                showDivider = true,
            )
            // [T-android-message-usage-footer] Per-reply token usage line.
            SettingsSwitchRow(
                icon = Icons.Outlined.Bolt,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_message_usage_title),
                subtitle = stringResource(R.string.appearance_message_usage_subtitle),
                checked = showMessageUsage,
                onCheckedChange = {
                    showMessageUsage = it
                    prefs.value.edit().putBoolean(KEY_SHOW_MESSAGE_TOKEN_USAGE, it).apply()
                },
            )
            // [T-android-composer-context-glow] Composer edge glow near the context cap.
            SettingsSwitchRow(
                icon = Icons.Outlined.LightMode,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_context_glow_title),
                subtitle = stringResource(R.string.appearance_context_glow_subtitle),
                checked = composerContextGlow,
                onCheckedChange = {
                    composerContextGlow = it
                    prefs.value.edit().putBoolean(KEY_COMPOSER_CONTEXT_GLOW, it).apply()
                },
                showDivider = false,
            )
        }

        // [T-thinking-auto-expand-toggle] -- Deep Thinking --
        // New streaming thinking block opens expanded (default ON); manual taps always work.
        SettingsSection(
            header = stringResource(R.string.appearance_section_deep_thinking),
            footer = stringResource(R.string.appearance_auto_expand_thinking_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Psychology,
                iconColor = tilePurple,
                title = stringResource(R.string.appearance_auto_expand_thinking_title),
                checked = autoExpandThinking,
                onCheckedChange = {
                    autoExpandThinking = it
                    prefs.value.edit().putBoolean(KEY_AUTO_EXPAND_THINKING, it).apply()
                },
                showDivider = true,
            )
            ShowFullThinkingRow(showFullThinking) { showFullThinking = it; prefs.value.edit().putBoolean(KEY_SHOW_FULL_THINKING, it).apply() }
            SettingsSwitchRow(
                icon = Icons.Outlined.ViewAgenda,
                iconColor = tilePurple,
                title = stringResource(R.string.appearance_fold_ai_process_title),
                subtitle = stringResource(R.string.appearance_fold_ai_process_subtitle),
                checked = foldAiProcess,
                onCheckedChange = {
                    foldAiProcess = it
                    prefs.value.edit().putBoolean(KEY_FOLD_AI_PROCESS, it).apply()
                },
                showDivider = false,
            )
        }

        // -- Auto-Focus After Reply --
        // Applies only on the streaming→idle edge; session entry leaves the keyboard hidden.
        SettingsSection(
            header = stringResource(R.string.appearance_section_auto_focus_after_reply),
            footer = stringResource(R.string.appearance_auto_focus_after_reply_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Keyboard,
                iconColor = tileBlue,
                title = stringResource(R.string.appearance_auto_focus_after_reply_title),
                checked = autoFocusAfterReply,
                onCheckedChange = {
                    autoFocusAfterReply = it
                    prefs.value.edit().putBoolean(KEY_AUTO_FOCUS_AFTER_REPLY, it).apply()
                },
                showDivider = false,
            )
        }

        // -- Chat Title (T-chat-title-pill) --
        // Sticky session-title pill when scrolling back through history. Default ON.
        SettingsSection(
            header = stringResource(R.string.appearance_section_chat_title),
            footer = stringResource(R.string.appearance_show_chat_title_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.ChatBubbleOutline,
                iconColor = tileBlue,
                title = stringResource(R.string.appearance_show_chat_title),
                subtitle = stringResource(R.string.appearance_show_chat_title_subtitle),
                checked = showChatTitle,
                onCheckedChange = {
                    showChatTitle = it
                    prefs.value.edit().putBoolean(KEY_SHOW_CHAT_TITLE, it).apply()
                },
                showDivider = false,
            )
        }

        // -- Auto-Grouping (T-android-auto-grouping) --
        // Rides the title-generation call (no extra request). Port of iOS "Grouping".
        SettingsSection(
            header = stringResource(R.string.appearance_section_grouping),
            footer = stringResource(R.string.appearance_auto_grouping_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Folder,
                iconColor = tileBlue,
                title = stringResource(R.string.appearance_auto_grouping),
                subtitle = stringResource(R.string.appearance_auto_grouping_subtitle),
                checked = autoGrouping,
                onCheckedChange = {
                    autoGrouping = it
                    prefs.value.edit().putBoolean(KEY_AUTO_GROUPING, it).apply()
                },
                showDivider = false,
            )
        }

        // -- Font Size --
        SettingsSection(
            header = stringResource(R.string.appearance_section_font_size),
            footer = stringResource(R.string.appearance_font_size_footer),
        ) {
            SettingsRow(
                icon = Icons.Outlined.FormatSize,
                iconColor = tileOrange,
                title = stringResource(R.string.appearance_font_scale_title),
                subtitle = stringResource(R.string.appearance_font_scale_subtitle),
                onClick = null,
                showChevron = false,
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_chat_input),
                level = chatInputLevel,
                onLevelChange = {
                    chatInputLevel = it
                    prefs.value.edit().putInt(KEY_FONT_CHAT_INPUT, it).apply()
                },
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_message),
                level = messageLevel,
                onLevelChange = {
                    messageLevel = it
                    prefs.value.edit().putInt(KEY_FONT_MESSAGE, it).apply()
                },
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_app_base),
                level = appBaseLevel,
                onLevelChange = {
                    appBaseLevel = it
                    prefs.value.edit().putInt(KEY_FONT_APP_BASE, it).apply()
                },
                showDivider = fontsModified,
            )
            if (fontsModified) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    MinisTextButton(onClick = {
                        chatInputLevel = 0; messageLevel = 0; appBaseLevel = 0
                        prefs.value.edit()
                            .putInt(KEY_FONT_CHAT_INPUT, 0)
                            .putInt(KEY_FONT_MESSAGE, 0)
                            .putInt(KEY_FONT_APP_BASE, 0)
                            .apply()
                    }) {
                        Text(stringResource(R.string.appearance_font_reset), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // -- App Icon (T-android-dynamic-app-icon) --
        // 3-column adaptive-icon grid; selected tile gets a checkmark badge; tap flips the activity-alias enabled state.
        SettingsSection(
            header = stringResource(R.string.appearance_section_app_icon),
            footer = stringResource(R.string.appearance_app_icon_footer),
        ) {
            data class IconOption(val variant: AppIconRepository.Variant, val titleRes: Int, val mipmapRes: Int)
            val iconOptions = listOf(
                IconOption(AppIconRepository.Variant.Auto, R.string.appearance_app_icon_auto, R.mipmap.ic_launcher),
                IconOption(AppIconRepository.Variant.ClassicLight, R.string.appearance_app_icon_light, R.mipmap.ic_launcher_classic_light),
                IconOption(AppIconRepository.Variant.ClassicDark, R.string.appearance_app_icon_dark, R.mipmap.ic_launcher_classic_dark),
            )
            // [T-android-app-icon-tile-max-width] Cap each tile (192px source upscaled ~4.6x at ~380dp and blurred) and centre the row; phones are unaffected.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                for (option in iconOptions) {
                    val isSelected = selectedAppIcon == option.variant
                    val iconPainter: Painter = remember(option.mipmapRes) {
                        // painterResource() can't decode adaptive-icon XML; rasterize to a Bitmap first.
                        val drawable = ResourcesCompat.getDrawable(context.resources, option.mipmapRes, context.theme)
                        if (drawable != null) {
                            BitmapPainter(drawable.toBitmap(width = 192, height = 192).asImageBitmap())
                        } else {
                            BitmapPainter(
                                android.graphics.Bitmap
                                    .createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                                    .asImageBitmap(),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            // 192dp = the bitmap's 192px; fill=false lets the tile stop at the cap.
                            .widthIn(max = APP_ICON_TILE_MAX_WIDTH)
                            .clickable {
                                if (selectedAppIcon != option.variant) {
                                    selectedAppIcon = option.variant
                                    AppIconRepository.apply(context, option.variant)
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 0.5.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                ),
                        ) {
                            Image(
                                painter = iconPainter,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(18.dp)),
                            )
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(22.dp)
                                        .background(
                                            color = Color.White,
                                            shape = CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        m3Icon(Icons.Filled.CheckCircle),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(option.titleRes),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }

        // -- Language --
        SettingsSection(
            header = stringResource(R.string.appearance_section_language),
            footer = stringResource(R.string.appearance_language_footer),
        ) {
            languageOptions.forEachIndexed { idx, lang ->
                SettingsChoiceRow(
                    title = if (lang.code.isEmpty()) stringResource(R.string.appearance_theme_system) else lang.label,
                    selected = selectedLanguage == lang.code,
                    onSelect = {
                        selectedLanguage = lang.code
                        prefs.value.edit().putString(KEY_LANGUAGE, lang.code).apply()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val lm = context.getSystemService(LocaleManager::class.java)
                            lm?.applicationLocales = if (lang.code.isEmpty()) {
                                LocaleList.getEmptyLocaleList()
                            } else {
                                LocaleList.forLanguageTags(lang.code)
                            }
                        } else {
                            // T-n01-andmenu-l10n: pre-Tiramisu has no LocaleManager; recreate so attachBaseContext picks up the new language.
                            (context as? android.app.Activity)?.recreate()
                        }
                    },
                    leading = {
                        Text(lang.flag, fontSize = 22.sp, modifier = Modifier.width(30.dp))
                    },
                    showDivider = idx < languageOptions.size - 1,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FontScaleSliderRow(
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
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
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 14.dp)
                .height(0.5.dp)
                .background(dividerColor),
        )
    }
}
