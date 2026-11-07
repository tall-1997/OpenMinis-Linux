package com.openminis.app.ui.chat

import com.openminis.app.provider.LLMProvider

/**
 * [T-android-model-switch-next-request] Persist a group-fallback model
 * switch onto the VM so the next sendMessage() starts from it. Extracted
 * from the fallback branch of runAgentLoop so the loop can gate the
 * call on the model-selection epoch — when the user picks a model
 * mid-run, this writeback is skipped and their selection wins (see
 * [ModelSwitchGate]).
 */
internal fun ChatViewModel.persistFallbackModelSelection(
    next: LLMProvider,
    entryId: String?,
    isRealModelChange: Boolean,
) {
    // Also update class-level provider so the next sendMessage() starts from here
    this.currentProvider = next
    // Update top bar model info + active entry. (For a same-model
    // endpoint recovery these are no-ops on the visible model name, but
    // still keep activeEntryId / provider name in sync with the instance
    // we actually used.) [T-android-model-switch-next-request] Reads the
    // non-null parameter, not the VM's nullable property — the original
    // loop read its local `var currentProvider`, and the bare name inside
    // this extension resolves to `this.currentProvider` (LLMProvider?).
    _modelName.value = next.model.displayName
    // Update activeEntryId so model picker reflects the switch.
    val newEntry = providerRepository.config.value.modelEntries.find { it.id == entryId }
    if (newEntry != null) {
        _activeEntryId.value = newEntry.id
        currentModel = newEntry.model
        val newInstance = providerRepository.instance(newEntry.providerInstanceId)
        if (newInstance != null) {
            _providerName.value = newInstance.label.ifEmpty { newEntry.model.provider }
        }
    }
    // Flash ONLY on a genuine model switch — never on a transparent
    // same-model endpoint retry.
    if (isRealModelChange) _fallbackTrigger.value++
    // [T-android-cross-window-compact] REVERTED by design: auto-compacting
    // on a model switch silently dropped context the user could still see
    // (reads as data loss). The new model's own provider error, the
    // in-loop threshold guard, and dynamicMaxTokens() handle the smaller
    // window without surprise truncation.
    // Persist the fallback model so re-entering the session starts from here
    val groupId = _selectedGroupId.value
    if (groupId != null && newEntry != null) {
        persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"${newEntry.id}"}""")
    }
}
