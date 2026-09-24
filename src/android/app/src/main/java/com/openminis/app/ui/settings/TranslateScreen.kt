package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.openminis.app.i18n.TranslationOutcome
import com.openminis.app.i18n.TranslationPrefs
import com.openminis.app.i18n.TranslationRunner
import com.openminis.app.ui.components.DialogTextField
import kotlinx.coroutines.launch

/**
 * Standalone translation. Uses the language and model already saved in
 * Settings. The result stays on this page and is not written into a conversation.
 */
@Composable
fun TranslateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf(TranslationPrefs.lang(context)) }
    var result by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val entry = remember { TranslationRunner.resolveEntry(context) }

    BackHandler(onBack = onBack)
    SettingsScaffold(title = stringResource(R.string.translate_pad_title), onBack = null) {
        SettingsSection(footer = stringResource(R.string.translate_footer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    if (entry == null) {
                        stringResource(R.string.translate_set_in_defaults)
                    } else {
                        stringResource(R.string.translate_using, entry.model.displayName)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DialogTextField(
                    source,
                    { source = it },
                    placeholder = stringResource(R.string.translate_source),
                    singleLine = false,
                    modifier = Modifier.padding(top = 8.dp),
                )
                DialogTextField(
                    lang,
                    {
                        lang = it
                        TranslationPrefs.setLang(context, it)
                    },
                    placeholder = stringResource(R.string.translate_target),
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    enabled = !busy && source.isNotBlank() && entry != null,
                    onClick = {
                        busy = true
                        result = ""
                        failed = false
                        val target = lang
                        scope.launch {
                            try {
                                when (val out = TranslationRunner.translate(context, source, target)) {
                                    is TranslationOutcome.Text -> {
                                        result = out.value
                                        failed = false
                                    }
                                    is TranslationOutcome.Failed -> {
                                        result = out.message
                                        failed = true
                                    }
                                }
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(stringResource(if (busy) R.string.translate_working else R.string.translate_run)) }
                if (result.isNotBlank()) {
                    Text(
                        result,
                        modifier = Modifier.padding(top = 8.dp),
                        color = if (failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
