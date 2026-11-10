package com.openminis.app.ui.chat

/**
 * Provider-instance lookups for [ChatViewModel], extracted from ChatViewModel.kt
 * to keep that file under its architecture-policy ceiling (the ceiling never
 * rises — see scripts/ci/check_architecture_policy.py).
 */
internal fun ChatViewModel.activeProviderInstanceId(): String? =
    _activeEntryId.value?.let { id ->
        providerRepository.config.value.modelEntries.find { it.id == id }?.providerInstanceId
    }

internal fun ChatViewModel.activeProviderInstanceLabel(): String? =
    activeProviderInstanceId()?.let { iid ->
        providerRepository.config.value.instances.find { it.id == iid }?.label
    }
