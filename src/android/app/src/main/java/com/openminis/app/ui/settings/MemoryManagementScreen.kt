package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.openminis.app.ui.components.MinisCenterTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.knowledge.AppNotes
import com.openminis.app.knowledge.AppNotesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.theme.m3Icon

/**
 * Settings-level memory file management.
 * Lists GLOBAL.md + daily logs in grouped card style.
 * Tapping a file navigates to a full-page editor.
 * GLOBAL.md cannot be deleted.
 * Mirrors iOS MemoryManagementView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagementScreen(
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
    onFileClick: (fileName: String, isGlobal: Boolean) -> Unit = { _, _ -> },
    onEvolutionClick: () -> Unit = {},
    /**
     * [T-memory-center] The knowledge workbench hangs off the bottom of this page
     * rather than sitting beside "Memory" in the drawer. Both surfaces manage
     * persistent content, and a two-item landing page read as an empty menu; a
     * secondary section here keeps this page the single way in.
     */
    onOpenKnowledge: () -> Unit = {},
) {
    var files by remember { mutableStateOf<List<MemoryRepository.MemoryFileInfo>>(emptyList()) }
    var deleteFileName by remember { mutableStateOf<String?>(null) }
    // [T-memory-center-stats] "Is my memory working?" answered on this page
    // instead of behind a second tap. Null while loading or when the walk fails:
    // the block is then omitted rather than rendered as zeros that look real.
    var stats by remember {
        mutableStateOf<com.openminis.app.data.repository.MemoryCenterStats.Snapshot?>(null)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // [T-memory-global-toggle-settings-ui-android] Global default for
    // newly-created sessions. Stored separately from per-session
    // memoryEnabled (which lives in the sessions DB row) so toggling
    // here never retroactively rewrites existing chats. Read once on
    // entry; the Switch's onCheckedChange writes back synchronously
    // and updates the local state mirror.
    var globalMemoryOn by remember {
        mutableStateOf(com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context))
    }

    LaunchedEffect(Unit) {
        // listAllFiles() reads every daily log for its preview; never do that
        // on the main thread, even from a LaunchedEffect.
        files = withContext(Dispatchers.IO) { memoryRepository.listAllFiles() }
        stats = withContext(Dispatchers.IO) {
            val notes = runCatching {
                (context.applicationContext as? com.openminis.app.MinisApp)
                    ?.knowledgeNoteRepository?.listActive()?.size
            }.getOrNull() ?: 0
            runCatching {
                com.openminis.app.data.repository.MemoryCenterStats.collect(context.filesDir, notes)
            }.getOrNull()
        }
    }

    SettingsScaffold(title = stringResource(R.string.memory_title), onBack = null) {
        // [T-memory-center-stats] Overview first: the numbers answer "is there
        // anything here at all" before the user scans the file list.
        stats?.let { snapshot ->
            SettingsSection(
                header = stringResource(R.string.memory_center_overview),
                footer = stringResource(R.string.memory_center_stats_footer),
            ) {
                MemoryStatRow(R.string.memory_center_stat_memory, snapshot.files)
                MemoryStatRow(R.string.memory_center_stat_notes, snapshot.notes)
                MemoryStatRow(R.string.memory_center_stat_recalls, snapshot.recalled)
                MemoryStatRow(R.string.memory_center_stat_failures, snapshot.blockedRepeats)
                MemoryStatRow(R.string.memory_center_stat_sediments, snapshot.sediments, showDivider = false)
            }
        }

        // Always-visible global toggle — sits above the file list so the
        // user finds it whether or not any memory files exist yet.
        SettingsSection(
            header = stringResource(R.string.settings_memory_global_header),
            footer = stringResource(R.string.settings_memory_global_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_memory_global_enabled_title),
                subtitle = stringResource(R.string.settings_memory_global_enabled_subtitle),
                checked = globalMemoryOn,
                onCheckedChange = { newValue ->
                    globalMemoryOn = newValue
                    com.openminis.app.data.MemoryGlobalPrefs.setGlobalEnabled(context, newValue)
                },
                showDivider = false,
            )
        }
        SettingsSection(
            header = stringResource(R.string.memory_evolution_header),
            footer = stringResource(R.string.memory_evolution_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.settings_evolution),
                subtitle = stringResource(R.string.memory_evolution_open),
                onClick = onEvolutionClick,
                showDivider = false,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (files.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.memory_empty_title), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.memory_empty_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SettingsSection(
                header = stringResource(R.string.memory_section_files),
                footer = stringResource(R.string.memory_section_footer),
            ) {
                files.forEachIndexed { index, file ->
                    MemoryFileRow(
                        file = file,
                        onClick = { onFileClick(file.name, file.isGlobal) },
                        onDelete = if (!file.isGlobal) { { deleteFileName = file.name } } else null,
                    )
                    if (index < files.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // [T-android-app-knowledge] The agent's app-experience notes live in
        // the memory domain (they ARE memory the agent recalls), not Settings.
        AppNotesSection()

        // [T-memory-center] Secondary destination at the bottom of the page, so
        // "Memory" stays the single entry point in the drawer instead of sharing
        // the drawer with a peer "Knowledge Workbench" entry.
        SettingsSection(header = stringResource(R.string.settings_section_knowledge)) {
            SettingsRow(
                title = stringResource(R.string.settings_section_knowledge),
                subtitle = stringResource(R.string.settings_knowledge_subtitle),
                onClick = onOpenKnowledge,
                showDivider = false,
            )
        }
    }

    // Delete confirmation
    if (deleteFileName != null) {
        AlertDialog(
            onDismissRequest = { deleteFileName = null },
            title = { Text(stringResource(R.string.memory_delete_confirm_title, deleteFileName ?: "")) },
            text = { Text(stringResource(R.string.memory_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    val target = deleteFileName
                    deleteFileName = null
                    if (target != null) {
                        scope.launch {
                            // deleteFile() + the refresh both touch disk.
                            files = withContext(Dispatchers.IO) {
                                memoryRepository.deleteFile(target)
                                memoryRepository.listAllFiles()
                            }
                        }
                    }
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteFileName = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun MemoryFileRow(
    file: MemoryRepository.MemoryFileInfo,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // [T-android-memory-row-date-wrap] The name+size group flexes and
                // truncates; the date never wraps.
                //
                // Previously neither child had a weight, so the name group claimed
                // its full intrinsic width and the date was left with the
                // remainder. A daily-log name is itself a date ("2026-08-16.md"),
                // which is long enough that the 16-char "yyyy-MM-dd HH:mm" stamp no
                // longer fit — it wrapped to two lines and made that one row
                // visibly taller than its neighbours (user-reported).
                //
                // Giving the name group weight(1f, fill = false) lets it shrink
                // only as far as needed, and maxLines/ellipsis on the NAME means
                // the truncation lands on the element that can afford it. softWrap
                // = false on the date is the actual guarantee of a uniform row
                // height — the date is short, fixed-width, and the one thing that
                // must never reflow. Deliberately NOT solved by shortening the
                // format: the time is real information, and dropping it would only
                // move the threshold rather than remove it.
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (file.fileSize.isNotBlank()) {
                        Text(
                            file.fileSize,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    file.modifiedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (file.preview.isNotBlank()) {
                Text(
                    file.preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // [T-memory-file-delete-entrypoint] Daily logs are deletable per the
        // repository contract, but the row previously accepted onDelete and
        // never rendered it — the confirm dialog was unreachable, so users
        // could not delete memory files at all (iOS exposes this via
        // List .onDelete swipe). An always-visible trash button is the
        // Android-convention equivalent; GLOBAL.md passes onDelete = null
        // and keeps its chevron-only layout.
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    m3Icon(Icons.Outlined.Delete),
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Icon(
            m3Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Full-page memory file editor, matching iOS MemoryFileEditView.
 * Monospaced text. The Save button stays ALWAYS visible.
 *
 * [T-global-memory-save-always-visible] Previously the Save action was
 * gated on a `hasChanges` flag that only flipped true inside the field's
 * onValueChange. Programmatic content changes (paste, IME commit, state
 * restore) don't always route through onValueChange, so Save could fail
 * to appear after a paste until the user typed another key — the exact
 * symptom reported on iOS/macOS (XIN msg 41384). Keeping Save permanently
 * visible removes the dependency entirely; saveFile is idempotent so a
 * no-op Save on unchanged content is harmless.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryFileEditScreen(
    fileName: String,
    isGlobal: Boolean,
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
) {
    var content by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // [T-memory-save-toast-feedback] Confirm Save actually committed by
    // flashing a toast — previously the Save tap silently closed nothing,
    // showed no state change, and the user had no signal that the edit
    // landed (user-reported confusion). Reuses the existing
    // memory_save_toast string already wired for the per-chat memory
    // detail editor's SavedToast so the wording stays consistent.
    val savedToastText = stringResource(R.string.memory_save_toast)

    LaunchedEffect(fileName) {
        content = memoryRepository.readFile(fileName)
    }

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(m3Icon(Icons.AutoMirrored.Filled.ArrowBack), contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // [T-global-memory-save-always-visible] Always render Save —
                    // no hasChanges gate (see KDoc above).
                    MinisTextButton(onClick = {
                        try {
                            memoryRepository.saveFile(fileName, content)
                            saveError = null
                            android.widget.Toast.makeText(
                                context,
                                savedToastText,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } catch (e: Exception) {
                            saveError = e.message
                        }
                    }) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Editor
            DialogTextField(
                value = content,
                onValueChange = {
                    content = it
                },
                singleLine = false,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                placeholder = stringResource(R.string.memory_editor_placeholder),
                modifier = Modifier.weight(1f),
            )

            // Footer text
            if (isGlobal) {
                Text(
                    stringResource(R.string.memory_global_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (saveError != null) {
                Text(
                    saveError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * "App notes" section ([T-android-app-knowledge]): the agent's on-device app
 * experience library. Lives on the memory page because these notes ARE memory
 * the agent recalls when operating apps — not a settings toggle. Lists every
 * recorded note as "app label — text (x uses)", with a clear-all row guarded
 * by a confirmation dialog. Backed by the single AppNotes store.
 */
/** [T-memory-center-stats] One label/count pair in the overview block. */
@Composable
private fun MemoryStatRow(labelRes: Int, value: Int, showDivider: Boolean = true) {
    SettingsRow(
        title = stringResource(labelRes),
        onClick = null,
        showDivider = showDivider,
        trailing = {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun AppNotesSection() {
    val context = LocalContext.current
    val notes = remember { AppNotesStore.forContext(context).all }
        .collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    SettingsSection(
        header = stringResource(R.string.settings_section_app_notes),
        footer = stringResource(R.string.settings_app_notes_footer),
    ) {
        val items = notes.value
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_app_notes_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        } else {
            items.forEach { note ->
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = remember(note.pkg) { AppNotes.labelOf(context, note.pkg) },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${note.kind.name} · x${note.uses}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = note.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showClearDialog = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = stringResource(R.string.settings_app_notes_clear),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.settings_app_notes_clear_title)) },
            text = { Text(stringResource(R.string.settings_app_notes_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    AppNotesStore.forContext(context).clearAll()
                    showClearDialog = false
                }) { Text(stringResource(R.string.settings_app_notes_clear_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.settings_app_notes_cancel))
                }
            },
        )
    }
}
