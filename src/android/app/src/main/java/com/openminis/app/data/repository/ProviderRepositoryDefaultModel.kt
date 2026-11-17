package com.openminis.app.data.repository

import com.openminis.app.data.model.ModelEntry

/**
 * [T-newchat-default-model-fallback-android] Default-model fallback
 * resolvers for [ProviderRepository], extracted to keep the repository
 * inside its architecture-policy line ceiling (same pattern as
 * ProviderRepositoryThinkingRules.kt / ProviderRepositoryPersist.kt). The
 * member facades on the repository keep the public surface unchanged for
 * callers; these carry the implementation bodies verbatim.
 */

/**
 * Resolve [ProviderRepository.lastUsedEntryId] to a still-valid, visible,
 * enabled-provider entry — or null when the recorded id was deleted / hidden /
 * its provider disabled. Callers treat null as "fall through to the next tier".
 */
internal fun ProviderRepository.resolveLastUsedVisibleEntry(): ModelEntry? {
    val id = lastUsedEntryId ?: return null
    return allVisibleEntries().firstOrNull { it.id == id }
}

/**
 * [T-newchat-default-model-fallback-android] Final-tier default for a new
 * chat: the newest text-output model from the newest-added enabled
 * provider. "Newest provider" = max [com.openminis.app.data.model.ProviderInstance.createdAt];
 * "newest model" = last text-capable entry in add order (modelEntries is
 * appended in add order, so the instance's last matching entry is the most
 * recently added). Image/audio-only models are excluded — a fresh chat must
 * default to something that can produce a text reply.
 *
 * Walks providers newest→oldest so that if the newest provider somehow has
 * no text model (all image/audio), we still land on the newest text model
 * from the next provider rather than returning null.
 */
internal fun ProviderRepository.resolveNewestProviderNewestTextEntry(): ModelEntry? {
    val config = _config.value
    val enabledProviders = config.instances
        .filter { it.isEnabled }
        .sortedByDescending { it.createdAt }
    for (instance in enabledProviders) {
        val textEntry = config.modelEntries
            .filter { it.providerInstanceId == instance.id && !it.isHidden && it.model.isTextOutput }
            .lastOrNull()
        if (textEntry != null) return textEntry
    }
    return null
}
