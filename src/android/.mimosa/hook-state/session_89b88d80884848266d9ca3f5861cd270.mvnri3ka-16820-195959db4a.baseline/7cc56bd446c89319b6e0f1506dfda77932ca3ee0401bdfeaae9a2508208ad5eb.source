package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.PersonaPromptLibrary
import com.openminis.app.agent.PersonaPromptLogic
import com.openminis.app.agent.SoulMDParser
import com.openminis.app.agent.SoulStore
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoulPromptEditorScreen(
    promptId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileName by remember { mutableStateOf("") }
    var builtin by remember { mutableStateOf(promptId == PersonaPromptLogic.BUILTIN_ID) }
    var body by remember { mutableStateOf("") }
    var baseline by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(promptId) {
        val loadedBody = withContext(Dispatchers.IO) {
            val index = PersonaPromptLibrary.loadIndex(context)
            val entry = index.prompts.find { it.id == promptId }
            Triple(
                entry?.fileName ?: PersonaPromptLogic.BUILTIN_FILE_NAME,
                entry?.builtin != false,
                PersonaPromptLibrary.readBody(context, promptId),
            )
        }
        fileName = loadedBody.first
        builtin = loadedBody.second
        body = loadedBody.third
        baseline = loadedBody.third
        loaded = true
    }

    val isDirty = loaded && baseline != null && body != baseline

    fun persistAndBack() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PersonaPromptLibrary.writeBody(context, promptId, body)
                }
                saveError = null
                onBack()
            } catch (t: Throwable) {
                saveError = t.message ?: context.getString(R.string.soul_save_error_title)
            }
        }
    }

    fun attemptBack() {
        if (isDirty) showDiscardDialog = true else onBack()
    }

    BackHandler(onBack = { attemptBack() })

    SettingsScaffold(
        title = fileName,
        onBack = { attemptBack() },
        scrollable = false,
        actions = {
            MinisTextButton(
                onClick = { persistAndBack() },
                enabled = loaded && isDirty,
            ) {
                Text(stringResource(R.string.soul_save))
            }
        },
    ) {
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxSize(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
            placeholder = { Text(stringResource(R.string.soul_body_placeholder)) },
        )
        Text(
            text = soulBodyCountTextAndroid(body),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (builtin) {
            MinisTextButton(
                onClick = { showRestoreDialog = true },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.soul_restore_default))
            }
        } else {
            MinisTextButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.soul_prompt_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.soul_discard_confirm_title)) },
            text = { Text(stringResource(R.string.soul_discard_confirm_body)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text(stringResource(R.string.soul_discard_confirm)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.soul_discard_cancel))
                }
            },
        )
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(stringResource(R.string.soul_restore_confirm_title)) },
            text = { Text(stringResource(R.string.soul_restore_confirm_body)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    val parsed = SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
                    body = parsed.body
                    showRestoreDialog = false
                }) { Text(stringResource(R.string.soul_ok)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showRestoreDialog = false }) {
                    Text(stringResource(R.string.soul_cancel))
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.soul_prompt_delete_title)) },
            text = { Text(stringResource(R.string.soul_prompt_delete_body, fileName)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            PersonaPromptLibrary.deleteImported(context, promptId)
                        }
                        onBack()
                    }
                }) { Text(stringResource(R.string.soul_prompt_delete)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.soul_cancel))
                }
            },
        )
    }

    saveError?.let { msg ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text(stringResource(R.string.soul_save_error_title)) },
            text = { Text(msg) },
            confirmButton = {
                MinisTextButton(onClick = { saveError = null }) {
                    Text(stringResource(R.string.soul_ok))
                }
            },
        )
    }
}
