package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.isPureVideoGenerator
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [T-android-rerun-from-tool-block-position] Shared streaming tail used by
 * both [retryFromMessage] and [rerunFromToolBlock]: refresh the OAuth
 * token if needed, build the (OAuth-prefixed) system prompt, and launch
 * the agent-loop stream job. Callers must have already (a) claimed
 * `_isStreaming = true` synchronously, (b) truncated UI + DB to the desired
 * re-entry point, and (c) rebuilt [agentHistory]. Returns true once the
 * stream job is launched (the caller's outer `finally` resets
 * `_isStreaming` only when this returns false / throws first).
 */
internal suspend fun ChatViewModel.runRerunStreamTail(
    initialProvider: LLMProvider,
    label: String,
): Boolean {
    var provider = initialProvider
    // Refresh OAuth token if needed
    if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
        try {
            val activeEntryId = _activeEntryId.value
            val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
            if (instance != null) {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val freshToken = manager?.validAccessToken()
                if (freshToken != null) {
                    val storedKey = providerRepository.loadApiKey(instance.id)
                    if (freshToken != storedKey) {
                        providerRepository.saveApiKey(instance.id, freshToken)
                        provider = com.openminis.app.provider.ProviderFactory.create(
                            instance, freshToken, currentModel ?: provider.model, context
                        )
                        currentProvider = provider
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(ChatViewModel.TAG, "OAuth token refresh failed: ${e.message}")
        }
    }

    val baseSystemPrompt = buildSystemPrompt()
    val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
        val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
        else "$prefix\n\n${baseSystemPrompt ?: ""}"
    } else baseSystemPrompt

    // _isStreaming was already set synchronously by the caller.
    val launchedProvider = provider
    streamJob = viewModelScope.launchActiveRun(activeSessionId, Dispatchers.IO, ownerSessionIds = setOf(activeSessionId, sessionId, realSessionId), beforeStart = { streamJob = it }) {
        AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob ENTER sid=$activeSessionId")
        try {
            SessionConcurrencyManager.acquireSlot(activeSessionId)
            AppLogger.debug(ChatViewModel.TAG_STREAM, "$label streamJob slot acquired")
            SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
            val activeFallbackStrategy = run {
                val groupId = _selectedGroupId.value
                groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                    ?: com.openminis.app.data.model.FallbackStrategy.default
            }
            val fallbackProviders = buildFallbackProviders(launchedProvider)
            try {
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label runAgentLoop CALL")
                if (groupChatEnabled.value && !launchedProvider.model.isPureVideoGenerator) {
                    groupChatCloseRequested = false
                    runGroupChat(launchedProvider, closing = false)
                    if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                        drainQueuedPrompts(launchedProvider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                    }
                } else {
                runAgentLoop(
                    provider = launchedProvider,
                    systemPrompt = systemPrompt,
                    fallbackProviders = fallbackProviders,
                    fallbackStrategy = activeFallbackStrategy,
                    // Contract above: callers already truncated and rebuilt
                    // agentHistory. Rebuilding again inside the loop doubled
                    // the pre-first-token DB work on long sessions, which is
                    // the stretch where the UI showed "running" but no
                    // process bubble yet.
                    historyAlreadyRebuilt = true,
                )
                }
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label runAgentLoop RETURN normal")
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label runAgentLoop CANCELLED")
                Log.d(ChatViewModel.TAG, "Agent loop cancelled")
            } catch (e: Exception) {
                AppLogger.error(ChatViewModel.TAG_STREAM, "$label runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                Log.e(ChatViewModel.TAG, "Agent loop error ($label)", e)
                if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                    setInlineError(e.message ?: "Unknown error")
                    // T298: flag the upcoming setInactive() so the
                    // background completion notifier renders the ❌
                    // variant instead of a clean success.
                    SessionActivityTracker.markStreamError(activeSessionId)
                }
            } finally {
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob FINALLY enter")
                // [T-android-overlay-reply-status-34599] Surface
                // the assistant's most recent reply text to the
                // overlay BEFORE setInactive so the post-completion
                // overlay state (no-running, has-outcome) carries a
                // non-null excerpt. Reading _messages here is safe:
                // we're in the finally block of the agent loop and
                // the stream has already flushed its last delta.
                publishOverlayReplyExcerpt(activeSessionId)
                SessionActivityTracker.setInactive(activeSessionId)
                SessionConcurrencyManager.releaseSlot(activeSessionId)
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob FINALLY exit")
            }
        } catch (e: com.openminis.app.service.SlotQueueTimeout) {
            if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                setInlineError(e.message ?: "会话排队超时，名额已释放")
            }
        } catch (e: CancellationException) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob CANCELLED waiting for slot")
            Log.d(ChatViewModel.TAG, "Cancelled while waiting for concurrency slot")
        }
        // [T-android-stale-streamjob-clears-isstreaming] Only the current
        // streamJob is allowed to flip _isStreaming false. An orphaned
        // earlier job (cancelled but its finally still draining downstream
        // I/O) reaching this tail AFTER a fresh send/resume/retry has
        // already taken over would otherwise hide the Stop button while
        // the new turn is still streaming. See `var streamJob` KDoc and
        // XIN 2026-06-12 log (20:22:26 / 20:23:25).
        if (streamJob === coroutineContext[Job]) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "$label _isStreaming=false (about to set)")
            _isStreaming.value = false
        } else {
            AppLogger.info(ChatViewModel.TAG_STREAM, "$label _isStreaming SKIPPED (stale job; current=${streamJob?.hashCode()} this=${coroutineContext[Job]?.hashCode()})")
        }
        AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob EXIT")
    }
    return true
}
