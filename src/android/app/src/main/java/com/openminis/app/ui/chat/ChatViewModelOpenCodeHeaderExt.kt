package com.openminis.app.ui.chat

import com.openminis.app.provider.OpenCodeSessionHeader

/**
 * [T-opencode-session-header] Chat-side wiring of the OpenCode Go header
 * (GH#327/#304). The provider instance is LRU-cached and shared across
 * conversations, so a per-conversation id cannot live in its constructor —
 * it rides per-call via [LLMProvider.streamMessage(extraHeaders=)].
 *
 * [OpenCodeSessionHeader.headersFor] no-ops for every non-OpenCode base URL
 * and for draft placeholder ids (`__new__…`); the persisted id
 * ([ChatViewModel.realSessionId]) keeps cross-turn prompt-cache hits alive.
 * Extracted from the agent loop so the loop body stays under its ceiling.
 */
internal fun ChatViewModel.opencodeSessionHeaders(): Map<String, String> {
    val entryId = _activeEntryId.value ?: return emptyMap()
    val entry = providerRepository.config.value.modelEntries
        .find { it.id == entryId } ?: return emptyMap()
    val instance = providerRepository.instance(entry.providerInstanceId)
        ?: return emptyMap()
    return OpenCodeSessionHeader.headersFor(
        instance.effectiveBaseURL,
        realSessionId.ifEmpty { sessionId },
    )
}
