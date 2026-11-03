package com.openminis.app.ui.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.data.repository.KnowledgeNoteItem
import com.openminis.app.knowledge.KnowledgeNoteTransfer
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisCenterTopBar
import com.openminis.app.ui.components.MinisOutlinedButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-m8-knowledge-workbench] Create or edit a single note. noteId=null means
 * create-new; in edit mode the archived version timestamps render below the
 * editor (bodies live in the compact archive, this list is the history index).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeNoteEditorScreen(
    noteId: String?,
    viewModel: KnowledgeWorkbenchViewModel,
    onBack: () -> Unit,
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val versions by viewModel.versions.collectAsStateWithLifecycle()
    val existing: KnowledgeNoteItem? = remember(noteId, notes) {
        notes.firstOrNull { it.id == noteId }
    }

    var title by remember(noteId) { mutableStateOf(existing?.title ?: "") }
    var body by remember(noteId) { mutableStateOf(existing?.bodyMd ?: "") }
    var tagsText by remember(noteId) {
        mutableStateOf(existing?.let { KnowledgeNoteTransfer.parseTags(it.tagsJson).joinToString(", ") } ?: "")
    }
    var confirmTrash by remember { mutableStateOf(false) }

    LaunchedEffect(noteId) { noteId?.let { viewModel.loadVersions(it) } }

    val parsedTags = remember(tagsText) {
        tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    val canSave = title.isNotBlank()

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = {
                    Text(stringResource(if (noteId == null) R.string.knowledge_note_new else R.string.knowledge_note_edit))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (noteId != null) {
                        IconButton(onClick = { confirmTrash = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.knowledge_trash))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.knowledge_title_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(stringResource(R.string.knowledge_body_label)) },
                placeholder = { Text(stringResource(R.string.knowledge_body_hint)) },
                minLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text(stringResource(R.string.knowledge_tags_label)) },
                supportingText = { Text(stringResource(R.string.knowledge_tags_hint)) },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            if (parsedTags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    parsedTags.forEach { tag ->
                        FilterChip(
                            selected = true,
                            onClick = { tagsText = parsedTags.filterNot { it == tag }.joinToString(", ") },
                            label = { Text(tag, fontSize = 14.sp) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                MinisButton(
                    onClick = {
                        if (noteId == null) {
                            viewModel.create(title, body, parsedTags) { onBack() }
                        } else {
                            viewModel.update(noteId, title, body, parsedTags)
                            onBack()
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.knowledge_save))
                }
                MinisOutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cancel))
                }
            }

            if (noteId != null && versions.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.knowledge_versions_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                versions.forEach { v ->
                    Text(
                        stringResource(R.string.knowledge_version_row, v.version, formatTs(v.bodyAt)),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text(stringResource(R.string.knowledge_trash_confirm_title)) },
            text = { Text(stringResource(R.string.knowledge_trash_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    noteId?.let { viewModel.trash(it) }
                    confirmTrash = false
                    onBack()
                }) { Text(stringResource(R.string.knowledge_trash)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrash = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun formatTs(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
