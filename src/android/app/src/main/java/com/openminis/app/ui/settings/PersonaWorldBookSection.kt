package com.openminis.app.ui.settings

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
import com.openminis.app.ui.components.DialogTextField
import java.util.UUID

/**
 * Foolproof world book on Settings → Persona. Two fields, one button.
 * Regex and role flags stay at their defaults so the user does not have to
 * know what they mean.
 */
@Composable
fun PersonaWorldBookSection() {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(WorldBook.load(context)) }
    var whenSaid by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<WorldBook.Entry?>(null) }

    var rules by remember { mutableStateOf(DisplayRegex.load(context)) }
    var hidePattern by remember { mutableStateOf("") }
    // 1.36.27 requires a confirm before delete for BOTH lists; the regex
    // section below used to delete on first tap. Same strings as the world
    // book dialog and CharacterExtrasScreen so the wording stays uniform.
    var pendingRule by remember { mutableStateOf<DisplayRegex.Rule?>(null) }

    pending?.let { entry ->
        AlertDialog(
            onDismissRequest = { pending = null },
            text = { Text(stringResource(R.string.character_extras_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val next = entries.filterNot { it.id == entry.id }
                    WorldBook.save(context, next)
                    entries = next
                    pending = null
                }) { Text(stringResource(R.string.character_extras_confirm_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    SettingsSection(
        header = stringResource(R.string.persona_worldbook_header),
        footer = stringResource(R.string.persona_worldbook_footer),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            entries.forEach { entry ->
                SettingsRow(
                    title = entry.keywords.joinToString("、").ifBlank { entry.name },
                    subtitle = entry.content.take(80),
                    showChevron = false,
                    onClick = { pending = entry },
                )
            }
            DialogTextField(
                whenSaid,
                { whenSaid = it },
                placeholder = stringResource(R.string.persona_worldbook_when),
            )
            DialogTextField(
                extra,
                { extra = it },
                placeholder = stringResource(R.string.persona_worldbook_say),
                modifier = Modifier.padding(top = 8.dp),
                singleLine = false,
            )
            TextButton(
                enabled = whenSaid.isNotBlank() && extra.isNotBlank(),
                onClick = {
                    val keys = whenSaid.split(',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }
                    val next = entries + WorldBook.Entry(
                        id = UUID.randomUUID().toString(),
                        name = keys.first().take(40),
                        content = extra.trim(),
                        keywords = keys,
                    )
                    WorldBook.save(context, next)
                    entries = next
                    whenSaid = ""
                    extra = ""
                },
            ) { Text(stringResource(R.string.persona_worldbook_add)) }
        }
    }

    pendingRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingRule = null },
            text = { Text(stringResource(R.string.character_extras_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val next = rules.filterNot { it.id == rule.id }
                    DisplayRegex.save(context, next)
                    rules = next
                    pendingRule = null
                }) { Text(stringResource(R.string.character_extras_confirm_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRule = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    SettingsSection(
        header = stringResource(R.string.persona_hide_header),
        footer = stringResource(R.string.persona_hide_footer),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            rules.forEach { rule ->
                SettingsRow(
                    title = rule.pattern,
                    subtitle = stringResource(R.string.persona_hide_tap_remove),
                    showChevron = false,
                    onClick = { pendingRule = rule },
                )
            }
            DialogTextField(
                hidePattern,
                { hidePattern = it },
                placeholder = stringResource(R.string.display_regex_pattern),
            )
            TextButton(
                enabled = hidePattern.isNotBlank(),
                onClick = {
                    val next = rules + DisplayRegex.Rule(
                        id = UUID.randomUUID().toString(),
                        name = hidePattern.trim().take(40),
                        pattern = hidePattern.trim(),
                        replacement = "",
                        scopes = setOf(DisplayRegex.Scope.ASSISTANT, DisplayRegex.Scope.USER),
                    )
                    DisplayRegex.save(context, next)
                    rules = next
                    hidePattern = ""
                },
            ) { Text(stringResource(R.string.persona_hide_add)) }
        }
    }
}
