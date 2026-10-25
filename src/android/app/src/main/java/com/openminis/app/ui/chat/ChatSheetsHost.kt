package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.browser.BrowserSheet
import com.openminis.app.ui.components.MinisAlertDialog

/**
 * Conditional overlay host for ChatScreen: every bottom sheet and confirmation
 * dialog, extracted verbatim (pure move). Sheets keyed on ViewModel flows are
 * re-collected here; ChatScreen-local show/hide flags arrive as MutableState.
 * All of these render as Dialog / ModalBottomSheet windows, so hosting them
 * after the Scaffold is behaviorally identical to their previous positions.
 */
@Composable
internal fun ChatSheetsHost(
    viewModel: ChatViewModel,
    sessionId: String,
    inputText: String,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository?,
    skillRepository: SkillRepository?,
    mcpRepository: MCPRepository?,
    onMoveToSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onModelGroupsClick: () -> Unit,
    showMoveSheet: MutableState<Boolean>,
    showTokenUsageSheet: MutableState<Boolean>,
    showSkillsSheet: MutableState<Boolean>,
    showMcpsSheet: MutableState<Boolean>,
    showThinkingLevelSheet: MutableState<Boolean>,
    showModelPicker: MutableState<Boolean>,
    showRestoreDialog: MutableState<Boolean>,
    showClearChatDialog: MutableState<Boolean>,
    showNewChatStopDialog: MutableState<Boolean>,
    showEnhancedCacheDialog: MutableState<Boolean>,
    deleteFromHereTargetId: MutableState<String?>,
    restorableCount: MutableState<Int>,
) {
    // ViewModel-flow visibility flags, previously collected in ChatScreen.
    val showBrowserSheet by viewModel.showBrowserSheet.collectAsStateWithLifecycle()
    val showMemorySheet by viewModel.showMemorySheet.collectAsStateWithLifecycle()
    val memoryToolRecords by viewModel.memoryToolRecords.collectAsStateWithLifecycle()
    val availableGroups by viewModel.availableGroups.collectAsStateWithLifecycle()
    val selectedGroupId by viewModel.selectedGroupId.collectAsStateWithLifecycle()
    val messages by viewModel.uiMessages.collectAsStateWithLifecycle()

            if (showMoveSheet.value) {
                MoveToSessionSheet(
                    currentSessionId = sessionId,
                    chatRepository = chatRepository,
                    onDismiss = { showMoveSheet.value = false },
                    onSelect = { targetId ->
                        ChatViewModelStore.stashPendingTransfer(
                            ChatViewModelStore.PendingTransfer(
                                inputText = inputText,
                                attachments = viewModel.attachments.value,
                                // [T-android-moveto-stash-binding] Bind the stash to
                                // the chosen target so no other session can drain it.
                                targetId = targetId,
                            ),
                        )
                        viewModel.setInputText("")
                        viewModel.clearAttachments()
                        viewModel.clearShareInjectedFlag()
                        showMoveSheet.value = false
                        onMoveToSession(targetId)
                    },
                )
            }

            // Pre-send context gate (iOS "Context Near Capacity" alert).
            // Raised when the compact threshold is crossed and auto-compact is
            // OFF; with it on the ViewModel compacts silently and never gets
            // here. Three actions, matching iOS:
            //   Send Anyway                  — skip compaction entirely
            //   Compact & Send               — compact this once, pref untouched
            //   Compact & Enable Auto-Compact— compact AND opt in, so the
            //                                  threshold stops prompting from
            //                                  now on (iOS T-chat-auto-compact-opt-in)
            val showCompactBeforeSend by viewModel.showCompactBeforeSendPrompt.collectAsStateWithLifecycle()
            if (showCompactBeforeSend) {
                MinisAlertDialog(
                    // Back-gesture / scrim dismissal must NOT silently drop the
                    // user's text — cancelCompactBeforeSend puts it back in the
                    // composer.
                    onDismissRequest = { viewModel.cancelCompactBeforeSend() },
                    title = stringResource(R.string.context_near_capacity_title),
                    text = stringResource(R.string.context_near_capacity_message),
                    confirmText = stringResource(R.string.context_compact_and_send),
                    onConfirm = { viewModel.compactAndSendPending() },
                    dismissText = stringResource(R.string.context_send_anyway),
                    onDismiss = { viewModel.sendPendingWithoutCompacting() },
                    neutralText = stringResource(R.string.context_compact_and_enable_auto),
                    onNeutral = {
                        viewModel.compactAndSendPending(alsoEnableAutoCompact = true)
                    },
                )
            }

            // [T-android-foreground-approval] In-app approval gate for sensitive
            // tools (file_write / file_edit). ApprovalGate.requestApproval blocks
            // the tool coroutine in waitFor(id); while the user is IN the chat we
            // surface the request here instead of relying on the notification-bar
            // action (which remains the background fallback). Resolving from here
            // wakes the waiter and cancels the matching notification via the
            // ViewModel helpers. When several are queued the oldest is shown first;
            // the next appears as soon as this one resolves.
            val pendingApprovals by viewModel.pendingApprovals.collectAsStateWithLifecycle()
            pendingApprovals.values.firstOrNull()?.let { approval ->
                MinisAlertDialog(
                    // Back-gesture / scrim dismissal is treated as a deny — the
                    // safe default, and it still wakes the blocked coroutine.
                    onDismissRequest = { viewModel.denyPendingTool(approval.id) },
                    title = "需要审批: ${approval.toolName}",
                    text = approval.preview.ifBlank { "Agent 请求执行敏感操作" },
                    confirmText = "同意",
                    onConfirm = { viewModel.approvePendingTool(approval.id) },
                    dismissText = "拒绝",
                    onDismiss = { viewModel.denyPendingTool(approval.id) },
                    // [T-session-allow-all] Third action: approve this request
                    // and auto-approve every remaining request this session.
                    neutralText = "本次会话全部允许",
                    onNeutral = { viewModel.approveAllForSession(approval.id) },
                )
            }

            if (showRestoreDialog.value) {
                MinisAlertDialog(
                    onDismissRequest = { showRestoreDialog.value = false },
                    title = stringResource(R.string.chat_menu_restore_deleted),
                    text = pluralStringResource(
                        R.plurals.chat_restore_deleted_dialog_body,
                        restorableCount.value,
                        restorableCount.value,
                    ),
                    confirmText = stringResource(R.string.chat_restore_deleted_confirm),
                    onConfirm = {
                        viewModel.restoreArchivedMessages()
                        showRestoreDialog.value = false
                        restorableCount.value = 0
                    },
                )
            }
            if (showClearChatDialog.value) {
                MinisAlertDialog(
                    onDismissRequest = { showClearChatDialog.value = false },
                    title = stringResource(R.string.chat_menu_clear_chat),
                    text = stringResource(R.string.chat_clear_dialog_body),
                    confirmText = stringResource(R.string.chat_clear_dialog_confirm),
                    isDestructive = true,
                    onConfirm = {
                        viewModel.clearChat()
                        viewModel.setInputText("")
                        showClearChatDialog.value = false
                    },
                )
            }
            // [T-android-delete-from-here] Confirm before truncating. The cut
            // removes the tapped message AND everything after it with no undo,
            // so the body states how many messages that actually is — "delete
            // from here" alone doesn't convey whether it's one message or
            // forty. Counting from the live list keeps it accurate even if the
            // conversation grew while the menu was open.
            deleteFromHereTargetId.value?.let { targetId ->
                val affected = remember(targetId, messages) {
                    val idx = messages.indexOfFirst { it.id == targetId }
                    if (idx < 0) 0 else messages.size - idx
                }
                MinisAlertDialog(
                    onDismissRequest = { deleteFromHereTargetId.value = null },
                    title = stringResource(R.string.chat_longpress_delete_from_here),
                    text = pluralStringResource(
                        R.plurals.chat_delete_from_here_dialog_body,
                        affected,
                        affected,
                    ),
                    confirmText = stringResource(R.string.chat_delete_from_here_confirm),
                    isDestructive = true,
                    onConfirm = {
                        viewModel.deleteFromMessage(targetId)
                        deleteFromHereTargetId.value = null
                    },
                )
            }
            // [T-new-chat-menu-entry] Streaming guard for the menu's New Chat:
            // confirm → stop the running task, then navigate to a fresh draft;
            // dismiss → stay in the current chat.
            if (showNewChatStopDialog.value) {
                MinisAlertDialog(
                    onDismissRequest = { showNewChatStopDialog.value = false },
                    title = stringResource(R.string.chat_menu_new_chat),
                    text = stringResource(R.string.chat_new_chat_stop_dialog_body),
                    confirmText = stringResource(R.string.chat_new_chat_stop_dialog_confirm),
                    isDestructive = true,
                    onConfirm = {
                        showNewChatStopDialog.value = false
                        viewModel.cancelStream()
                        onNewChat()
                    },
                )
            }
            // [T-android-enhanced-cache] One-time extra-billing confirmation
            // before the first enable. Accepting records the durable ack and
            // turns the toggle on; subsequent enables skip the dialog.
            if (showEnhancedCacheDialog.value) {
                MinisAlertDialog(
                    onDismissRequest = { showEnhancedCacheDialog.value = false },
                    title = stringResource(R.string.chat_menu_enhanced_cache),
                    text = stringResource(R.string.enhanced_cache_dialog_body),
                    confirmText = stringResource(R.string.enhanced_cache_dialog_confirm),
                    onConfirm = {
                        viewModel.confirmAndEnableEnhancedCache()
                        showEnhancedCacheDialog.value = false
                    },
                )
            }
    // Browser bottom sheet
    if (showBrowserSheet) {
        BrowserSheet(
            tabPool = viewModel.browserTabPool,
            onDismiss = { viewModel.dismissBrowserSheet() },
        )
    }

    // Session Token Usage bottom sheet
    if (showTokenUsageSheet.value) {
        TokenUsageSheet(
            viewModel = viewModel,
            onDismiss = { showTokenUsageSheet.value = false },
        )
    }

    // Memory bottom sheet
    if (showMemorySheet && memoryRepository != null) {
        SessionMemorySheet(
            memoryRepository = memoryRepository,
            toolRecords = memoryToolRecords,
            onDismiss = { viewModel.dismissMemorySheet() },
            onRevokeRecord = { record -> viewModel.revokeMemoryRecord(record) },
            onSaveRecord = { record, newContent -> viewModel.replaceMemoryRecord(record, newContent) },
            // Show what the injector reads, not a file guess: resolved persona
            // for this session's provider, both GLOBAL.md levels, and the
            // captured assembly snapshot as ground truth.
            sources = InjectionSources(
                appRepo = viewModel.memoryRepository,
                sessionRepo = runCatching { viewModel.sessionMemoryRepo() }.getOrNull(),
                providerInstanceId = viewModel.activeProviderInstanceId(),
                providerLabel = viewModel.activeProviderInstanceLabel(),
                sessionId = viewModel.sessionId,
            ),
        )
    }

    // Session Skills bottom sheet
    if (showSkillsSheet.value && skillRepository != null) {
        SessionSkillsSheet(
            skillRepository = skillRepository,
            sessionId = sessionId,
            onDismiss = { showSkillsSheet.value = false },
        )
    }

    // [T-mcp-integration-android] MCPs-in-Session sheet.
    if (showMcpsSheet.value && mcpRepository != null) {
        SessionMcpsSheet(
            mcpRepository = mcpRepository,
            sessionId = sessionId,
            onDismiss = { showMcpsSheet.value = false },
        )
    }

    // [T-android-thinking-badge-navbar] Thinking-level sheet opened by tapping
    // the navbar thinking badge. Mirrors iOS ThinkingLevelSheetView: an Off row
    // plus every level the current model supports, each selectable.
    if (showThinkingLevelSheet.value) {
        val currentThinkingLevel by viewModel.thinkingLevel.collectAsStateWithLifecycle()
        ThinkingLevelSheet(
            currentLevel = currentThinkingLevel,
            availableLevels = viewModel.availableThinkingLevels,
            onSelect = { level ->
                viewModel.setThinkingLevel(level)
                showThinkingLevelSheet.value = false
            },
            onDismiss = { showThinkingLevelSheet.value = false },
        )
    }

    // Model Picker bottom sheet
    if (showModelPicker.value) {
        val config by providerRepository.config.collectAsStateWithLifecycle()
        val activeEntryId by viewModel.activeEntryId.collectAsStateWithLifecycle()

        // When the user picks a model whose output is image/audio/video, defer
        // the actual binding behind a confirmation dialog — those models can't
        // drive an Agent loop, so we steer the user toward a text-output model
        // (or, if they really want it, hint at adding it as a tool inside an
        // Agent loop instead).
        var pendingNonTextSelection by remember {
            mutableStateOf<PendingNonTextSelection?>(null)
        }
        val resolveImageLabel = stringResource(R.string.model_picker_modality_image)
        val resolveAudioLabel = stringResource(R.string.model_picker_modality_audio)
        val resolveVideoLabel = stringResource(R.string.model_picker_modality_video)
        fun nonTextLabelFor(model: LLMModel): String? {
            val mods = model.outputModalities?.map { it.lowercase() } ?: emptyList()
            return when {
                "image" in mods -> resolveImageLabel
                "audio" in mods -> resolveAudioLabel
                "video" in mods -> resolveVideoLabel
                else -> null
            }
        }
        fun entryById(entryId: String): ModelEntry? =
            config.modelEntries.firstOrNull { it.id == entryId }

        ModelPickerSheet(
            groups = availableGroups,
            selectedGroupId = selectedGroupId,
            activeEntryId = activeEntryId,
            defaultPrimaryGroupId = config.defaultPrimaryGroupId,
            config = config,
            providerRepository = providerRepository,
            onSelectGroup = { groupId ->
                val group = availableGroups.firstOrNull { it.id == groupId }
                val firstEntry = group?.memberEntryIds?.firstNotNullOfOrNull(::entryById)
                val label = firstEntry?.model?.let(::nonTextLabelFor)
                if (label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.Group(
                        groupId = groupId,
                        modelDisplayName = firstEntry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectGroup(groupId)
                    showModelPicker.value = false
                }
            },
            onSelectGroupEntry = { groupId, entryId ->
                val entry = entryById(entryId)
                val label = entry?.model?.let(::nonTextLabelFor)
                if (entry != null && label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.GroupEntry(
                        groupId = groupId,
                        entryId = entryId,
                        modelDisplayName = entry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectGroupEntry(groupId, entryId)
                    showModelPicker.value = false
                }
            },
            onSelectEntry = { entryId ->
                val entry = entryById(entryId)
                val label = entry?.model?.let(::nonTextLabelFor)
                if (entry != null && label != null) {
                    pendingNonTextSelection = PendingNonTextSelection.Entry(
                        entryId = entryId,
                        modelDisplayName = entry.model.displayName,
                        modalityLabel = label,
                    )
                } else {
                    viewModel.selectEntry(entryId)
                    showModelPicker.value = false
                }
            },
            onDismiss = { showModelPicker.value = false },
            // [T-android-modelpicker-group-edit] Close the picker first, then
            // navigate — pushing the management screen on top of an open bottom
            // sheet leaves the sheet lingering behind it on back.
            onEditGroups = {
                showModelPicker.value = false
                onModelGroupsClick()
            },
        )

        pendingNonTextSelection?.let { pending ->
            MinisAlertDialog(
                onDismissRequest = { pendingNonTextSelection = null },
                title = stringResource(R.string.model_picker_non_text_warning_title),
                text = stringResource(
                    R.string.model_picker_non_text_warning_body,
                    pending.modelDisplayName,
                    pending.modalityLabel,
                    pending.modalityLabel,
                ),
                confirmText = stringResource(R.string.model_picker_non_text_warning_use_anyway),
                dismissText = stringResource(R.string.model_picker_non_text_warning_choose_other),
                onConfirm = {
                    when (val sel = pending) {
                        is PendingNonTextSelection.Group -> viewModel.selectGroup(sel.groupId)
                        is PendingNonTextSelection.GroupEntry ->
                            viewModel.selectGroupEntry(sel.groupId, sel.entryId)
                        is PendingNonTextSelection.Entry -> viewModel.selectEntry(sel.entryId)
                    }
                    pendingNonTextSelection = null
                    showModelPicker.value = false
                },
            )
        }
    }
}
