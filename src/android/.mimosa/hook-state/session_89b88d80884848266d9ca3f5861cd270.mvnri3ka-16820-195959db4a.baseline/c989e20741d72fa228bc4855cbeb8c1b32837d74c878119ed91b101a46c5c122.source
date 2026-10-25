package com.openminis.app.ui.chat

import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.ui.chat.ChatViewModel.FallbackCandidate

internal fun ChatViewModel.buildFallbackProviders(primaryProvider: LLMProvider): List<FallbackCandidate> {
    // Settings → Model Groups only. A model picked under a provider
    // section never sets _selectedGroupId, so this returns empty and
    // the turn stays on that one model.
    val groupId = _selectedGroupId.value ?: return emptyList()
    val config = providerRepository.config.value
    val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
    val members = group.memberEntryIds
    // Find current provider's position in the group.
    // [T-android-fallback-entry-identity] Prefer the ACTIVE ENTRY id — the
    // model-id match below is ambiguous when two instances share a model id
    // and would anchor the cycle at the wrong member.
    val activeEntry = _activeEntryId.value
    val currentIdx = members.indexOfFirst { it == activeEntry }.takeIf { it >= 0 }
        ?: members.indexOfFirst { entryId ->
            config.modelEntries.find { it.id == entryId }?.model?.id == primaryProvider.model.id
        }
    val result = mutableListOf<FallbackCandidate>()
    // Iterate starting from the entry AFTER the primary, cycling around
    for (offset in 1 until members.size) {
        val idx = if (currentIdx >= 0) (currentIdx + offset) % members.size else offset
        val entryId = members[idx]
        val entry = config.modelEntries.find { it.id == entryId } ?: continue
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
        if (!instance.isEnabled) continue
        // [T-android-group-resolve-skip-uncredentialed] Credential test via
        // hasAnyCredential so an OAuth-logged-in provider is kept as a
        // fallback candidate; usableApiKey alone reads only the API-key
        // slot and dropped every OAuth member from the chain.
        if (!providerRepository.hasAnyCredential(instance)) continue
        val apiKey = providerRepository.usableApiKey(instance) ?: ""
        val p = try {
            ProviderFactory.create(instance, apiKey, entry.model, context)
        } catch (_: Exception) { continue }
        // Same host+key+model is the same relay bucket; skip it so fallback
        // actually moves to another model name or another key.
        if (com.openminis.app.provider.ProviderKeyGate.sameBucket(p.callGateKey, primaryProvider.callGateKey)) continue
        result.add(FallbackCandidate(provider = p, entryId = entry.id))
    }
    return result
}
