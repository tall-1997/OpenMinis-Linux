package com.openminis.app.ui.chat

import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory

/**
 * Group members that fallback skipped (disabled instance / missing
 * credential / hidden entry), with reasons. Mirrors iOS
 * ModelGroupRouter.unavailableMembers: when fallback exhausts, the user
 * needs to know WHY the other group members never got tried — e.g. the
 * Claude subscription was logged out, so every Anthropic entry was
 * silently filtered and fallback kept cycling OpenAI-only.
 */
internal fun ChatViewModel.unavailableGroupMembers(): List<String> {
    val groupId = _selectedGroupId.value ?: return emptyList()
    val config = providerRepository.config.value
    val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
    val result = mutableListOf<String>()
    for (entryId in group.memberEntryIds) {
        val entry = config.modelEntries.find { it.id == entryId } ?: continue
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
        val label = instance.label.ifEmpty { entry.model.provider }
        val reason = when {
            entry.isHidden -> "Hidden"
            !instance.isEnabled -> "Disabled"
            // [T-android-group-resolve-skip-uncredentialed] Must match the
            // routing filter, or a provider the user IS signed into gets
            // reported as "Not logged in".
            !providerRepository.hasAnyCredential(instance) -> "Not logged in"
            else -> continue
        }
        result.add("⚠️ ${entry.model.displayName} ($label): $reason")
    }
    return result
}

private fun ChatViewModel.resolveNextFallbackProvider(): LLMProvider? {
    val groupId = _selectedGroupId.value ?: return null
    val group = providerRepository.group(groupId) ?: return null
    val currentEntryId = _activeEntryId.value ?: return null
    val currentIdx = group.memberEntryIds.indexOf(currentEntryId)
    if (currentIdx < 0) return null

    val config = providerRepository.config.value
    // Try next entries in the group
    for (i in 1 until group.memberEntryIds.size) {
        val nextIdx = (currentIdx + i) % group.memberEntryIds.size
        val entryId = group.memberEntryIds[nextIdx]
        val entry = config.modelEntries.find { it.id == entryId } ?: continue
        val instance = providerRepository.instance(entry.providerInstanceId) ?: continue
        // [T-disabled-provider-via-group-android] Skip disabled
        // providers when walking the group's fallback chain so a
        // disabled provider sitting after the current entry doesn't
        // get picked up. buildFallbackProviders already does this; the
        // single-step variant here had the same bug.
        if (!instance.isEnabled) continue
        // [T-android-group-resolve-skip-uncredentialed] Same credential
        // notion as buildFallbackProviders — OAuth members belong in the
        // single-step chain too.
        if (!providerRepository.hasAnyCredential(instance)) continue
        val apiKey = providerRepository.usableApiKey(instance) ?: ""

        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _activeEntryId.value = entry.id
        val provider = ProviderFactory.create(instance, apiKey, entry.model, context)
        currentProvider = provider
        return provider
    }
    return null
}
