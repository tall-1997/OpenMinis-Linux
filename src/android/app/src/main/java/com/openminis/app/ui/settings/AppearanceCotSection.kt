package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.R
import com.openminis.app.ui.theme.IosGreen

/**
 * [T-cot-translate] Reasoning-translation preferences, extracted from
 * AppearanceScreen when its architecture ceiling left no room. Master toggle +
 * provider + target language; the privacy notice is always visible.
 */
@Composable
internal fun CotTranslationSection() {
    val context = LocalContext.current
    val prefs = remember { com.openminis.app.cottranslate.CotTranslationPrefs }
    var enabled by remember { mutableStateOf(prefs.enabled(context)) }
    var provider by remember { mutableStateOf(prefs.provider(context)) }
    var language by remember { mutableStateOf(prefs.targetLanguage(context)) }
    SettingsSection(
        header = stringResource(R.string.cot_translate_settings_header),
        footer = stringResource(R.string.cot_translate_privacy_notice) + " " +
            stringResource(R.string.cot_translate_anonymous_notice),
    ) {
        SettingsSwitchRow(
            icon = Icons.Outlined.Translate,
            iconColor = IosGreen,
            title = stringResource(R.string.cot_translate_settings_enabled),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                prefs.setEnabled(context, it)
            },
        )
        SettingsValueRow(
            title = stringResource(R.string.cot_translate_settings_provider),
            value = provider.name.lowercase().replaceFirstChar { it.uppercase() },
            onClick = {
                // BING → GOOGLE → LLM → BING. The LLM route uses the chat's
                // currently configured provider instance (no extra setup).
                val cycle = com.openminis.app.cottranslate.TranslationProvider.entries
                val next = cycle[(cycle.indexOf(provider) + 1).mod(cycle.size)]
                provider = next
                prefs.setProvider(context, next)
            },
        )
        SettingsValueRow(
            title = stringResource(R.string.cot_translate_settings_language),
            value = language,
            onClick = {
                // Cycle common targets; "auto" follows the app locale.
                val cycle = listOf("auto", "zh", "en", "ja")
                val next = cycle[(cycle.indexOf(language) + 1).mod(cycle.size)]
                language = next
                prefs.setTargetLanguage(context, next)
            },
            showDivider = false,
        )
    }
}
