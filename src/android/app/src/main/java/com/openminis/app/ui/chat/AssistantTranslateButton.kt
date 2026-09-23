package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.launch

private const val PREFS = "translate_bubble"
private const val KEY_MODEL = "model_id"
private const val KEY_LANG = "lang"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantTranslateButton(
    source: String,
    onTranslated: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf(prefs.getString(KEY_LANG, "中文") ?: "中文") }
    var modelId by remember { mutableStateOf(prefs.getString(KEY_MODEL, "") ?: "") }
    var error by remember { mutableStateOf("") }
    val models = remember { translationModelChoices(context) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, end = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp).padding(4.dp), strokeWidth = 2.dp)
        }
        IconButton(
            onClick = { if (!busy) open = true },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_translate_bubble),
                contentDescription = stringResource(R.string.translate_bubble),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }

    if (!open) return
    ModalBottomSheet(onDismissRequest = { if (!busy) open = false }) {
        Text(
            stringResource(R.string.translate_bubble),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ModelPicker(
            models = models,
            selectedId = modelId.ifBlank { models.firstOrNull()?.first.orEmpty() },
            onSelect = { modelId = it },
        )
        OutlinedTextField(
            value = lang,
            onValueChange = { lang = it },
            label = { Text(stringResource(R.string.translate_target)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
        )
        if (error.isNotBlank()) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        TextButton(
            enabled = !busy && source.isNotBlank() && lang.isNotBlank(),
            onClick = {
                busy = true
                error = ""
                prefs.edit().putString(KEY_MODEL, modelId).putString(KEY_LANG, lang).apply()
                scope.launch {
                    val result = translateBubble(context, source, lang, modelId)
                    busy = false
                    if (result.startsWith("Error:") || result == context.getString(R.string.translate_no_model)) {
                        error = result
                    } else {
                        onTranslated(result)
                        open = false
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.translate_run)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    models: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = models.firstOrNull { it.first == selectedId }?.second
        ?: stringResource(R.string.translate_no_model)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.translate_pick_model)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun translationModelChoices(context: android.content.Context): List<Pair<String, String>> {
    val app = context.applicationContext as? MinisApp ?: return emptyList()
    val cfg = app.providerRepository.config.value
    val enabled = cfg.instances.filter { it.isEnabled }.associateBy { it.id }
    return cfg.modelEntries
        .filter { !it.isHidden && enabled.containsKey(it.providerInstanceId) }
        .map { it.id to it.model.displayName }
}

private suspend fun translateBubble(
    context: android.content.Context,
    text: String,
    lang: String,
    modelId: String,
): String {
    val app = context.applicationContext as? MinisApp
        ?: return context.getString(R.string.translate_no_model)
    val repo = app.providerRepository
    val cfg = repo.config.value
    val enabled = cfg.instances.filter { it.isEnabled }.associateBy { it.id }
    val entry = cfg.modelEntries.firstOrNull { it.id == modelId && !it.isHidden }
        ?: cfg.modelEntries.firstOrNull { !it.isHidden && enabled.containsKey(it.providerInstanceId) }
        ?: return context.getString(R.string.translate_no_model)
    val instance = enabled[entry.providerInstanceId] ?: return context.getString(R.string.translate_no_model)
    val key = repo.usableApiKey(instance) ?: return context.getString(R.string.translate_no_model)
    return runCatching {
        val provider = ProviderFactory.create(instance, key, entry.model, context)
        provider.sendMessage(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = text)),
            systemPrompt = "You are a translator. Return only the translation into $lang. No preface.",
            maxTokens = 4096,
            temperature = 0.2,
            thinkingLevel = ThinkingLevel.OFF,
        ).text.ifBlank { context.getString(R.string.translate_empty) }
    }.getOrElse { "Error: ${it.message ?: context.getString(R.string.translate_empty)}" }
}
