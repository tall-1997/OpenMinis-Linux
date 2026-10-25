package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.SkillFeed
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.data.repository.SkillSubscriptionStore
import com.openminis.app.data.repository.SkillSubscriptionSync
import com.openminis.app.ui.components.DialogTextField
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SkillFeedsSection(skillRepository: SkillRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf(SkillSubscriptionStore.list(context)) }
    var showAdd by remember { mutableStateOf(false) }

    SettingsSection(
        header = stringResource(R.string.skill_feeds_header),
        footer = stringResource(R.string.skill_feeds_footer),
    ) {
        if (feeds.isEmpty()) {
            Text(
                stringResource(R.string.skill_feeds_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        } else {
            val autoLabel = stringResource(R.string.skill_feeds_auto)
            feeds.forEachIndexed { index, feed ->
                val subtitle = buildString {
                    if (feed.autoUpdate) append(autoLabel)
                    feed.lastError?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it.take(80))
                    } ?: feed.lastSha256?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it.take(12))
                    }
                }.ifBlank { feed.url }
                SettingsRow(
                    title = feed.label.ifBlank { feed.url },
                    subtitle = subtitle,
                    showChevron = false,
                    showDivider = index < feeds.lastIndex,
                    onClick = {
                        scope.launch {
                            SkillSubscriptionSync.refresh(context, skillRepository, feed.id)
                            feeds = SkillSubscriptionStore.list(context)
                        }
                    },
                    trailing = {
                        TextButton(onClick = {
                            SkillSubscriptionStore.remove(context, feed.id)
                            feeds = SkillSubscriptionStore.list(context)
                        }) { Text(stringResource(R.string.delete)) }
                    },
                )
            }
        }
        SettingsRow(
            title = stringResource(R.string.skill_feeds_add),
            showChevron = true,
            showDivider = false,
            onClick = { showAdd = true },
        )
    }

    if (showAdd) {
        var url by remember { mutableStateOf("") }
        var pin by remember { mutableStateOf("") }
        var auto by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.skill_feeds_add)) },
            text = {
                Column {
                    DialogTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = "https://…/SKILL.md",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DialogTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        placeholder = stringResource(R.string.skill_feeds_sha256),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.skill_feeds_auto),
                        checked = auto,
                        onCheckedChange = { auto = it },
                        showDivider = false,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = url.trim()
                        if (trimmed.isNotEmpty()) {
                            SkillSubscriptionStore.upsert(
                                context,
                                SkillFeed(
                                    id = UUID.randomUUID().toString(),
                                    url = trimmed,
                                    label = trimmed.substringAfterLast('/').ifBlank { trimmed },
                                    sha256Pin = pin.trim().ifEmpty { null },
                                    autoUpdate = auto,
                                ),
                            )
                            feeds = SkillSubscriptionStore.list(context)
                            val id = SkillSubscriptionStore.list(context).firstOrNull { it.url == trimmed }?.id
                            if (id != null) {
                                scope.launch {
                                    SkillSubscriptionSync.refresh(context, skillRepository, id)
                                    feeds = SkillSubscriptionStore.list(context)
                                }
                            }
                        }
                        showAdd = false
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
