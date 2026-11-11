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
 *
 * [GH#347] Custom OpenAI-compatible endpoints get the same session header via
 * [OpenCodeSessionHeader.customEndpointHeadersFor]. Users who point the app at
 * an OpenCode-compatible relay (or at a self-hosted gateway fronting OpenCode
 * Go) hit the same `400 missing x-opencode-session` routing rule the built-in
 * Zen lane already satisfies; host-gating the header to `opencode.ai` left
 * those endpoints unable to route a request at all. The custom-endpoint
 * branch only fires when the instance actually carries a custom base URL, so
 * official endpoints are untouched.
 */
internal fun ChatViewModel.opencodeSessionHeaders(): Map<String, String> {
    val entryId = _activeEntryId.value ?: return emptyMap()
    val entry = providerRepository.config.value.modelEntries
        .find { it.id == entryId } ?: return emptyMap()
    val instance = providerRepository.instance(entry.providerInstanceId)
        ?: return emptyMap()
    val baseUrl = instance.effectiveBaseURL
    val persistedSessionId = realSessionId.ifEmpty { sessionId }
    val customEndpointHeaders =
        OpenCodeSessionHeader.customEndpointHeadersFor(baseUrl, persistedSessionId)
    return customEndpointHeaders.ifEmpty {
        OpenCodeSessionHeader.headersFor(baseUrl, persistedSessionId)
    }
}
