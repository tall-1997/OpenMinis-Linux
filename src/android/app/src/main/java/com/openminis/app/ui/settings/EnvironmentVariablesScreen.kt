package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisTextButton

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.deeplink.DeepLinkCoordinator
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Environment Variables \u2014 adopts the SettingsScaffold/SettingsSection
 * toolkit (T80). One section stringResource(R.string.env_var_section_header) carrying every defined key/
 * value pair as a SettingsRow, with the visibility/copy/delete actions
 * in each row's trailing slot. Add affordance lives in the top-bar
 * `actions` slot (T75-part1 already moved it off the FAB).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentVariablesScreen(
    envVarRepository: EnvVarRepository,
    skillRepository: SkillRepository? = null,
    onBack: () -> Unit,
) {
    val entries by envVarRepository.entries.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    var editEntryId by remember { mutableStateOf<String?>(null) }
    var deleteEntryId by remember { mutableStateOf<String?>(null) }
    // iOS parity (AIChatView.swift L1410-1411): when a deep link arrives
    // with `create_key`, the env screen should open with a prefilled Add
    // sheet so the user only has to paste the value.
    var prefill by remember { mutableStateOf<DeepLinkCoordinator.EnvVarCreate?>(null) }
    val visibleKeys = remember { mutableStateOf(setOf<String>()) }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        DeepLinkCoordinator.consumePendingEnvVarCreate()?.let {
            prefill = it
            showAddSheet = true
        }
    }

    val privacyEnabled by com.openminis.app.data.EnvVarPrivacyStore.enabled.collectAsState()

    val fallbackSkills = remember { MutableStateFlow(emptyList<SkillRepository.Skill>()) }
    val allSkills by (skillRepository?.skills ?: fallbackSkills).collectAsState()
    val platformSkills = remember(allSkills, entries) {
        allSkills.mapNotNull { skill ->
            val req = skillRepository?.loadSkillRequirements(skill.id) ?: return@mapNotNull null
            if (req.env.isEmpty()) return@mapNotNull null
            Triple(skill, req, computePlatformTier(envVarRepository, req))
        }
    }
    val platformVarKeys = remember(platformSkills) {
        platformSkills.flatMap { (_, req, _) -> req.env.keys.map { it.uppercase() } }.toSet()
    }
    val otherEntries = remember(entries, platformVarKeys) {
        entries.filter { it.key.uppercase() !in platformVarKeys }
    }

    SettingsScaffold(
        title = stringResource(R.string.env_var_title),
        onBack = null,
        // T75-part1 moved Add off a FAB onto the top-bar action slot;
        // kept here for visual continuity.
        actions = {
            IconButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.env_var_add))
            }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.env_var_privacy_header),
            footer = stringResource(R.string.env_var_privacy_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.env_var_privacy_toggle),
                checked = privacyEnabled,
                onCheckedChange = { com.openminis.app.data.EnvVarPrivacyStore.setEnabled(it) },
                showDivider = false,
            )
        }

        if (platformSkills.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.env_var_platform_header)) {
                platformSkills.forEachIndexed { index, (skill, req, tier) ->
                    PlatformIntegrationCard(
                        skill = skill,
                        requirements = req,
                        tier = tier,
                        envVarRepository = envVarRepository,
                        platformEntries = entries.filter { it.key.uppercase() in platformVarKeys },
                        onAddEnvVar = { key, note ->
                            prefill = DeepLinkCoordinator.EnvVarCreate(key.uppercase(), "", note)
                            showAddSheet = true
                        },
                        onEditEnvVar = { id -> editEntryId = id },
                    )
                    if (index < platformSkills.lastIndex) {
                        androidx.compose.material3.HorizontalDivider()
                    }
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.env_var_section_header),
            footer = stringResource(R.string.env_var_section_footer),
        ) {
            if (otherEntries.isEmpty()) {
                // Centred empty-state message inside the same card so the
                // section visually owns it (instead of an empty card +
                // separately-positioned text block).
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.env_var_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.env_var_empty_action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                otherEntries.forEachIndexed { index, entry ->
                    val isVisible = entry.key in visibleKeys.value
                    val displayValue = if (isVisible) {
                        envVarRepository.getValue(entry.key) ?: ""
                    } else {
                        "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
                    }
                    // iOS parity (EnvironmentVariablesView.swift:139-151): show
                    // value on one line and the optional note on a second.
                    // SettingsRow only has one subtitle slot, so concatenate
                    // with a newline; both render in the same secondary style.
                    val subtitleText = if (entry.note.isNotEmpty()) {
                        "$displayValue\n${entry.note}"
                    } else {
                        displayValue
                    }
                    SettingsRow(
                        title = entry.key,
                        subtitle = subtitleText,
                        showChevron = false,
                        showDivider = index < otherEntries.size - 1,
                        onClick = { editEntryId = entry.id },
                        trailing = {
                            Row {
                                IconButton(onClick = {
                                    visibleKeys.value = if (isVisible)
                                        visibleKeys.value - entry.key
                                    else
                                        visibleKeys.value + entry.key
                                }) {
                                    Icon(
                                        if (isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = stringResource(R.string.env_var_toggle_visibility),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(onClick = {
                                    val v = envVarRepository.getValue(entry.key) ?: ""
                                    clipboardManager.setText(AnnotatedString("${entry.key}=$v"))
                                }) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.common_copy),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(onClick = { deleteEntryId = entry.id }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.common_delete),
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // Add/Edit sheet
    if (showAddSheet || editEntryId != null) {
        val editing = editEntryId?.let { id -> entries.find { it.id == id } }
        EnvVarFormSheet(
            editEntry = editing,
            prefillKey = prefill?.key.orEmpty(),
            prefillValue = prefill?.value.orEmpty(),
            prefillNote = prefill?.note.orEmpty(),
            envVarRepository = envVarRepository,
            onDismiss = {
                showAddSheet = false
                editEntryId = null
                prefill = null
            },
        )
    }

    // Delete confirmation
    if (deleteEntryId != null) {
        val entry = entries.find { it.id == deleteEntryId }
        AlertDialog(
            onDismissRequest = { deleteEntryId = null },
            title = { Text(stringResource(R.string.delete_named, entry?.key ?: stringResource(R.string.env_var_fallback))) },
            text = { Text(stringResource(R.string.env_var_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    deleteEntryId?.let { envVarRepository.delete(it) }
                    deleteEntryId = null
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteEntryId = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvVarFormSheet(
    editEntry: EnvVarRepository.EnvVarEntry?,
    envVarRepository: EnvVarRepository,
    onDismiss: () -> Unit,
    prefillKey: String = "",
    prefillValue: String = "",
    prefillNote: String = "",
) {
    val sheetState = rememberModalBottomSheetState()
    var keyText by remember { mutableStateOf(editEntry?.key ?: prefillKey) }
    var valueText by remember {
        mutableStateOf(editEntry?.let { envVarRepository.getValue(it.key) } ?: prefillValue)
    }
    var noteText by remember { mutableStateOf(editEntry?.note ?: prefillNote) }

    val isEditing = editEntry != null
    val normalizedKey = keyText.trim().uppercase()
    val isValid = envVarRepository.isValidKey(normalizedKey)
    val isDuplicate = envVarRepository.isDuplicateKey(normalizedKey, excludeId = editEntry?.id)
    val canSave = isValid && !isDuplicate && keyText.isNotBlank()

    val errorText = when {
        keyText.isNotBlank() && !isValid -> stringResource(R.string.env_var_error_invalid_key)
        isDuplicate -> stringResource(R.string.env_var_error_duplicate)
        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isEditing) stringResource(R.string.env_var_form_edit_title) else stringResource(R.string.env_var_form_add_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = stringResource(R.string.env_var_field_name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            DialogTextField(
                value = keyText,
                onValueChange = { keyText = it.uppercase() },
                singleLine = true,
                isError = errorText != null,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            if (errorText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = stringResource(R.string.env_var_field_value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            DialogTextField(
                value = valueText,
                onValueChange = { valueText = it },
                singleLine = false,
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )

            // iOS parity: optional human-readable note explaining what the
            // variable is for. Stored alongside the metadata, surfaced in the
            // list row below the value.
            Text(
                text = stringResource(R.string.env_var_field_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            DialogTextField(
                value = noteText,
                onValueChange = { noteText = it },
                placeholder = stringResource(R.string.env_var_field_note_placeholder),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                MinisTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
                MinisTextButton(
                    onClick = {
                        val success = if (isEditing) {
                            envVarRepository.update(editEntry!!.id, keyText, valueText, noteText)
                        } else {
                            envVarRepository.add(keyText, valueText, noteText)
                        }
                        if (success) onDismiss()
                    },
                    enabled = canSave,
                ) {
                    Text(if (isEditing) stringResource(R.string.common_save) else stringResource(R.string.env_var_add))
                }
            }
        }
    }
}

@Composable
private fun PlatformIntegrationCard(
    skill: SkillRepository.Skill,
    requirements: SkillRepository.SkillRequirements,
    tier: Int,
    envVarRepository: EnvVarRepository,
    platformEntries: List<EnvVarRepository.EnvVarEntry>,
    onAddEnvVar: (key: String, note: String) -> Unit,
    onEditEnvVar: (entryId: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = skill.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TierBadge(tier = tier)
        }

        requirements.env.forEach { (varKey, varDesc) ->
            val existingEntry = platformEntries.find {
                it.key.equals(varKey, ignoreCase = true)
            }
            val isConfigured = existingEntry != null &&
                envVarRepository.getValue(existingEntry.key) != null

            val rowColor = if (isConfigured)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
            else
                MaterialTheme.colorScheme.error.copy(alpha = 0.06f)
            val dotColor = if (isConfigured)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error
            val keyColor = if (isConfigured)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.error

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(rowColor)
                    .clickable {
                        if (existingEntry != null) {
                            onEditEnvVar(existingEntry.id)
                        } else {
                            onAddEnvVar(varKey, varDesc)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(dotColor, CircleShape),
                )
                Text(
                    text = varKey,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = keyColor,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (isConfigured)
                        stringResource(R.string.env_var_platform_var_configured)
                    else
                        stringResource(R.string.env_var_platform_var_missing, varKey),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TierBadge(tier: Int) {
    val (label, color) = when (tier) {
        2 -> stringResource(R.string.env_var_platform_tier_2) to MaterialTheme.colorScheme.primary
        1 -> stringResource(R.string.env_var_platform_tier_1) to Color(0xFFD4A017)
        else -> stringResource(R.string.env_var_platform_tier_0) to MaterialTheme.colorScheme.error
    }
    Text(
        text = label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun computePlatformTier(
    envVarRepository: EnvVarRepository,
    req: SkillRepository.SkillRequirements,
): Int {
    val keys = req.env.keys.toList()
    if (keys.isEmpty()) return 2
    val configured = keys.count { envVarRepository.getValue(it.uppercase()) != null }
    return when {
        configured == keys.size -> 2
        configured > 0 -> 1
        else -> 0
    }
}
