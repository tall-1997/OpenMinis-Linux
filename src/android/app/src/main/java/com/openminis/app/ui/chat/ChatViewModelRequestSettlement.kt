package com.openminis.app.ui.chat

import com.openminis.app.data.ContextTokenEstimate
import com.openminis.app.data.ContextUsageAttempt
import com.openminis.app.data.model.ContextUsageAnchor
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.reportedRequestInputTokens
import com.openminis.app.data.settleContextUsageAttempt
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.anthropic.AnthropicProvider
import kotlinx.coroutines.CancellationException

internal fun isNaturalAgentStop(reason: String?): Boolean = when (reason?.lowercase()) {
    "stop", "end_turn", "stop_sequence" -> true
    else -> false
}

/** A closed transport is not proof of a complete answer or tool-call response. */
internal fun eligibleContextReceipt(reason: String?, hasText: Boolean, hasTools: Boolean): Boolean =
    if (isNaturalAgentStop(reason)) hasText || hasTools
    else hasTools && reason?.lowercase() in setOf("tool_calls", "tool_use", "function_call")

internal suspend fun ChatViewModel.acceptRequestProjection(
    provider: LLMProvider,
    projection: ProjectedChatRequest,
    attempt: ContextUsageAttempt?,
    usage: LLMUsage?,
    realUserId: String?,
): ContextUsageAnchor? {
    // The Codex image route serializes its own instructions and does not carry chat memory.
    if (!projection.carriesChatContext) return null
    val request = projection.outboundRequest
    val actual = reportedRequestInputTokens(usage, provider is AnthropicProvider)
    // [T-anchor-route-guard] A parallel provider offload can mutate the cached
    // client between the projection snapshot and settlement. The measured input
    // belongs to the route the projection was BUILT for; if the live attribution
    // no longer matches, keep the billing but drop the anchor and calibration.
    val routeStillCurrent = ProviderFactory.requestAttribution(provider).contextRouteId == projection.model.contextRouteId
    val anchor = if (routeStillCurrent) attempt?.let { settleContextUsageAttempt(it, usage) } else null
    if (actual != null && routeStillCurrent && projection.markerId == _cachedLatestMarker?.id) {
        _lastTurnContextTokens.value = actual
        _lastCacheHitRate.value = ((usage?.cacheReadInputTokens ?: 0).toDouble() / actual).coerceIn(0.0, 1.0)
        refreshContextUsage(ContextTokenEstimate(actual, actual), projection.window)
    }
    try {
        recordSuccessfulMemoryDelivery(projection.prompt?.daily, request.systemPrompt, request.messages, projection.recall, realUserId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.warning(ChatViewModel.TAG, "Memory delivery bookkeeping failed: ${e.javaClass.simpleName}")
    }
    PromptCacheWarmCoordinator.onStreamTurnSucceeded(
        provider, request.messages, request.systemPrompt, request.tools, request.thinkingLevel,
        usage, isBusy = { _isStreaming.value },
        replayable = projection.prompt?.daily?.let { it.end > it.start } != true && !projection.hasRequestLocalContext,
    )
    return anchor
}
