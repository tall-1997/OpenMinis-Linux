package com.openminis.app.ui.settings

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.PersonaImportConflictKind
import com.openminis.app.agent.PersonaImportError
import com.openminis.app.agent.PersonaImportPreview
import com.openminis.app.agent.PersonaImportResult
import com.openminis.app.agent.PersonaPromptEntry
import com.openminis.app.agent.PersonaPromptLibrary
import com.openminis.app.agent.PersonaPromptLogic
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.safeLaunchPicker
import com.openminis.app.agent.SoulIcon
import com.openminis.app.agent.SoulFile
import com.openminis.app.agent.SoulMDParser
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext

/**
 * [T-soul-md] Settings page for editing SOUL.md. Mirrors iOS
 * `SoulSettingsView` (commit 6370d5a):
 *   - Header preview card showing `✨ [name]` + `[style]` (emoji is
 *     locked to ✨; the editable emoji field was removed to keep
 *     identity surface consistent across the app)
 *   - Identity fields: name, style, lang (Auto / Chinese / English)
 *   - Personality prompt (multiline) with soft-warning + hard-truncate
 *     length indicators (>2000 chars = yellow, >4000 = red)
 *   - "Restore Default" with confirmation dialog
 *   - "Save" writes to SOUL.md via [SoulStore.save] and refreshes the
 *     in-memory metadata cache so chat bubble headers update without a
 *     restart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoulSettingsScreen(
    onBack: () -> Unit,
    providerRepository: com.openminis.app.data.repository.ProviderRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(SoulMetadata.DEFAULT.name) }
    var style by remember { mutableStateOf(SoulMetadata.DEFAULT.style) }
    var lang by remember { mutableStateOf(SoulMetadata.DEFAULT.lang) }
    var body by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // Preserve the raw `emoji` field exactly as it appears in SOUL.md so
    // Save round-trips without clobbering a value the user may have set
    // on another device (or an older build). Not surfaced in the UI —
    // the identity emoji shown to the user is locked to ✨ everywhere
    // (see [SoulMetadata.displayEmoji]).
    var preservedEmoji by remember { mutableStateOf(SoulMetadata.DEFAULT.emoji) }
    // [T-android-soul-custom-icon] The user-settable identity icon: an emoji
    // literal, a data:image/png;base64 URI, or empty for the default sparkle.
    var icon by remember { mutableStateOf(SoulMetadata.DEFAULT.icon) }
    var showIconMenu by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }
    var iconError by remember { mutableStateOf<String?>(null) }

    // [persona-import] Persona prompt library state: which prompt file is
    // being edited, an in-flight import (preview awaiting conflict verdict),
    // and the entry pending delete confirmation.
    var editingPromptId by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<PersonaImportPreview.Ready?>(null) }
    var pendingDelete by remember { mutableStateOf<PersonaPromptEntry?>(null) }
    // [persona-unlimited] Restore Default defers the body write to Save so a
    // discarded editor never clobbers the persona file on disk.
    var pendingRestoreBody by remember { mutableStateOf<String?>(null) }
    val promptRevision by PersonaPromptLibrary.revision.collectAsState()
    val providerConfig by providerRepository.config.collectAsState()
    var promptIndex by remember { mutableStateOf(PersonaPromptLogic.emptyIndex()) }
    LaunchedEffect(promptRevision) {
        promptIndex = withContext(Dispatchers.IO) {
            PersonaPromptLibrary.loadIndex(context)
        }
    }

    // [T-android-soul-save-in-appbar] What was loaded from disk, kept so
    // "has the user changed anything" is a comparison rather than a flag.
    //
    // A boolean set by every onValueChange would report dirty after a round
    // trip that lands back on the original text (type a character, delete it),
    // and would need a reset at each of the several places state is
    // reassigned — the load, the restore-default, and the save itself. A
    // snapshot compared by value cannot drift out of sync with the fields.
    var baseline by remember { mutableStateOf<SoulFile?>(null) }

    // [T-android-soul-custom-icon] Image import: decode → reject opaque →
    // centre-crop → 96px → PNG → base64 data URI. Runs off the main thread
    // because a full-resolution camera photo is expensive to decode and scan.
    val iconUnreadableMsg = stringResource(R.string.soul_icon_error_unreadable)
    val iconTooLargeMsg = stringResource(R.string.soul_icon_error_too_large)
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val bmp = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
                if (bmp == null) {
                    SoulIcon.EncodeResult.Failure(SoulIcon.Rejection.UNREADABLE)
                } else {
                    SoulIcon.encode(bmp)
                }
            }
            when (result) {
                is SoulIcon.EncodeResult.Success -> icon = result.dataUri
                is SoulIcon.EncodeResult.Failure -> iconError = when (result.reason) {
                    SoulIcon.Rejection.TOO_LARGE -> iconTooLargeMsg
                    SoulIcon.Rejection.UNREADABLE -> iconUnreadableMsg
                }
            }
        }
    }

    // Initial load + (defensive) ensureExists. The Application-level call
    // already seeded on first run, but loading from a freshly cleared app
    // shouldn't crash.
    LaunchedEffect(Unit) {
        val parsed = withContext(Dispatchers.IO) {
            SoulStore.ensureExists(context)
            SoulStore.load(context) ?: SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
        }
        name = parsed.metadata.name
        preservedEmoji = parsed.metadata.emoji
        icon = parsed.metadata.icon
        style = parsed.metadata.style
        lang = parsed.metadata.lang
        body = parsed.body
        // [T-android-soul-save-in-appbar] Snapshot what disk holds, so the
        // dirty check starts from the loaded state rather than from the
        // pre-load defaults (which would read as "changed" immediately).
        //
        // Rebuilt through the same normalisation Save applies (ifBlank
        // fallbacks) rather than stored raw: a file whose `lang` is empty on
        // disk would otherwise compare unequal to the field state the instant
        // it loaded, and the screen would open already dirty.
        baseline = SoulFile(
            metadata = SoulMetadata(
                name = parsed.metadata.name.ifBlank { SoulMetadata.DEFAULT.name },
                emoji = parsed.metadata.emoji.ifBlank { SoulMetadata.DEFAULT.emoji },
                icon = parsed.metadata.icon,
                style = parsed.metadata.style,
                lang = parsed.metadata.lang.ifBlank { SoulMetadata.DEFAULT.lang },
            ),
            body = parsed.body,
        )
        loaded = true
    }

    // [persona-import] File import flow: SAF picker → preview (type / size /
    // conflict check) → either commit straight away or ask the user about a
    // name conflict. Commit runs on IO; the revision flow refreshes the
    // dropdowns via promptRevision above.
    val importUnsupportedMsg = stringResource(R.string.soul_prompt_import_bad_type)
    val importEmptyMsg = stringResource(R.string.soul_prompt_import_empty)
    val importTooLargeMsg = stringResource(R.string.soul_prompt_import_too_large)
    val importUnreadableMsg = stringResource(R.string.soul_prompt_import_unreadable)
    suspend fun commitPromptImport(
        ready: PersonaImportPreview.Ready,
        overwrite: Boolean,
    ) {
        val result = withContext(Dispatchers.IO) {
            PersonaPromptLibrary.commitPrepared(
                context,
                ready.fileName,
                ready.body,
                overwrite = overwrite && ready.conflict.canOverwrite,
            )
        }
        when (result) {
            is PersonaImportResult.Success -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.soul_prompt_import_ok, result.entry.fileName),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            is PersonaImportResult.Failure -> importError = when (result.reason) {
                PersonaImportError.BAD_TYPE -> importUnsupportedMsg
                PersonaImportError.EMPTY -> importEmptyMsg
                PersonaImportError.TOO_LARGE -> importTooLargeMsg
                PersonaImportError.UNREADABLE -> importUnreadableMsg
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val preview = withContext(Dispatchers.IO) {
                PersonaPromptLibrary.previewImport(context, uri)
            }
            when (preview) {
                is PersonaImportPreview.Failure -> importError = when (preview.reason) {
                    PersonaImportError.BAD_TYPE -> importUnsupportedMsg
                    PersonaImportError.EMPTY -> importEmptyMsg
                    PersonaImportError.TOO_LARGE -> importTooLargeMsg
                    PersonaImportError.UNREADABLE -> importUnreadableMsg
                }
                is PersonaImportPreview.Ready -> {
                    if (preview.conflict.kind == PersonaImportConflictKind.NONE) {
                        commitPromptImport(preview, overwrite = false)
                    } else {
                        pendingImport = preview
                    }
                }
            }
        }
    }

    // [persona-import] This screen edits identity only (name / style / lang /
    // icon). The persona body lives in the prompt library — edited per file in
    // SoulPromptEditorScreen — so Save preserves whatever body is on disk
    // (or the pending restore-default body) instead of writing a stale copy.
    val currentFile = SoulFile(
        metadata = SoulMetadata(
            name = name.ifBlank { SoulMetadata.DEFAULT.name },
            emoji = preservedEmoji.ifBlank { SoulMetadata.DEFAULT.emoji },
            icon = icon,
            style = style,
            lang = lang.ifBlank { SoulMetadata.DEFAULT.lang },
        ),
        body = pendingRestoreBody ?: body,
    )
    val isDirty = loaded && baseline != null && (
        currentFile.metadata != baseline?.metadata || pendingRestoreBody != null
    )
    var showDiscardDialog by remember { mutableStateOf(false) }

    val save: () -> Unit = {
        scope.launch {
            try {
                val snapshot = currentFile
                withContext(Dispatchers.IO) {
                    val diskBody = if (snapshot.body == body && pendingRestoreBody == null) {
                        // Identity-only change: keep whatever body the file
                        // holds right now (a prompt editor may have written it
                        // since this screen loaded).
                        SoulStore.load(context)?.body.orEmpty()
                    } else {
                        snapshot.body
                    }
                    SoulStore.save(context, snapshot.copy(body = diskBody))
                }
                onBack()
            } catch (t: Throwable) {
                saveError = t.message ?: "save failed"
            }
        }
        Unit
    }

    // [T-android-soul-save-in-appbar] Leaving with unsaved edits asks first.
    // Routed through one lambda so the app bar's back arrow and the system
    // back gesture cannot disagree about whether the guard applies.
    val attemptBack: () -> Unit = {
        if (isDirty) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = isDirty) { showDiscardDialog = true }

    // [persona-import] A prompt file is open in the full-screen editor: it
    // owns the back stack until the user closes it.
    if (editingPromptId != null) {
        SoulPromptEditorScreen(
            promptId = editingPromptId!!,
            onBack = { editingPromptId = null },
        )
        return
    }

    SettingsScaffold(
        title = stringResource(R.string.soul_settings_title),
        onBack = attemptBack,
        actions = {
            // Save lives in the app bar, where a top-level commit action
            // belongs and where it stays reachable without scrolling the
            // (long) prompt editor to the bottom.
            MinisTextButton(
                onClick = save,
                enabled = loaded && isDirty,
            ) { Text(stringResource(R.string.soul_save)) }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.soul_section_preview),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // [T-android-soul-custom-icon] A bare glyph does not read as
                // tappable — reported on iOS review — so the icon sits on a
                // filled circle with a hairline border and a pencil badge.
                // The circle doubles as the backdrop for transparent PNGs,
                // which by definition have no background of their own.
                Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            )
                            .clickable { showIconMenu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        SoulIconGlyph(icon = icon, sizeDp = 30.dp, emojiSp = 24.sp)
                        // Badge kept INSIDE the circle's bounds: on iOS an
                        // overhanging badge was clipped to a sliver by the
                        // enclosing button label.
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(2.dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(2.5.dp),
                        )
                    }
                    SoulIconMenu(
                        expanded = showIconMenu,
                        hasIcon = icon.isNotEmpty(),
                        onDismiss = { showIconMenu = false },
                        onChooseEmoji = { showIconMenu = false; showEmojiSheet = true },
                        onChooseImage = {
                            showIconMenu = false
                            imagePicker.safeLaunchPicker(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                                context,
                                "SoulSettings",
                            )
                        },
                        onUseDefault = { showIconMenu = false; icon = "" },
                    )
                }
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        text = name.ifBlank { "Minis Ultra" },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (style.isNotBlank()) {
                        Text(
                            text = style,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.soul_section_identity),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.soul_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // Emoji field intentionally removed — identity emoji is
                // locked to ✨; only name / style / lang are editable.
                OutlinedTextField(
                    value = style,
                    onValueChange = { style = it },
                    label = { Text(stringResource(R.string.soul_field_style)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LangPicker(lang = lang, onLangChange = { lang = it })
            }
        }

        // [persona-import] The personality body is managed as a library of
        // persona files: the builtin SOUL.md plus anything the user imports.
        // Body editing happens in SoulPromptEditorScreen, opened from the
        // file row below.
        val selectedPrompt = promptIndex.prompts.find { it.id == promptIndex.selectedId }
            ?: PersonaPromptLogic.builtinEntry()
        SettingsSection(
            header = stringResource(R.string.soul_section_personality),
            footer = stringResource(R.string.soul_personality_footer),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SoulPromptFileRow(
                    fileName = selectedPrompt.fileName,
                    onOpen = { editingPromptId = selectedPrompt.id },
                    onImport = {
                        importLauncher.launch(
                            arrayOf(
                                "text/plain",
                                "text/markdown",
                                "text/x-markdown",
                                "*/*",
                            ),
                        )
                    },
                )
                SoulPromptDropdown(
                    label = stringResource(R.string.soul_prompt_select),
                    prompts = promptIndex.prompts,
                    selectedId = selectedPrompt.id,
                    onSelect = { id ->
                        scope.launch(Dispatchers.IO) {
                            PersonaPromptLibrary.setSelected(context, id)
                        }
                    },
                    onDelete = { pendingDelete = it },
                )
            }
        }

        // [persona-import] Per-provider persona: an instance can pin its own
        // persona file, or follow the global selection.
        val instances = providerConfig.instances
        if (instances.isNotEmpty()) {
            SettingsSection(
                header = stringResource(R.string.soul_section_provider_prompts),
                footer = stringResource(R.string.soul_provider_prompts_footer),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    instances.forEachIndexed { index, instance ->
                        val mapped = promptIndex.providerSelections[instance.id]
                        val label = instance.label.ifBlank { instance.providerType.displayName }
                        SoulPromptDropdown(
                            label = label,
                            prompts = promptIndex.prompts,
                            selectedId = mapped ?: PersonaPromptLogic.FOLLOW_DEFAULT,
                            onSelect = { id ->
                                scope.launch(Dispatchers.IO) {
                                    PersonaPromptLibrary.setProviderPrompt(
                                        context,
                                        instance.id,
                                        id.takeIf { it.isNotBlank() },
                                    )
                                }
                            },
                            onDelete = { pendingDelete = it },
                            followDefaultLabel = stringResource(
                                R.string.soul_prompt_follow_default,
                                selectedPrompt.fileName,
                            ),
                            leadingGlyph = true,
                            showDivider = index < instances.lastIndex,
                        )
                    }
                }
            }
        }

        SettingsSection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MinisOutlinedButton(
                    onClick = { showRestoreDialog = true },
                    // [T-android-soul-save-in-appbar] Full width now that Save
                    // has moved to the app bar and this is the only button left
                    // in the row.
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.soul_restore_default)) }
            }
        }
    }

    // [T-android-soul-save-in-appbar] Unsaved-changes guard.
    //
    // Each button leads with the verdict and then names the consequence
    // ("OK, discard" / "Cancel, keep editing"), so the row scans correctly
    // whether the user reads the leading word or the trailing one — a bare
    // "Discard"/"Keep editing" pair reads fine but gives no cue about which
    // side is the safe one.
    //
    // Dismissing the dialog by tapping outside keeps the edits — the
    // conservative reading of an ambiguous gesture.
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
                MinisButton(onClick = {
                    val parsed = SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
                    name = parsed.metadata.name
                    preservedEmoji = parsed.metadata.emoji
                    icon = parsed.metadata.icon
                    style = parsed.metadata.style
                    lang = parsed.metadata.lang
                    // [persona-unlimited] The body write is deferred to Save
                    // (pendingRestoreBody) so backing out of the screen never
                    // rewrites the persona file the user may have imported.
                    pendingRestoreBody = parsed.body
                    showRestoreDialog = false
                }) { Text(stringResource(R.string.soul_restore_default)) }
            },
            dismissButton = {
                MinisOutlinedButton(onClick = { showRestoreDialog = false }) {
                    Text(stringResource(R.string.soul_cancel))
                }
            },
        )
    }

    // [persona-import] Import failures explain themselves in one dialog.
    importError?.let { err ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(stringResource(R.string.soul_prompt_import_error_title)) },
            text = { Text(err) },
            confirmButton = {
                MinisTextButton(onClick = { importError = null }) {
                    Text(stringResource(R.string.soul_ok))
                }
            },
        )
    }

    // [persona-import] Name conflict: same-name different-content asks for an
    // overwrite verdict; identical content only offers "close".
    pendingImport?.let { ready ->
        val sameContent = ready.conflict.kind == PersonaImportConflictKind.SAME_CONTENT
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            text = {
                Text(
                    stringResource(
                        if (sameContent) {
                            R.string.soul_prompt_import_same_content
                        } else {
                            R.string.soul_prompt_import_same_name
                        },
                        ready.conflict.existingName,
                    ),
                )
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    val snapshot = ready
                    pendingImport = null
                    scope.launch {
                        commitPromptImport(
                            snapshot,
                            overwrite = !sameContent && snapshot.conflict.canOverwrite,
                        )
                    }
                }) {
                    Text(
                        stringResource(
                            if (sameContent) R.string.soul_prompt_import_confirm
                            else R.string.soul_prompt_overwrite_confirm,
                        ),
                    )
                }
            },
            dismissButton = {
                MinisTextButton(onClick = {
                    val snapshot = ready
                    pendingImport = null
                    if (!sameContent) {
                        scope.launch { commitPromptImport(snapshot, overwrite = false) }
                    }
                }) {
                    Text(
                        stringResource(
                            if (sameContent) R.string.soul_cancel
                            else R.string.soul_prompt_import_suffix,
                        ),
                    )
                }
            },
        )
    }

    // [persona-import] Delete confirmation for imported persona files.
    pendingDelete?.takeIf { !it.builtin }?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.soul_prompt_delete_title)) },
            text = { Text(stringResource(R.string.soul_prompt_delete_body, entry.fileName)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    val id = entry.id
                    pendingDelete = null
                    scope.launch(Dispatchers.IO) {
                        PersonaPromptLibrary.deleteImported(context, id)
                    }
                }) { Text(stringResource(R.string.soul_prompt_delete)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.soul_cancel))
                }
            },
        )
    }

    saveError?.let { err ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text(stringResource(R.string.soul_save_error_title)) },
            text = { Text(err) },
            confirmButton = {
                MinisButton(onClick = { saveError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }

    // [T-android-soul-custom-icon] Image rejection is explained after the
    // fact here. There is no longer a transparency requirement to state up
    // front — any image is accepted (see soul_icon_image_hint) — so the only
    // remaining rejections are "couldn't be read" and "too large", neither of
    // which can be predicted before the user picks a file.
    iconError?.let { err ->
        AlertDialog(
            onDismissRequest = { iconError = null },
            title = { Text(stringResource(R.string.soul_icon_error_title)) },
            text = { Text(err) },
            confirmButton = {
                MinisButton(onClick = { iconError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }

    if (showEmojiSheet) {
        SoulEmojiPickerSheet(
            current = if (SoulIcon.isDataUri(icon)) "" else icon,
            onDismiss = { showEmojiSheet = false },
            onPick = { chosen -> icon = chosen; showEmojiSheet = false },
        )
    }
}

/**
 * [T-android-soul-custom-icon] The icon itself, rendered the same way
 * everywhere it appears: a decoded bitmap, an emoji, or the default sparkle.
 *
 * The bitmap decode is `remember`ed on the icon string. Without that, the
 * chat header would re-decode base64 on every recomposition — and headers
 * recompose on every streaming tick.
 */
@Composable
internal fun SoulIconGlyph(
    icon: String,
    sizeDp: Dp,
    emojiSp: TextUnit,
    sparkleTint: Brush? = null,
) {
    val bitmap = remember(icon) { SoulIcon.decode(icon) }
    when {
        // Rounded rectangle, NOT a circle: at 18dp in the chat header a circle
        // eats the corners of a small avatar. Clipping here is also what makes
        // an opaque image acceptable at all — the refusal that used to guard
        // against "opaque rectangle reads as a broken tile" was a presentation
        // concern, and this is the presentation fix.
        bitmap != null -> Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .size(sizeDp)
                .clip(RoundedCornerShape(sizeDp * SoulIcon.CORNER_RADIUS_FRACTION)),
        )
        icon.isNotEmpty() -> Text(text = icon, fontSize = emojiSp)
        // Unset: byte-for-byte the previous sparkle, so a user who never
        // touches this sees no visual change at all.
        sparkleTint != null -> Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(sizeDp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = sparkleTint, blendMode = BlendMode.SrcIn)
                },
        )
        else -> Text(text = SoulMetadata.DISPLAY_EMOJI, fontSize = emojiSp)
    }
}

@Composable
private fun SoulIconMenu(
    expanded: Boolean,
    hasIcon: Boolean,
    onDismiss: () -> Unit,
    onChooseEmoji: () -> Unit,
    onChooseImage: () -> Unit,
    onUseDefault: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.soul_icon_choose_emoji)) },
            onClick = onChooseEmoji,
        )
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.soul_icon_choose_image))
                    // Stated up front so the square crop isn't a surprise after
                    // picking. (There is no alpha requirement: opaque images are
                    // accepted and the rounded clip is what makes them look right.)
                    Text(
                        text = stringResource(R.string.soul_icon_image_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = onChooseImage,
        )
        // Only offered when there is something to clear.
        if (hasIcon) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.soul_icon_use_default)) },
                onClick = onUseDefault,
            )
        }
    }
}

/**
 * Two rows of suggestions, tap-to-fill, a live preview at render size, and a
 * free-form field that normalizes per keystroke.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoulEmojiPickerSheet(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.soul_icon_emoji_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            // Preview at the size the icon is actually drawn on the card.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = draft.ifEmpty { SoulMetadata.DISPLAY_EMOJI },
                    fontSize = 30.sp,
                )
            }
            Spacer(Modifier.height(20.dp))

            SoulIcon.SUGGESTED_EMOJI.chunked(8).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    row.forEach { e ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (e == draft) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent,
                                )
                                .clickable { draft = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(text = e, fontSize = 22.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft,
                // Normalized PER KEYSTROKE rather than validated on submit:
                // a second emoji replaces the first, and non-emoji input is
                // dropped silently instead of being accepted then rejected.
                onValueChange = { draft = SoulIcon.normalizeEmojiInput(it) },
                label = { Text(stringResource(R.string.soul_icon_emoji_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MinisOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.soul_cancel))
                }
                MinisButton(
                    onClick = { onPick(draft) },
                    enabled = draft.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.soul_icon_set)) }
            }
        }
    }
}

/// Render the within-budget counter — picks the CJK character unit vs "words" depending on
/// the same CJK ratio rule that decides which cap applies. Standalone
/// helper so it stays out of the Composable hot-path's expression budget.
@Composable
internal fun soulBodyCountTextAndroid(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return stringResource(R.string.soul_count_zero)
    var cjk = 0
    var total = 0
    var i = 0
    while (i < trimmed.length) {
        val cp = trimmed.codePointAt(i)
        total += 1
        val isCJK =
            cp in 0x4E00..0x9FFF ||
                cp in 0x3400..0x4DBF ||
                cp in 0x3040..0x309F ||
                cp in 0x30A0..0x30FF ||
                cp in 0xAC00..0xD7AF
        if (isCJK) cjk += 1
        i += Character.charCount(cp)
    }
    val ratio = if (total > 0) cjk.toDouble() / total else 0.0
    // [persona-unlimited] Pure count display — no cap referenced.
    return if (ratio > SoulStore.CJK_RATIO_THRESHOLD) {
        val chars = trimmed.codePointCount(0, trimmed.length)
        stringResource(R.string.soul_count_plain_chars, chars)
    } else {
        val words = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
        stringResource(R.string.soul_count_plain_words, words)
    }
}

/** [T-soul-lang-button-wrap] Side padding of the language buttons. */
internal val LANG_BUTTON_SIDE_PADDING = 8.dp

@Composable
private fun LangButtonLabel(label: String) {
    Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LangPicker(lang: String, onLangChange: (String) -> Unit) {
    val options = listOf(
        "auto" to stringResource(R.string.soul_lang_auto),
        "zh" to stringResource(R.string.soul_lang_zh),
        "en" to stringResource(R.string.soul_lang_en),
    )
    val current = options.firstOrNull { it.first == lang } ?: options.first()
    var expanded by remember { mutableStateOf(false) }
    // Render the picker as a simple labeled row of buttons. Three options
    // (auto / Chinese / English) fit comfortably without a dropdown — avoids
    // depending on material3 ExposedDropdownMenu, which has a fragile
    // alignment story across compose versions.
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.soul_field_lang),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        // [T-soul-lang-button-wrap] Three equal-width buttons leave little room
        // once the default 24dp side padding is taken: on narrow phones
        // "English" broke mid-word ("Englis" / "h"). Keep labels on one line
        // and use a tighter side padding.
        val padding = PaddingValues(horizontal = LANG_BUTTON_SIDE_PADDING, vertical = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, label) ->
                if (key == current.first) {
                    MinisButton(
                        onClick = { onLangChange(key) },
                        modifier = Modifier.weight(1f),
                        contentPadding = padding,
                    ) { LangButtonLabel(label) }
                } else {
                    MinisOutlinedButton(
                        onClick = { onLangChange(key) },
                        modifier = Modifier.weight(1f),
                        contentPadding = padding,
                    ) { LangButtonLabel(label) }
                }
            }
        }
    }
    expanded // suppress unused-var warning
}

// -- [persona-import] Persona prompt library UI ----------------------------

@Composable
private fun SoulPromptFileRow(
    fileName: String,
    onOpen: () -> Unit,
    onImport: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.soul_prompt_tap_to_edit),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val importCd = stringResource(R.string.soul_prompt_import)
        IconButton(
            onClick = onImport,
            modifier = Modifier.semantics { contentDescription = importCd },
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = muted.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoulPromptDropdown(
    label: String,
    prompts: List<PersonaPromptEntry>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDelete: ((PersonaPromptEntry) -> Unit)? = null,
    followDefaultLabel: String? = null,
    leadingGlyph: Boolean = false,
    showDivider: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = when {
        selectedId.isBlank() || selectedId == PersonaPromptLogic.FOLLOW_DEFAULT ->
            followDefaultLabel ?: prompts.firstOrNull()?.fileName.orEmpty()
        else -> prompts.find { it.id == selectedId }?.fileName
            ?: followDefaultLabel
            ?: prompts.firstOrNull()?.fileName.orEmpty()
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingGlyph) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = selectedName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(label) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    singleLine = true,
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    if (followDefaultLabel != null) {
                        DropdownMenuItem(
                            text = { Text(followDefaultLabel) },
                            onClick = {
                                onSelect(PersonaPromptLogic.FOLLOW_DEFAULT)
                                expanded = false
                            },
                        )
                    }
                    prompts.forEach { prompt ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    prompt.fileName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                onSelect(prompt.id)
                                expanded = false
                            },
                            trailingIcon = if (!prompt.builtin && onDelete != null) {
                                {
                                    IconButton(
                                        onClick = {
                                            expanded = false
                                            onDelete(prompt)
                                        },
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.soul_prompt_delete),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}
