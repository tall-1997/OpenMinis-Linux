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
 * provider instance was edited since it was built, or when the app trust store
 * was changed (CA import/delete — [com.openminis.app.network.AppTrustStore.generation]).
 * Called where a turn takes `currentProvider` (send, retry, resume, rerun);
 * never mid-stream.
 */
internal fun ChatViewModel.rebuildProviderIfEdited() {
    // [GH#258][GH#287] A CA imported/removed changes AppTrustStore.generation;
    // the provider held here was built under the previous TLS material, so it
    // must be rebuilt exactly like an edited instance — otherwise an open chat
    // keeps the old socket until the process restarts.
    val trustChanged = providerTrustGeneration !=
        com.openminis.app.network.AppTrustStore.generation
    if (_isStreaming.value) return
    if (pendingProviderEdits.isEmpty() && !trustChanged) return
    val entryId = _activeEntryId.value ?: return
    val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return
    val id = entry.providerInstanceId
    if (id !in pendingProviderEdits && !trustChanged) return
    // [FIX-R2-13, review §三 逻辑] The pending edit is consumed only where the
    // outcome is known; it used to be dropped up front, so a deleted instance
    // or a missing credential left the chat on the OLD endpoint (with the edit
    // silently gone) until the session was switched.
    val instance = providerRepository.instance(id)
    if (instance == null) {
        // The instance is gone - nothing exists to rebuild (review 6.2: not
        // even a ProviderFactory re-resolution has anything to resolve), and
        // keeping the id would retry every turn. Drop it with a warning; a
        // credential saved later re-queues the id via ProviderCredentialEdits,
        // and a re-added instance arrives with a new config snapshot anyway.
        pendingProviderEdits = pendingProviderEdits - id
        AppLogger.warning(
            ChatViewModel.TAG,
            "[provider-edit] instance ${id.take(8)} no longer exists; dropping pending edit (chat keeps the previous provider)",
        )
        return
    }
    if (!providerRepository.hasAnyCredential(instance)) {
        // Edit stays pending: the next saved credential re-triggers the
        // rebuild on the next turn, config change or not. Cheap set lookups
        // meanwhile; no warning, this is an expected in-between state.
        return
    }
    val apiKey = providerRepository.usableApiKey(instance) ?: ""
    currentProvider = ProviderFactory.create(instance, apiKey, currentModel ?: entry.model, context)
    _providerName.value = instance.label.ifEmpty { entry.model.provider }
    pendingProviderEdits = pendingProviderEdits - id
    AppLogger.info(
        ChatViewModel.TAG,
        "[provider-edit] rebuilt provider for edited instance ${id.take(8)} " +
            "(responses=${instance.useResponsesAPI}, caReload=$trustChanged)",
    )
}
