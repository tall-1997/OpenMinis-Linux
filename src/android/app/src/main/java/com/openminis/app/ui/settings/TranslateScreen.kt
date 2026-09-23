package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.ui.components.DialogTextField
import kotlinx.coroutines.launch

@Composable
fun TranslateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("中文") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)
    SettingsScaffold(title = stringResource(R.string.settings_translate), onBack = onBack) {
        SettingsSection(footer = stringResource(R.string.translate_footer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                DialogTextField(
                    source,
                    { source = it },
                    placeholder = stringResource(R.string.translate_source),
                    singleLine = false,
                )
                DialogTextField(
                    lang,
                    { lang = it },
                    placeholder = stringResource(R.string.translate_target),
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    enabled = !busy && source.isNotBlank(),
                    onClick = {
                        busy = true
                        result = ""
                        scope.launch {
                            result = translateOnce(context, source, lang)
                            busy = false
                        }
                    },
                ) { Text(stringResource(if (busy) R.string.translate_working else R.string.translate_run)) }
                if (result.isNotBlank()) {
                    Text(result, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

private suspend fun translateOnce(context: android.content.Context, text: String, lang: String): String {
    val app = context.applicationContext as? MinisApp
        ?: return context.getString(R.string.translate_no_model)
    val repo = app.providerRepository
    val cfg = repo.config.value
    val enabled = cfg.instances.filter { it.isEnabled }.associateBy { it.id }
    val entry = cfg.modelEntries.firstOrNull { !it.isHidden && enabled.containsKey(it.providerInstanceId) }
        ?: return context.getString(R.string.translate_no_model)
    val instance = enabled[entry.providerInstanceId] ?: return context.getString(R.string.translate_no_model)
    val key = repo.usableApiKey(instance) ?: return context.getString(R.string.translate_no_model)
    return runCatching {
        val provider = ProviderFactory.create(instance, key, entry.model, context)
        provider.sendMessage(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = text)),
            systemPrompt = "You are a translator. Return only the translation into $lang, no preface.",
            maxTokens = 2048,
            temperature = 0.2,
            thinkingLevel = ThinkingLevel.OFF,
        ).text.ifBlank { context.getString(R.string.translate_empty) }
    }.getOrElse { it.message ?: context.getString(R.string.translate_empty) }
}
