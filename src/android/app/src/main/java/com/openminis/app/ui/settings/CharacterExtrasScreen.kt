package com.openminis.app.ui.settings

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.DisplayRegex
import com.openminis.app.agent.WorldBook
import com.openminis.app.ui.HighRefreshRate
import com.openminis.app.ui.components.DialogTextField
import java.util.UUID

@Composable
fun CharacterExtrasScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(WorldBook.load(context)) }
    var rules by remember { mutableStateOf(DisplayRegex.load(context)) }
    var name by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var pendingRemove by remember { mutableStateOf<(() -> Unit)?>(null) }

    BackHandler(onBack = onBack)
    pendingRemove?.let { remove ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            confirmButton = {
                TextButton(onClick = {
                    remove()
                    pendingRemove = null
                }) { Text(stringResource(R.string.character_extras_confirm_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            text = { Text(stringResource(R.string.character_extras_confirm_body)) },
        )
    }
    SettingsScaffold(title = stringResource(R.string.settings_character_extras), onBack = onBack) {
        SettingsSection(
            header = stringResource(R.string.world_book_header),
            footer = stringResource(R.string.world_book_footer),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                DialogTextField(name, { name = it }, placeholder = stringResource(R.string.world_book_name))
                DialogTextField(
                    keywords,
                    { keywords = it },
                    placeholder = stringResource(R.string.world_book_keywords),
                    modifier = Modifier.padding(top = 8.dp),
                )
                DialogTextField(
                    content,
                    { content = it },
                    placeholder = stringResource(R.string.world_book_content),
                    singleLine = false,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    onClick = {
                        if (content.isBlank() || keywords.isBlank()) return@TextButton
                        val next = entries + WorldBook.Entry(
                            id = UUID.randomUUID().toString(),
                            name = name.ifBlank { keywords },
                            content = content.trim(),
                            keywords = keywords.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() },
                        )
                        entries = next
                        WorldBook.save(context, next)
                        name = ""; keywords = ""; content = ""
                    },
                ) { androidx.compose.material3.Text(stringResource(R.string.world_book_add)) }
            }
            entries.forEachIndexed { index, entry ->
                SettingsRow(
                    title = entry.name,
                    subtitle = entry.keywords.joinToString(", ") + " · " + stringResource(R.string.character_extras_tap_remove),
                    showDivider = index < entries.lastIndex,
                    onClick = {
                        pendingRemove = {
                            val next = entries.filterNot { it.id == entry.id }
                            entries = next
                            WorldBook.save(context, next)
                        }
                    },
                )
            }
        }
        SettingsSection(
            header = stringResource(R.string.display_regex_header),
            footer = stringResource(R.string.display_regex_footer),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                DialogTextField(pattern, { pattern = it }, placeholder = stringResource(R.string.display_regex_pattern))
                DialogTextField(
                    replacement,
                    { replacement = it },
                    placeholder = stringResource(R.string.display_regex_replacement),
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    onClick = {
                        if (pattern.isBlank()) return@TextButton
                        val next = rules + DisplayRegex.Rule(
                            id = UUID.randomUUID().toString(),
                            name = pattern,
                            pattern = pattern,
                            replacement = replacement,
                            scopes = setOf(DisplayRegex.Scope.ASSISTANT, DisplayRegex.Scope.USER),
                            visualOnly = true,
                        )
                        rules = next
                        DisplayRegex.save(context, next)
                        pattern = ""; replacement = ""
                    },
                ) { androidx.compose.material3.Text(stringResource(R.string.display_regex_add)) }
            }
            rules.forEachIndexed { index, rule ->
                SettingsRow(
                    title = rule.pattern,
                    subtitle = rule.replacement + " · " + stringResource(R.string.character_extras_tap_remove),
                    showDivider = index < rules.lastIndex,
                    onClick = {
                        pendingRemove = {
                            val next = rules.filterNot { it.id == rule.id }
                            rules = next
                            DisplayRegex.save(context, next)
                        }
                    },
                )
            }
        }
    }
}
