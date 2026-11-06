// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (ui/chat/ChatViewModel.kt watchProviderEdits / providerKeyWatch /
// rebuildProviderIfEdited), split into an extension file because this tree's
// ChatViewModel sits at its architecture line ceiling. The two state fields
// the watch needs live on the VM (pendingProviderEdits / providerInstancesSeen).
package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.launch
import com.openminis.app.provider.ProviderFactory

/**
 * [T-android-provider-edit-mid-session] Track provider-instance edits for an
 * open chat: base URL, API type (Responses flag), user agent, Azure mode, or a
 * saved/removed credential used to leave `currentProvider` on the OLD endpoint
 * until the provider was deleted and re-added. Two collectors here —
 * `providerRepository.config` (diffed instance snapshots) and
 * [com.openminis.app.data.repository.ProviderCredentialEdits] (key-store
 * writes the config flow never sees) — feed [rebuildProviderIfEdited], which
 * the turn entry points (send / retry / resume / rerun) call before taking
 * `currentProvider`. Never mid-stream.
 */
internal fun ChatViewModel.startProviderEditWatch() {
    viewModelScope.launch {
        providerRepository.config.collect { cfg ->
            val now = cfg.instances.associateBy { it.id }
            val before = providerInstancesSeen
            providerInstancesSeen = now
            if (before == null) return@collect
            val edited = editedProviderInstanceIds(before, now)
            if (edited.isNotEmpty()) pendingProviderEdits = pendingProviderEdits + edited
        }
    }
    // [FIX-261025-12] A saved / removed API key counts as an edit of its instance.
    viewModelScope.launch {
        com.openminis.app.data.repository.ProviderCredentialEdits.ids.collect { id ->
            pendingProviderEdits = pendingProviderEdits + id
        }
    }
}

/**
 * Rebuild [ChatViewModel.currentProvider] from the active model entry when its
 * provider instance was edited since it was built. Called where a turn takes
 * `currentProvider` (send, retry, resume, rerun); never mid-stream.
 */
internal fun ChatViewModel.rebuildProviderIfEdited() {
    if (pendingProviderEdits.isEmpty() || _isStreaming.value) return
    val entryId = _activeEntryId.value ?: return
    val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return
    val id = entry.providerInstanceId
    if (id !in pendingProviderEdits) return
    pendingProviderEdits = pendingProviderEdits - id
    val instance = providerRepository.instance(id) ?: return
    if (!providerRepository.hasAnyCredential(instance)) return
    val apiKey = providerRepository.usableApiKey(instance) ?: ""
    currentProvider = ProviderFactory.create(instance, apiKey, currentModel ?: entry.model, context)
    _providerName.value = instance.label.ifEmpty { entry.model.provider }
    AppLogger.info(
        ChatViewModel.TAG,
        "[provider-edit] rebuilt provider for edited instance ${id.take(8)} (responses=${instance.useResponsesAPI})",
    )
}
