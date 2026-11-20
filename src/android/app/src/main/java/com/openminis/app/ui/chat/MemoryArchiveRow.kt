package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.MemoryDiaryManagement
import com.openminis.app.data.repository.MemoryLifecycle
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.settings.SettingsRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit inspection and restore. Neither action needs a model request or changes the raw diary. */
@Composable
internal fun MemoryArchiveRow(repository: MemoryRepository) {
    var open by remember(repository) { mutableStateOf(false) }
    SettingsRow(title = stringResource(R.string.sessionlist_archive), onClick = { open = true }, showDivider = false)
    if (open) MemoryArchiveDialog(repository) { open = false }
}

@Composable
private fun MemoryArchiveDialog(repository: MemoryRepository, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<MemoryDiaryManagement.ArchiveEntry>?>(null) }
    var entry by remember { mutableStateOf<MemoryDiaryManagement.ArchiveEntry?>(null) }
    var restore by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var limit by remember(query) { mutableStateOf(40) }
    LaunchedEffect(query, limit, restore) {
        restore?.let { id ->
            val ok = withContext(Dispatchers.IO) { runCatching { repository.restoreArchivedMemory(id) }.getOrDefault(false) }
            failed = !ok
            if (ok) entry = null
            restore = null
        }
        entries = withContext(Dispatchers.IO) { repository.archivedEntries(query, limit + 1) }
    }
    val selected = entry
    AlertDialog(
        onDismissRequest = { if (restore == null) onDismiss() },
        title = { Text(stringResource(R.string.sessionlist_archive)) },
        text = {
            if (selected == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogTextField(query, { query = it }, placeholder = stringResource(R.string.code_editor_search_hint))
                    val snapshot = entries
                    when {
                        snapshot == null -> Text(stringResource(R.string.filepreview_loading))
                        snapshot.isEmpty() -> Text(stringResource(R.string.model_picker_no_results))
                        else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            items(snapshot.take(limit), key = { it.token }) { item ->
                                SettingsRow(
                                    title = item.token.substringBefore(':'),
                                    subtitle = item.body.lineSequence().firstOrNull { it.isNotBlank() },
                                    onClick = { failed = false; entry = item },
                                )
                            }
                            if (snapshot.size > limit) item {
                                MinisTextButton(onClick = { limit += 40 }) { Text(stringResource(R.string.cd_more)) }
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected.token.substringBefore(':'), style = MaterialTheme.typography.labelMedium, overflow = TextOverflow.Ellipsis)
                    if (selected.status == MemoryLifecycle.Status.EXPIRED) Text(stringResource(R.string.quota_status_expired))
                    Text(selected.body)
                    if (failed) Text(stringResource(R.string.memory_save_failed_toast), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (selected != null) {
                MinisTextButton(onClick = { restore = selected.token }, enabled = restore == null) {
                    Text(stringResource(R.string.chat_restore_deleted_confirm))
                }
            } else MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        dismissButton = {
            if (selected != null) MinisTextButton(onClick = { entry = null }, enabled = restore == null) {
                Text(stringResource(R.string.memory_action_back))
            }
        },
    )
}
