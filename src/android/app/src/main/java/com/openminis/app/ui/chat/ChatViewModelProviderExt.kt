package com.openminis.app.ui.chat

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel

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

/**
 * [OpenMinis#311] True when the active provider instance is an
 * Anthropic-compatible relay with the provider-level force-thinking switch on.
 *
 * Both the composer picker and the agent loop's send path consult
 * [ChatViewModel.currentModelSupportsReasoning] and would otherwise hard-pin
 * thinking to OFF for a model whose catalog row says
 * `supportsReasoning = false`, so the switch has to lift that capability read
 * too — not just the provider's clamp.
 */
internal fun ChatViewModel.activeInstanceForcesThinking(): Boolean {
    val instanceId = activeProviderInstanceId() ?: return false
    val instance = providerRepository.instance(instanceId) ?: return false
    return instance.forceThinkingEnabled && instance.providerType == ProviderType.anthropic
}

/**
 * [OpenMinis#311] The thinking ceiling to OFFER for [entry], lifted to XHIGH
 * while the force switch is on. Without the lift the picker would stay hidden
 * (ceiling OFF) and the switch would be untouchable from the UI.
 */
internal fun ChatViewModel.forcedThinkingCeiling(entry: ModelEntry): ThinkingLevel {
    val ceiling = entry.effectiveMaxThinkingLevel
    if (activeInstanceForcesThinking() && ceiling.rank < ThinkingLevel.XHIGH.rank) {
        return ThinkingLevel.XHIGH
    }
    return ceiling
}
