package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.accessibility.A11yScriptRecorder
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.components.DialogTextField
import kotlinx.coroutines.launch

@Composable
fun A11yRecordDialog(
    skillRepository: SkillRepository,
    onDismiss: () -> Unit,
) {
    val recording by A11yScriptRecorder.recording.collectAsStateWithLifecycle()
    val steps by A11yScriptRecorder.steps.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("recorded-scene") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = {
            if (recording) A11yScriptRecorder.stop()
            onDismiss()
        },
        title = { Text(stringResource(R.string.a11y_record_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.a11y_record_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DialogTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "recorded-scene",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                Text(
                    stringResource(R.string.a11y_record_count, steps.size),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (recording) {
                TextButton(onClick = { A11yScriptRecorder.stop() }) {
                    Text(stringResource(R.string.a11y_record_stop))
                }
            } else {
                TextButton(
                    onClick = {
                        if (!A11yScriptRecorder.start()) {
                            error = "Accessibility service is not running"
                        } else {
                            error = null
                        }
                    },
                ) { Text(stringResource(R.string.a11y_record_start)) }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (recording) A11yScriptRecorder.stop()
                    if (steps.isEmpty()) {
                        onDismiss()
                        return@TextButton
                    }
                    val md = A11yScriptRecorder.toSkillMarkdown(name)
                    scope.launch {
                        skillRepository.importFromContent(md)
                        onDismiss()
                    }
                },
            ) {
                Text(
                    if (steps.isEmpty()) stringResource(R.string.cancel)
                    else stringResource(R.string.a11y_record_save),
                )
            }
        },
    )
}
