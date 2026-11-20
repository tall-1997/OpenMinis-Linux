package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.USER_STOP_NOTE
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resume an interrupted agent loop. Injects a `<system-reminder>` into
 * agentHistory so the model picks up where it left off, then re-enters
 * the agent loop in a fresh [streamJob]. Mirrors iOS
 * AIChatViewModel.resume().
 *
 * Safe to call only when [canResume] is true and [isStreaming] is false.
 * Clears [_canResume] on entry so repeated taps don't stack.
 */
fun ChatViewModel.resume() {
    if (_isStreaming.value || !_canResume.value) return
    val provider = currentProvider ?: run {
        _error.value = "No provider configured"
        return
    }
    _canResume.value = false
    _error.value = null
    // [T-android-resume-banner-race] A resume is a fresh claim too: bump the
    // epoch so any still-winding-down tail from the interrupted run (or a
    // concurrent loadSession re-detection) cannot re-raise the banner over
    // the turn this resume is about to start.
    claimResumeEpoch()
    // [T-error-persist-android] resume() follows finalizeAtTurnLimit's
    // setInlineError (which persisted an error sticker on the last assistant
    // row) — and a dropped run's wind-down can strand one on an EARLIER row
    // too. Sweep them all (memory + persisted) before the new turn starts.
    sweepStaleInlineErrors()
    AppLogger.info(ChatViewModel.TAG, "▶️ resume: continuing partial assistant message (no new header emitted)")
    // [T-android-tool-autoscroll] Start-of-turn snap. The thinking
    // placeholder is the only visible delta until the model's first
    // token, and the auto-follow tuple won't advance until content
    // streams — ChatScreen would otherwise leave the placeholder
    // behind the input bar.
    _forceScrollToBottom.tryEmit(Unit)

    val sid = activeSessionId
    val epoch = snapshotResumeEpoch()
    viewModelScope.launch {
        // Rebuild agentHistory from the DB before continuing — a fresh
        // sendMessage() does exactly this (rebuildAgentHistoryFromDatabase),
        // which is why a normal send never carries stale context while a
        // Resume could. The in-memory agentHistory lags the DB whenever the
        // process was killed mid-turn and the ViewModel was restored with a
        // partial in-memory tail, or any path committed rows the in-memory
        // list missed. The DB is the authoritative store; trusting memory
        // here is how a resume picks up an old, pre-interruption context.
        if (!rebuildAgentHistoryFromDatabase(sid, epoch)) {
            if (ownsTranscript(sid, epoch)) raiseCanResume(epoch)
            return@launch
        }
        if (!ownsTranscript(sid, epoch)) return@launch
        // A Stop note records an interruption, not consent to continue.
        // Add that intent only here, after the user actually taps Resume.
        val tail = agentHistory.lastOrNull()
        if (tail?.role == LLMMessage.Role.ASSISTANT ||
            (tail?.isHostGenerated == true && tail.content == USER_STOP_NOTE)
        ) {
            val reminder =
                "The user stopped the previous response but now wants to continue. Pick up exactly where you left off."
            if (!commitHiddenUserNote(reminder, sid, expectedEpoch = epoch)) {
                if (ownsTranscript(sid, epoch)) raiseCanResume(epoch)
                return@launch
            }
        }

        // [P0-5] Prompt assembly does disk I/O (skills/MCP reloads, memory
        // fragments) — keep it off the main dispatcher. All inputs are
        // StateFlow reads / pure helpers; written fields are @Volatile.
        val systemPrompt = withContext(Dispatchers.IO) { buildMemoryDeliveryPrompt() }
        if (!ownsTranscript(sid, epoch)) return@launch

        AppLogger.info(ChatViewModel.TAG_STREAM, "resume _isStreaming=true (sid=$sid)")
        _isStreaming.value = true
        streamJob = launchActiveRun(activeSessionId, Dispatchers.IO, ownerSessionIds = setOf(activeSessionId, sessionId, realSessionId), beforeStart = { streamJob = it }) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob ENTER sid=$activeSessionId")
            try {
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(ChatViewModel.TAG_STREAM, "resume streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let {
                        providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy
                    } ?: com.openminis.app.data.model.FallbackStrategy.default
                }
                val fallbackProviders = buildFallbackProviders(provider)
                try {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume runAgentLoop CALL")
                    runAgentLoop(
                        provider = provider,
                        systemPrompt = systemPrompt,
                        fallbackProviders = fallbackProviders,
                        fallbackStrategy = activeFallbackStrategy,
                    )
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume runAgentLoop RETURN normal")
                    if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                        drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                        AppLogger.info(ChatViewModel.TAG_STREAM, "resume drainQueuedPrompts RETURN")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume runAgentLoop CANCELLED")
                    Log.d(ChatViewModel.TAG, "Agent loop cancelled (resume)")
                } catch (e: Exception) {
                    AppLogger.error(ChatViewModel.TAG_STREAM, "resume runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(ChatViewModel.TAG, "Agent loop error (resume)", e)
                    if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                        setInlineError(e.message ?: "Unknown error")
                    }
                } finally {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob FINALLY enter")
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
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob FINALLY exit")
                }
            } catch (e: com.openminis.app.service.SlotQueueTimeout) {
                if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                    setInlineError(e.message ?: "会话排队超时，名额已释放")
                }
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob CANCELLED waiting for slot")
                Log.d(ChatViewModel.TAG, "Cancelled while waiting for concurrency slot (resume)")
            }
            // [T-android-stale-streamjob-clears-isstreaming] guard.
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resume _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resume _isStreaming SKIPPED (stale job)")
            }
            AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob EXIT")
        }
    }
}
