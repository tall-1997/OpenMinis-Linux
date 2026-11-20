package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.R
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.isPureVideoGenerator
import com.openminis.app.data.repository.PendingTurnRow
import com.openminis.app.data.repository.appendTurnRows
import com.openminis.app.provider.LLMProvider
import com.openminis.app.service.ActiveRun
import com.openminis.app.service.ActiveRunContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Claim only the prepared prefix: later enqueues are left alone; edits/withdrawals invalidate it. */
internal fun claimPreparedPromptQueue(
    queue: MutableStateFlow<List<QueuedPrompt>>,
    prepared: List<QueuedPrompt>,
    ownsQueue: () -> Boolean,
): Boolean {
    if (prepared.isEmpty()) return false
    while (ownsQueue()) {
        val current = queue.value
        if (current.size < prepared.size || prepared.indices.any { prepared[it] != current[it] }) return false
        if (!ownsQueue()) return false
        if (queue.compareAndSet(current, current.drop(prepared.size))) return true
    }
    return false
}

/** Award the claim only when ready to append. An exception before commit leaves the batch queued. */
internal suspend fun <T> persistPreparedQueueBatch(
    queue: MutableStateFlow<List<QueuedPrompt>>,
    prepared: List<QueuedPrompt>,
    ownsQueue: () -> Boolean,
    appendToOwner: suspend () -> T,
): T? {
    val caller = currentCoroutineContext()
    return withContext(NonCancellable + Dispatchers.IO) {
        caller.ensureActive()
        if (!claimPreparedPromptQueue(queue, prepared, ownsQueue)) return@withContext null
        // Before starting, recheck the ownership on the far side of the CAS.
        // After the atomic append starts, let it finish even on cancellation;
        // restoring after commit would duplicate the user turn.
        fun restoreUnsent() {
            val ids = prepared.mapTo(hashSetOf()) { it.id }
            queue.update { current -> prepared + current.filterNot { it.id in ids } }
        }
        if (!ownsQueue()) {
            restoreUnsent()
            return@withContext null
        }
        val persisted = try {
            caller.ensureActive()
            appendToOwner()
        } catch (e: Exception) {
            restoreUnsent()
            throw e
        }
        persisted.takeIf { ownsQueue() }
    }
}

internal suspend fun ChatViewModel.queuedTurnSession(expectedSessionId: String?, run: ActiveRun?): String? {
    val origin = expectedSessionId ?: activeSessionId
    if (origin != activeSessionId || run?.isStopped == true || (run != null && origin !in run.ownerSessionIds)) return null
    if (realSessionId.isNotBlank()) return origin
    // The only permitted owner change is this VM's own draft promotion.
    return ensureSession().takeIf { ChatViewModelStore.resolvePersistedId(origin) == it && ownsQueuedTurn(it, run) }
}

internal fun ChatViewModel.ownsQueuedTurn(sid: String, run: ActiveRun?): Boolean =
    activeSessionId == sid && run?.isStopped != true

internal data class PreparedQueuedTurn(
    val userText: String,
    val modelText: String,
    val partsJson: String,
    val contentParts: List<AgentContentPart>,
    val attachments: PreparedAttachments,
    val paste: PastedParts?,
) {
    fun message(id: String) = LLMMessage(
        role = LLMMessage.Role.USER, content = modelText, imageParts = attachments.imageParts,
        contentParts = contentParts, dbMessageId = id,
    )

    fun bubble(id: String) = ChatMessage(
        id = id, role = "user",
        content = paste?.let { stripConsumedPasteMarkers(userText, it.consumedIds) } ?: userText,
        imageUris = attachments.imageUris,
        attachmentNames = attachments.attachmentNames + paste?.uiNames.orEmpty(),
        attachmentUris = attachments.nonImageUris + paste?.uiUris.orEmpty(),
        sourceDbIds = listOf(id),
    )
}

internal suspend fun ChatViewModel.prepareQueuedTurn(
    queued: List<QueuedPrompt>,
    sid: String,
    run: ActiveRun?,
): PreparedQueuedTurn? {
    if (!ownsQueuedTurn(sid, run)) return null
    val attachments = prepareUserAttachments(queued.flatMap { it.attachments }, sid)
    if (!ownsQueuedTurn(sid, run)) return null
    val texts = queued.map { it.text }.filter(String::isNotBlank)
    val userText = texts.joinToString("\n\n")
    val paste = buildPastedParts(userText, sid)
    if (!ownsQueuedTurn(sid, run)) return null
    val modelText = paste?.modelText ?: userText
    val mediaParts = buildList {
        attachments.imageParts.forEachIndexed { index, part ->
            val path = attachments.imageUploadPaths.getOrNull(index)
            if (path != null) add(AgentContentPart.Text("[attached image: $path]"))
            add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
        }
        attachments.attachedFilesXml?.let { add(AgentContentPart.Text(it)) }
    }
    if (modelText.isBlank() && mediaParts.isEmpty()) {
        // Preserve the placeholders for edit/withdraw instead of sending an empty user row.
        withContext(Dispatchers.Main.immediate) {
            if (ownsQueuedTurn(sid, run)) setInlineError(context.getString(R.string.queue_dropped_no_content))
        }
        return null
    }
    val partsJson = buildUserPartsJson(userText, attachments.mediaRefPartsJson, attachments.attachedFilesXml, bodyPartsJson = paste?.partsJson)
    val bodyParts = if (paste == null) texts.map { AgentContentPart.Text(it) } else listOf(AgentContentPart.Text(modelText))
    return PreparedQueuedTurn(userText, modelText, partsJson, bodyParts + mediaParts, attachments, paste)
}

internal suspend fun ChatViewModel.persistQueuedTurn(
    queued: List<QueuedPrompt>,
    prepared: PreparedQueuedTurn,
    sid: String,
    run: ActiveRun?,
): String? {
    val persist = suspend {
        persistPreparedQueueBatch(_promptQueue, queued, { ownsQueuedTurn(sid, run) }) {
            chatRepository.appendTurnRows(sid, listOf(PendingTurnRow("user", prepared.partsJson)))
                .singleOrNull()?.takeIf { it.sessionId == sid }?.id
        }
    }
    val userId = if (run == null) persist() else run.withPersistencePermit { persist() }
    return userId?.takeIf { ownsQueuedTurn(sid, run) }
}

internal fun ChatViewModel.consumeQueuedPaste(prepared: PreparedQueuedTurn) {
    prepared.paste?.let { paste -> _pastedTexts.update { it.filterNot { entry -> entry.id in paste.consumedIds } } }
}

/** Persist a combined queued user turn, then start the next run only for the original owner. */
internal suspend fun ChatViewModel.drainQueuedPrompts(
    provider: LLMProvider,
    systemPrompt: MemoryDeliveryPrompt?,
    fallbackProviders: List<ChatViewModel.FallbackCandidate>,
    fallbackStrategy: FallbackStrategy,
) {
    val run = ActiveRunContext.current()
    if (_promptQueue.value.isEmpty()) return
    val sid = queuedTurnSession(expectedSessionId = null, run = run) ?: return
    fun ownsDrain() = ownsQueuedTurn(sid, run)
    while (ownsDrain()) {
        val queued = _promptQueue.value.takeIf { it.isNotEmpty() } ?: return
        try {
            val prepared = prepareQueuedTurn(queued, sid, run) ?: return
            val userId = persistQueuedTurn(queued, prepared, sid, run)
            if (!ownsDrain()) return
            if (userId == null) {
                // A concurrent edit or claim won. Retry only against a changed queue.
                if (_promptQueue.value == queued) return
                continue
            }
            val queuedIds = queued.mapTo(hashSetOf()) { it.id }
            val bound = withContext(Dispatchers.Main.immediate) {
                if (!ownsDrain()) return@withContext false
                consumeQueuedPaste(prepared)
                _messages.update { current ->
                    trimLoadedWindow(current.filterNot { it.queuedPromptId in queuedIds } + prepared.bubble(userId))
                }
                notePersistedUiRow(userId, userId)
                appendBoundedHistory(prepared.message(userId))
                true
            }
            if (!bound || !ownsDrain()) return

            if (groupChatEnabled.value && !provider.model.isPureVideoGenerator) {
                runGroupChat(provider, closing = false)
            } else {
                val drainedPrompt = refreshRunPromptForInjectedTurn(systemPrompt, expectedSessionId = sid)
                if (!ownsDrain()) return
                runAgentLoop(
                    provider = provider, systemPrompt = drainedPrompt,
                    fallbackProviders = fallbackProviders, fallbackStrategy = fallbackStrategy,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(ChatViewModel.TAG, "Queued drain stopped: ${e.javaClass.simpleName}")
            withContext(Dispatchers.Main.immediate) {
                if (ownsDrain()) setInlineError(context.getString(R.string.error_empty_response_generic))
            }
            return
        }
    }
}
