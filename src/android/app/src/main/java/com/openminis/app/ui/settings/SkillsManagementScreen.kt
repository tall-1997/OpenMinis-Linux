package com.openminis.app.ui.settings
import com.openminis.app.ui.theme.m3Icon
import com.openminis.app.ui.theme.IosBlue
import com.openminis.app.ui.theme.IosGreen

import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.openminis.app.ui.components.DialogTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import com.openminis.app.ui.components.MinisCenterTopBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.markdown.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.util.IsoTime
import com.openminis.app.i18n.uppercaseForDisplay
/** Inline state for the update actions on [SkillDetailScreen] (T154); drives the status row. */
private sealed class UpdateStatus {
    data object Idle : UpdateStatus()
    data class InProgress(val label: String) : UpdateStatus()
    data object Done : UpdateStatus()
    data class Failed(val reason: String) : UpdateStatus()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsManagementScreen(
    skillRepository: SkillRepository,
    onBack: () -> Unit,
    onSkillClick: (String) -> Unit,
    onMinisSkillsClick: () -> Unit,
) {
    val skills by skillRepository.skills.collectAsStateWithLifecycle()
    var showImportSheet by remember { mutableStateOf(false) }
    var deleteSkillId by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showA11yRecord by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // [T-android-sessionlist-prefs-io] In-flight enabled-writes disable the switch (anti double-tap).
    var togglingSkillIds by remember { mutableStateOf(emptySet<String>()) }

    // Sort preference (SharedPreferences, default name ascending). [T-android-sessionlist-prefs-io] lazy handle; first touch on IO.
    val sortPrefs = remember {
        lazy {
            context.applicationContext.getSharedPreferences(
                "skills_prefs",
                android.content.Context.MODE_PRIVATE,
            )
        }
    }
    var sortByModified by remember { mutableStateOf(false) }
    var sortAscending by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val stored = withContext(Dispatchers.IO) {
            val prefs = sortPrefs.value
            prefs.getBoolean("sort_by_modified", false) to prefs.getBoolean("sort_ascending", true)
        }
        sortByModified = stored.first
        sortAscending = stored.second
    }

    val filteredSkills by remember(skills) {
        derivedStateOf {
            val q = searchQuery.trim().lowercase()
            val base = if (q.isEmpty()) skills
            else skills.filter {
                it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
            }
            val sorted = if (sortByModified) base.sortedBy { it.updatedAt }
            else base.sortedBy { it.name.lowercase() }
            if (sortAscending) sorted else sorted.reversed()
        }
    }

    // T-skillscan: rescan on entry so a skill installed in-chat (e.g. shell git clone) shows without restart.
    LaunchedEffect(Unit) {
        skillRepository.reloadFromDisk()
    }

    SettingsScaffold(
        title = stringResource(R.string.skill_title),
        onBack = null,
        // T75-part1: Add lives on the top-bar action slot.
        actions = {
            // Sort menu — same structure as the file browser's MoreMenu.
            Box {
                var sortMenuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { sortMenuExpanded = true }) {
                    Icon(
                        m3Icon(Icons.AutoMirrored.Filled.Sort),
                        contentDescription = stringResource(R.string.filebrowser_sort_by),
                    )
                }
                com.openminis.app.ui.components.MinisMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.filebrowser_sort_name)) },
                        leadingIcon = {
                            if (!sortByModified) Icon(m3Icon(Icons.Filled.Check), contentDescription = null)
                            else Spacer(modifier = Modifier.size(24.dp))
                        },
                        onClick = {
                            sortByModified = false
                            sortPrefs.value.edit().putBoolean("sort_by_modified", false).apply()
                            sortMenuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.filebrowser_sort_modified)) },
                        leadingIcon = {
                            if (sortByModified) Icon(m3Icon(Icons.Filled.Check), contentDescription = null)
                            else Spacer(modifier = Modifier.size(24.dp))
                        },
                        onClick = {
                            sortByModified = true
                            sortPrefs.value.edit().putBoolean("sort_by_modified", true).apply()
                            sortMenuExpanded = false
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(
                                if (sortAscending) R.string.filebrowser_sort_ascending
                                else R.string.filebrowser_sort_descending
                            ))
                        },
                        leadingIcon = {
                            Icon(
                                if (sortAscending) m3Icon(Icons.Filled.ArrowUpward) else m3Icon(Icons.Filled.ArrowDownward),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            sortAscending = !sortAscending
                            sortPrefs.value.edit().putBoolean("sort_ascending", sortAscending).apply()
                            sortMenuExpanded = false
                        },
                    )
                }
            }
            IconButton(onClick = { showAddMenu = true }) {
                Icon(m3Icon(Icons.Default.Add), contentDescription = stringResource(R.string.skill_add))
            }
        },
    ) {
        // Search bar filters by name + description.
        if (skills.isNotEmpty()) {
            DialogTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = stringResource(R.string.skills_search_placeholder),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp),
            )
        }

        SkillFeedsSection(skillRepository = skillRepository)

        SettingsSection(
            header = stringResource(R.string.skill_section_installed),
            footer = stringResource(R.string.skill_section_footer),
        ) {
            if (skills.isEmpty()) {
                // Centred empty-state inside the section card.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        m3Icon(Icons.Outlined.Description),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                    Text(
                        stringResource(R.string.skill_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.skill_empty_action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (filteredSkills.isEmpty()) {
                Text(
                    text = stringResource(R.string.skills_search_no_match, searchQuery),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                )
            } else {
                filteredSkills.forEachIndexed { index, skill ->
                    val (badgeIcon, badgeColor) = sourceIconAndColor(skill.importSource)
                    SettingsRow(
                        title = localizedSkillName(skill),
                        subtitle = skill.description.takeIf { it.isNotEmpty() }?.let { stripMarkdown(it) },
                        showChevron = true,
                        showDivider = index < filteredSkills.size - 1,
                        onClick = { onSkillClick(skill.id) },
                        trailing = {
                            // [T-android-sessionlist-prefs-io] setEnabled writes prefs + DB off-main; switch disabled while in flight.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    badgeIcon,
                                    contentDescription = null,
                                    tint = badgeColor,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                SettingsSwitch(
                                    checked = skill.isEnabled,
                                    enabled = skill.id !in togglingSkillIds,
                                    onCheckedChange = { on ->
                                        togglingSkillIds = togglingSkillIds + skill.id
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                skillRepository.setEnabled(skill.id, on)
                                            }
                                            togglingSkillIds = togglingSkillIds - skill.id
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showAddMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAddMenu = false },
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAddMenu = false
                            showImportSheet = true
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(m3Icon(Icons.Outlined.Description), contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.skill_import_modal_title), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAddMenu = false
                            onMinisSkillsClick()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(m3Icon(Icons.Outlined.Language), contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.skill_minis_skills_modal), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAddMenu = false
                            showA11yRecord = true
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(m3Icon(Icons.Outlined.FiberManualRecord), contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.a11y_record_title), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    if (showImportSheet) {
        SkillImportSheet(
            skillRepository = skillRepository,
            onDismiss = { showImportSheet = false },
        )
    }
    if (showA11yRecord) {
        A11yRecordDialog(
            skillRepository = skillRepository,
            onDismiss = { showA11yRecord = false },
        )
    }

    if (deleteSkillId != null) {
        val skill = skills.find { it.id == deleteSkillId }
        AlertDialog(
            onDismissRequest = { deleteSkillId = null },
            title = { Text(stringResource(R.string.delete_named, skill?.name ?: stringResource(R.string.skill_fallback))) },
            text = { Text(stringResource(R.string.skill_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    // [T-android-sessionlist-prefs-io] delete() rewrites the skill dir + DB row off-main.
                    val id = deleteSkillId
                    deleteSkillId = null
                    if (id != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) { skillRepository.delete(id) }
                        }
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteSkillId = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

// ─── Import Sheet (3 modes: URL / Paste / File) ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillImportSheet(
    skillRepository: SkillRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var urlText by remember { mutableStateOf("") }
    var pasteContent by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // File picker
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // [T-android-sessionlist-prefs-io] Picker callback runs on main; read/inflate/write moves to IO.
        isLoading = true
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    // [T-android-skill-import-zip] Read raw bytes and branch on file magic (readText() corrupted zip bundles to garbage UTF-8).
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) {
                        "Failed to read file"
                    } else {
                        // PK\x03\x04 local header, PK\x05\x06 EOCD, PK\x07\x08 spanned; magic-byte sniffing beats extension checks (URI may strip it).
                        val isZip = bytes.size >= 4 &&
                            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())
                        if (isZip) {
                            val result = skillRepository.importFromArchive(
                                java.io.ByteArrayInputStream(bytes),
                            )
                            if (result != null) null
                            else "Invalid skill archive — no SKILL.md found at the root or one directory deep"
                        } else {
                            val content = String(bytes, Charsets.UTF_8)
                            val result = skillRepository.importFromContent(
                                content,
                                SkillRepository.ImportSource.FILE,
                            )
                            if (result != null) null else "Invalid SKILL.md content"
                        }
                    }
                } catch (e: Exception) {
                    "Failed to read file: ${e.message}"
                }
            }
            isLoading = false
            if (failure == null) onDismiss() else errorText = failure
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.skill_import_modal_title), style = MaterialTheme.typography.titleMedium)

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0; errorText = null }) {
                    Text(stringResource(R.string.skill_import_tab_url), modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1; errorText = null }) {
                    Text(stringResource(R.string.skill_import_tab_paste), modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2; errorText = null }) {
                    Text(stringResource(R.string.skill_import_tab_file), modifier = Modifier.padding(12.dp))
                }
            }

            when (selectedTab) {
                0 -> {
                    Text(
                        stringResource(R.string.skill_import_url_instruction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    DialogTextField(
                        value = urlText,
                        onValueChange = { urlText = it; errorText = null },
                        placeholder = stringResource(R.string.skill_import_url_placeholder),
                        singleLine = true,
                        isError = errorText != null,
                    )
                }
                1 -> {
                    Text(
                        stringResource(R.string.skill_import_paste_instruction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    DialogTextField(
                        value = pasteContent,
                        onValueChange = { pasteContent = it; errorText = null },
                        modifier = Modifier.height(200.dp),
                        singleLine = false,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        isError = errorText != null,
                    )
                }
                2 -> {
                    Text(
                        stringResource(R.string.skill_import_file_instruction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MinisTextButton(
                        onClick = { fileLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.skill_import_file_button)) }
                }
            }

            if (errorText != null) {
                Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (selectedTab < 2) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    MinisTextButton(
                        onClick = {
                            when (selectedTab) {
                                0 -> {
                                    if (urlText.isBlank()) { errorText = context.getString(R.string.skill_import_error_no_url); return@MinisTextButton }
                                    isLoading = true
                                    scope.launch {
                                        try {
                                            // importFromGitHub recurses the Contents API so bundled scripts/references land on disk (old path only grabbed SKILL.md).
                                            val result = skillRepository.importFromGitHub(urlText.trim())
                                            if (result != null) onDismiss() else errorText = context.getString(R.string.skill_import_error_invalid)
                                        } catch (e: Exception) { errorText = "Error: ${e.message}" }
                                        finally { isLoading = false }
                                    }
                                }
                                1 -> {
                                    // [T-android-sessionlist-prefs-io] importFromContent parses + writes on IO; only state writes stay on main.
                                    isLoading = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            skillRepository.importFromContent(pasteContent)
                                        }
                                        isLoading = false
                                        if (result != null) onDismiss()
                                        else errorText = context.getString(R.string.skill_import_error_format)
                                    }
                                }
                            }
                        },
                        enabled = when (selectedTab) { 0 -> urlText.isNotBlank() && !isLoading; 1 -> pasteContent.isNotBlank() && !isLoading; else -> false },
                    ) { Text(if (isLoading) stringResource(R.string.skill_import_in_progress) else stringResource(R.string.skill_import_submit)) }
                }
            }
        }
    }
}

// ─── Skill Detail Sheet ─────────────────────────────────────────────────────

// ─── Skill Detail Screen (full page, matching iOS) ──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailScreen(
    skillId: String,
    skillRepository: SkillRepository,
    onBack: () -> Unit,
    onFileClick: (String, String) -> Unit = { _, _ -> },
) {
    val skills by skillRepository.skills.collectAsStateWithLifecycle()
    val skill = skills.find { it.id == skillId }
    var deleted by remember { mutableStateOf(false) }

    // [T-compose-nav-in-composition] Navigate in a LaunchedEffect (keyed on null-ness) rather than during composition.
    LaunchedEffect(skillId, deleted, skill == null) {
        if (skill == null && !deleted) onBack()
    }
    if (skill == null) return // Already deleted or not loaded yet — render nothing

    var showDeleteDialog by remember { mutableStateOf(false) }
    // T155: name editing in a modal dialog (avoids inline OutlinedTextField layout jiggle).
    var showEditNameDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(skill.name) }

    // T154: updateStatus surfaces inline feedback next to the buttons, clearing ~3 s after a terminal result.
    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Idle) }
    // [T-android-skill-export] Guards the share button while the zip builds off-main.
    var isExporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(updateStatus) {
        if (updateStatus is UpdateStatus.Done || updateStatus is UpdateStatus.Failed) {
            kotlinx.coroutines.delay(3000)
            updateStatus = UpdateStatus.Idle
        }
    }
    val fileUpdateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // [T-android-sessionlist-prefs-io] ActivityResult callback runs on main; read/parse/write moves to IO.
        updateStatus = UpdateStatus.InProgress(context.getString(R.string.skill_detail_status_reading))
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val content = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                    if (content.isNullOrBlank()) {
                        UpdateStatus.Failed(context.getString(R.string.skill_detail_error_empty))
                    } else {
                        // Re-parse SKILL.md to update name/description/body without a new entry.
                        val parsed = skillRepository.parseSkillMdPublic(content)
                        when {
                            parsed == null -> UpdateStatus.Failed(
                                context.getString(R.string.skill_detail_error_invalid),
                            )
                            skillRepository.update(
                                id = skill.id,
                                name = parsed.name,
                                description = parsed.description,
                                body = parsed.body,
                            ) -> UpdateStatus.Done
                            else -> UpdateStatus.Failed(
                                context.getString(R.string.skill_detail_error_update_failed),
                            )
                        }
                    }
                } catch (e: Exception) {
                    UpdateStatus.Failed("Failed to read file: ${e.message ?: "unknown"}")
                }
            }
            updateStatus = outcome
        }
    }

    val sourceLabel = when (skill.importSource) {
        SkillRepository.ImportSource.URL -> stringResource(R.string.skill_import_tab_url)
        SkillRepository.ImportSource.FILE -> stringResource(R.string.skill_detail_source_file)
        SkillRepository.ImportSource.BUNDLED -> stringResource(R.string.skill_detail_source_bundled)
        SkillRepository.ImportSource.SESSION -> stringResource(R.string.skill_detail_source_session)
    }

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = { Text(localizedSkillName(skill)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(m3Icon(Icons.AutoMirrored.Filled.ArrowBack), contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // [T-android-skill-export] Export the skill as a .zip to the share sheet (Android equivalent of iOS's share button).
                    IconButton(
                        enabled = !isExporting,
                        onClick = {
                            isExporting = true
                            scope.launch {
                                val zip = withContext(Dispatchers.IO) {
                                    skillRepository.exportSkillToZip(skill.id)
                                }
                                isExporting = false
                                if (zip == null) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.skill_export_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    shareSkillZip(context, zip)
                                }
                            }
                        },
                    ) {
                        Icon(
                            m3Icon(Icons.Default.IosShare),
                            contentDescription = stringResource(R.string.skill_export_share),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Meta Section ──
            DetailSection {
                // Name (tap pencil → edit dialog, T155)
                DetailRow {
                    Text(stringResource(R.string.skill_detail_label_name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                    Spacer(Modifier.weight(1f))
                    Text(skill.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        m3Icon(Icons.Default.Edit), contentDescription = "Edit name",
                        modifier = Modifier.size(14.dp).clickable {
                            editName = skill.name
                            showEditNameDialog = true
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                DetailDivider(insetEnd = 14.dp, alpha = 0.5f)

                // Version
                DetailRow {
                    Text(stringResource(R.string.skill_detail_label_version), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                    Spacer(Modifier.weight(1f))
                    Text(skill.version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DetailDivider(insetEnd = 14.dp, alpha = 0.5f)

                // Last Modified
                DetailRow {
                    Text(stringResource(R.string.skill_detail_label_modified), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                    Spacer(Modifier.weight(1f))
                    Text(relativeTime(skill.updatedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DetailDivider(insetEnd = 14.dp, alpha = 0.5f)

                // Source
                DetailRow {
                    Text(stringResource(R.string.skill_detail_label_source), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                    Spacer(Modifier.weight(1f))
                    Text(sourceLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── Update Actions Section ──
            // T154: every row drives `updateStatus` (updateFromURL / update / rescanFromDisk).
            val isBusy = updateStatus is UpdateStatus.InProgress
            DetailSection {
                if (skill.importSource == SkillRepository.ImportSource.URL) {
                    DetailRow(clickable = !isBusy, onClick = {
                        if (isBusy) return@DetailRow
                        updateStatus = UpdateStatus.InProgress(context.getString(R.string.skill_detail_status_downloading))
                        scope.launch {
                            updateStatus = when (val r = skillRepository.updateFromURL(skill.id)) {
                                is SkillRepository.UpdateResult.Success -> UpdateStatus.Done
                                // T152: Surface the cause of a partial update (e.g. GitHub 403) instead of a silent green check.
                                is SkillRepository.UpdateResult.PartialSuccess -> UpdateStatus.Failed("Updated, but: ${r.reason}")
                                is SkillRepository.UpdateResult.Failure -> UpdateStatus.Failed(r.reason)
                            }
                        }
                    }) {
                        SettingsActionIcon(m3Icon(Icons.Default.Refresh), SettingsIconBlue)
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.skill_detail_update_url), color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Text(relativeTime(skill.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DetailDivider(insetEnd = 14.dp, alpha = 0.5f)
                }

                DetailRow(clickable = !isBusy, onClick = {
                    if (isBusy) return@DetailRow
                    fileUpdateLauncher.launch("*/*")
                }) {
                    SettingsActionIcon(Icons.Outlined.Description, SettingsIconBlue)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.skill_detail_update_file), color = MaterialTheme.colorScheme.primary)
                }
                DetailDivider(insetEnd = 14.dp, alpha = 0.5f)

                DetailRow(clickable = !isBusy, onClick = {
                    if (isBusy) return@DetailRow
                    updateStatus = UpdateStatus.InProgress(context.getString(R.string.skill_detail_status_rescanning))
                    // [T-android-sessionlist-prefs-io] rescanFromDisk re-reads the skill tree off-main.
                    scope.launch {
                        val refreshed = withContext(Dispatchers.IO) {
                            skillRepository.rescanFromDisk(skill.id)
                        }
                        updateStatus = if (refreshed != null) UpdateStatus.Done
                            else UpdateStatus.Failed(context.getString(R.string.skill_detail_error_missing))
                    }
                }) {
                    SettingsActionIcon(m3Icon(Icons.Default.Refresh), SettingsIconGreen)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.skill_detail_rescan), color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                }

                // Inline status row (holds ~3 s on Done/Failed before clearing to Idle).
                when (val s = updateStatus) {
                    is UpdateStatus.InProgress -> {
                        DetailDivider(insetEnd = 14.dp, alpha = 0.5f)
                        DetailRow {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(s.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is UpdateStatus.Done -> {
                        DetailDivider(insetEnd = 14.dp, alpha = 0.5f)
                        DetailRow {
                            Icon(m3Icon(Icons.Default.CheckCircle), contentDescription = null, tint = IosGreen, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.skill_detail_updated), style = MaterialTheme.typography.bodySmall, color = IosGreen)
                        }
                    }
                    is UpdateStatus.Failed -> {
                        DetailDivider(insetEnd = 14.dp, alpha = 0.5f)
                        DetailRow {
                            Icon(m3Icon(Icons.Default.Error), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(s.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    UpdateStatus.Idle -> Unit
                }
            }

            // ── Description Section ──
            val previewLines = skill.body.lineSequence().filter { it.isNotBlank() }.take(5).toList()
            val hasMore = skill.body.lines().count { it.isNotBlank() } > 5
            val preview = previewLines.joinToString("\n") + if (hasMore) "\n…" else ""

            if (preview.isNotEmpty()) {
                DetailSection(header = stringResource(R.string.skills_section_description)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        MarkdownText(
                            markdown = preview,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // ── Files Section ──
            // Re-list when the skill record changes (rescan/update bumps updatedAt).
            val skillFiles = remember(skill.id, skill.updatedAt) {
                skillRepository.listSkillFiles(skill.id)
                    .ifEmpty { listOf("SKILL.md") }
            }
            DetailSection(header = stringResource(R.string.skills_section_files)) {
                skillFiles.forEachIndexed { index, relativePath ->
                    if (index > 0) DetailDivider(insetEnd = 14.dp, alpha = 0.5f)
                    DetailRow(clickable = true, onClick = { onFileClick(skill.id, relativePath) }) {
                        Icon(m3Icon(Icons.Outlined.Description), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            relativePath,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            m3Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // ── Delete ──
            Spacer(Modifier.height(16.dp))
            MinisButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.skill_detail_delete))
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_named, skill.name)) },
            text = { Text(stringResource(R.string.skill_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    deleted = true
                    showDeleteDialog = false
                    // [T-android-sessionlist-prefs-io] delete() removes the skill tree + DB row off-main; onBack deferred until it completes.
                    scope.launch {
                        withContext(Dispatchers.IO) { skillRepository.delete(skill.id) }
                        onBack()
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // T155: rename dialog; save disabled when the name is empty or unchanged.
    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.skill_detail_edit_name_title)) },
            text = {
                DialogTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                val trimmed = editName.trim()
                val canSave = trimmed.isNotEmpty() && trimmed != skill.name
                MinisTextButton(
                    onClick = {
                        showEditNameDialog = false
                        // [T-android-sessionlist-prefs-io] update() rewrites SKILL.md + DB off-main.
                        if (canSave) {
                            val id = skill.id
                            scope.launch {
                                withContext(Dispatchers.IO) { skillRepository.update(id, name = trimmed) }
                            }
                        }
                    },
                    enabled = canSave,
                ) { Text(stringResource(R.string.skill_file_save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showEditNameDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

// [T-detail-rows-unify] DetailSection/Row/Divider moved to shared DetailRows.kt.

// [T-android-skill-export] Share an exported zip via FileProvider. Not deleted here (consumers read the URI async); SkillRepository sweeps >24h exports.
private fun shareSkillZip(context: android.content.Context, zip: java.io.File) {
    // [T-share-fileprovider-unify] Shared FileProvider helper (clip + double grant hardening).
    if (!shareViaFileProvider(
            context, zip, "application/zip", context.getString(R.string.skill_export_share),
        )
    ) {
        Toast.makeText(context, context.getString(R.string.skill_export_failed), Toast.LENGTH_SHORT).show()
    }
}

/** [T-android-skill-icon-circular] iOS-system-palette values (blue = remote/nav, green = local fs). */
private val SettingsIconBlue = IosBlue
private val SettingsIconGreen = IosGreen

// Strip Markdown syntax for compact UI surfaces; output trimmed and capped at 120 chars.
private fun stripMarkdown(text: String): String {
    return text
        .replace(Regex("```[\\s\\S]*?```"), "")           // fenced code blocks
        .replace(Regex("`([^`]+)`"), "$1")                  // inline code
        .replace(Regex("!?\\[([^\\]]+)\\]\\([^)]+\\)"), "$1") // [text](url) → text
        .replace(Regex("^#+ ", RegexOption.MULTILINE), "") // headings
        .replace(Regex("^> ", RegexOption.MULTILINE), "")  // blockquote markers
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")       // **bold**
        .replace(Regex("__([^_]+)__"), "$1")                // __bold__
        .replace(Regex("\\*([^*]+)\\*"), "$1")             // *italic*
        .replace(Regex("_([^_]+)_"), "$1")                  // _italic_
        .replace(Regex("\\s+"), " ")                       // collapse whitespace + newlines
        .trim()
        .take(120)
}

/** Format millis as a localized relative time string (DateUtils.getRelativeTimeSpanString). */
private fun relativeTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val resolution = if (diff < 60_000) {
        android.text.format.DateUtils.SECOND_IN_MILLIS
    } else {
        android.text.format.DateUtils.MINUTE_IN_MILLIS
    }
    if (diff >= 30 * 86_400_000L) {
        // Past a month falls back to the absolute date, as before.
        return IsoTime.formatLocalDate(millis)
    }
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        millis, now, resolution,
    ).toString()
}

// ─── Skill File Viewer Screen (full page, matching iOS) ─────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillFileViewerScreen(
    skillId: String,
    relativePath: String = "SKILL.md",
    skillRepository: SkillRepository,
    onBack: () -> Unit,
) {
    val skills by skillRepository.skills.collectAsStateWithLifecycle()
    val skill = skills.find { it.id == skillId }

    // [T-compose-nav-in-composition] Navigate out of composition (keyed on null-ness).
    LaunchedEffect(skillId, skill == null) {
        if (skill == null) onBack()
    }
    if (skill == null) return

    val isSkillMd = relativePath.equals("SKILL.md", ignoreCase = true)
    val fileName = relativePath.substringAfterLast('/')

    // SKILL.md reconstructs from the in-memory record; sibling files read straight from disk.
    val initialContent = remember(skillId, relativePath, skill.updatedAt) {
        if (isSkillMd) {
            buildString {
                appendLine("---")
                appendLine("name: ${skill.name}")
                appendLine("version: ${skill.version}")
                if (skill.description.isNotEmpty()) {
                    appendLine("description: ${skill.description}")
                }
                appendLine("---")
                appendLine()
                append(skill.body)
            }
        } else {
            skillRepository.readSkillFile(skillId, relativePath) ?: ""
        }
    }

    var isEditing by remember { mutableStateOf(false) }
    var editContent by remember(skillId, relativePath) { mutableStateOf(initialContent) }
    val scope = rememberCoroutineScope()

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
                    if (isEditing) {
                        MinisTextButton(onClick = {
                            isEditing = false
                            // [T-android-sessionlist-prefs-io] importFromContent/writeSkillFile run off-main; onBack waits for the save.
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    if (isSkillMd) {
                                        // SKILL.md edits go through importFromContent so frontmatter flows into DB metadata.
                                        skillRepository.importFromContent(editContent, skill.importSource)
                                    } else {
                                        skillRepository.writeSkillFile(skillId, relativePath, editContent)
                                    }
                                }
                                onBack()
                            }
                        }) { Text(stringResource(R.string.skill_file_save)) }
                    } else {
                        MinisTextButton(onClick = {
                            editContent = initialContent
                            isEditing = true
                        }) { Text(stringResource(R.string.skill_file_edit)) }
                    }
                },
            )
        },
    ) { padding ->
        if (isEditing) {
            DialogTextField(
                value = editContent,
                onValueChange = { editContent = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 8.dp),
                singleLine = false,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                item {
                    Text(
                        initialContent,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
