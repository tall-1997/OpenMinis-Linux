package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.i18n.TranslationPrefs
import com.openminis.app.ui.components.DialogTextField

@Composable
fun TranslationSettingsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val config by providerRepository.config.collectAsState()
    var enabled by remember { mutableStateOf(TranslationPrefs.isEnabled(context)) }
    var lang by remember { mutableStateOf(TranslationPrefs.lang(context)) }
    var entryId by remember { mutableStateOf(TranslationPrefs.entryId(context)) }
    val entries = config.modelEntries.filter { entry ->
        if (entry.isHidden) return@filter false
        val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return@filter false
        inst.isEnabled
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_translate),
        onBack = onBack,
    ) {
        SettingsSection(header = stringResource(R.string.translate_settings_section)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        enabled = !enabled
                        TranslationPrefs.setEnabled(context, enabled)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.translate_settings_enabled), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.translate_settings_enabled_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        TranslationPrefs.setEnabled(context, it)
                    },
                )
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.translate_target),
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            DialogTextField(
                value = lang,
                onValueChange = {
                    lang = it
                    TranslationPrefs.setLang(context, it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        SettingsSection(header = stringResource(R.string.translate_pick_model)) {
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.translate_no_model),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    entries.forEach { entry ->
                        val provider = config.instances.find { it.id == entry.providerInstanceId }
                        val selected = entry.id == entryId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    entryId = entry.id
                                    TranslationPrefs.setEntryId(context, entry.id)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.model.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    provider?.label ?: entry.providerInstanceId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.cd_selected))
                            }
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.translate_settings_hint),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
