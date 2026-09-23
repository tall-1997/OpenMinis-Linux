package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.i18n.TranslationPrefs
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "translate_bubble"
private const val KEY_LANG = "lang"

/**
 * Labeled control at the end of one assistant segment. The model comes from
 * Settings → Model groups → Defaults → Translation, not from a picker here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantTranslateButton(
    source: String,
    onTranslated: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (!TranslationPrefs.isEnabled(context)) return
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf(TranslationPrefs.lang(context)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var modelName by remember { mutableStateOf<String?>(null) }
    val missing = stringResource(R.string.translate_set_in_defaults)
    val noModel = stringResource(R.string.translate_no_model)

    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        val entry = withContext(Dispatchers.IO) {
            resolveBubbleEntry(context)
        }
        modelName = entry?.model?.displayName
        if (entry == null) error = missing
    }

    Box(modifier = modifier.size(32.dp), contentAlignment = Alignment.Center) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Language,
                    contentDescription = stringResource(R.string.translate_action),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (!open) return
    ModalBottomSheet(onDismissRequest = { if (!busy) open = false }) {
        Column(modifier = Modifier.fillMaxWidth().imePadding().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.translate_bubble),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                modelName?.let { stringResource(R.string.translate_using_model, it) } ?: missing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = lang,
                onValueChange = { lang = it },
                label = { Text(stringResource(R.string.translate_target)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            TextButton(
                onClick = {
                    busy = true
                    error = null
                    TranslationPrefs.setLang(context, lang)
                    scope.launch {
                        val result = translateBubble(context, source, lang)
                        busy = false
                        if (result.startsWith("Error:") || result == noModel || result == missing) {
                            error = result.removePrefix("Error:").trim()
                        } else {
                            onTranslated(result)
                            open = false
                        }
                    }
                },
                enabled = !busy && source.isNotBlank() && lang.isNotBlank() && modelName != null,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(if (busy) stringResource(R.string.translate_working) else stringResource(R.string.translate_run))
            }
        }
    }
}

private suspend fun translateBubble(context: android.content.Context, text: String, lang: String): String {
    val app = context.applicationContext as? MinisApp
        ?: return "Error: " + context.getString(R.string.translate_no_model)
    val repo = app.providerRepository
    val entry = withContext(Dispatchers.IO) { resolveBubbleEntry(context) }
        ?: return "Error: " + context.getString(R.string.translate_set_in_defaults)
    val instance = repo.instance(entry.providerInstanceId)
        ?: return "Error: " + context.getString(R.string.translate_no_model)
    val key = repo.usableApiKey(instance) ?: return "Error: " + context.getString(R.string.translate_no_model)
    return try {
        val provider = ProviderFactory.create(instance, key, entry.model, context)
        val response = provider.sendMessage(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = text)),
            systemPrompt = "You are a translator. Return only the translation into $lang. No preface.",
            maxTokens = 4096,
            temperature = 0.2,
            thinkingLevel = ThinkingLevel.OFF,
        )
        response.text.trim().ifBlank { "Error: " + context.getString(R.string.translate_empty) }
    } catch (e: Exception) {
        "Error: " + (e.message ?: context.getString(R.string.translate_empty))
    }
}


private fun resolveBubbleEntry(context: android.content.Context): com.openminis.app.data.model.ModelEntry? {
    val repo = (context.applicationContext as? MinisApp)?.providerRepository ?: return null
    val config = repo.config.value
    fun usable(id: String): com.openminis.app.data.model.ModelEntry? {
        val entry = config.modelEntries.find { it.id == id && !it.isHidden } ?: return null
        val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
        return entry.takeIf { inst.isEnabled }
    }
    val wanted = TranslationPrefs.entryId(context)
    if (wanted != null) return usable(wanted)
    return config.modelEntries.firstNotNullOfOrNull { if (it.isHidden) null else usable(it.id) }
}
