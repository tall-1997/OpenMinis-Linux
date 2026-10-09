package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.isPureVideoGenerator
import com.openminis.app.provider.LLMProvider
import com.openminis.app.queue.PromptQueueBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drain queued prompts after an agent loop finishes. Each queued prompt is
 * appended to agentHistory, persisted, and re-runs the agent loop.
 * Mirrors iOS AIChatViewModel.drainQueuedPrompts().
 */
internal suspend fun ChatViewModel.drainQueuedPrompts(
    provider: LLMProvider,
    systemPrompt: String?,
    fallbackProviders: List<ChatViewModel.FallbackCandidate>,
    fallbackStrategy: com.openminis.app.data.model.FallbackStrategy,
) {
    while (_promptQueue.value.isNotEmpty()) {
        if (!materializeQueuedPromptsAsUserTurn()) break

        try {
            if (groupChatEnabled.value && !provider.model.isPureVideoGenerator) {
                runGroupChat(provider, closing = false)
            } else {
            runAgentLoop(
                provider = provider,
                systemPrompt = systemPrompt,
                fallbackProviders = fallbackProviders,
                fallbackStrategy = fallbackStrategy,
            )
            }
        } catch (e: CancellationException) {
            Log.d(ChatViewModel.TAG, "Agent loop (queued-drain) cancelled")
            // Cancel mid-drain: cancelStream() will check _promptQueue
            // and call resumeQueueAfterCancel() if anything's still pending,
            // so just propagate.
            throw e
        } catch (e: Exception) {
            Log.e(ChatViewModel.TAG, "Agent loop (queued-drain) error", e)
            if (com.openminis.app.service.ActiveRunContext.current()?.isStopped == false) {
                setInlineError(e.message ?: "Unknown error")
            }
            break
        }
    }
}

/**
 * [T-retry-stale-context] 把幽灵队列物化成持久用户轮（**不跑回合**）。
 *
 * 供两条路径复用：
 *  - [drainQueuedPrompts]：物化后继续跑 agent loop（原行为）；
 *  - [retryLast]：重试前先物化——用户在错误横幅期间发的消息若还卡在
 *    _promptQueue（错误出口此前不排空），它们不在 agentHistory 里，直接
 *    重试会拿到「上一次错误时」的上下文，用户消息被跳过。
 *
 * @return true = 物化了至少一条排队提示词。
 */
internal suspend fun ChatViewModel.materializeQueuedPromptsAsUserTurn(): Boolean {
    if (_promptQueue.value.isEmpty()) return false
    val queued = _promptQueue.value
    Log.i(ChatViewModel.TAG, "📨[DRAIN] Draining ${queued.size} queued prompt(s): " +
        queued.joinToString(", ") { "${it.id}=\"${it.text.take(20)}...\"" })

    // [T-queue-inject-ghost-bubble] 与 mid-loop 注入同口径：清队与气泡翻转都推迟
    // 到「确定要物化」之后——纯文档附件且 XML 为空等边缘组合会在这里早退，早退时
    // 队列/气泡/磁盘镜像三方原样保留（不再产生幽灵气泡与丢词）。
    // Build a combined user message (text + images from all queued prompts).
    // Persist as a single row.
    val sid = ensureSession()
    val combinedAttachments = queued.flatMap { it.attachments }
    val prepared = prepareUserAttachments(combinedAttachments, sid)

    // T132: same shape as sendMessage — caption(s) first, then for each
    // image emit "[attached image: <path>]" + ImageData, finally the
    // <user-attached-files> XML. Keeps caption adjacent to image and
    // lets the agent re-read the file via read_image.
    val combinedParts = mutableListOf<AgentContentPart>()
    val combinedText = StringBuilder()
    for (prompt in queued) {
        if (prompt.text.isNotEmpty()) {
            if (combinedText.isNotEmpty()) combinedText.append("\n\n")
            combinedText.append(prompt.text)
            combinedParts.add(AgentContentPart.Text(prompt.text))
        }
    }
    prepared.imageParts.forEachIndexed { idx, part ->
        val path = prepared.imageUploadPaths.getOrNull(idx)
        if (path != null) combinedParts.add(AgentContentPart.Text("[attached image: $path]"))
        combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
    }
    prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

    if (combinedParts.isEmpty() && prepared.mediaRefPartsJson == null && prepared.attachedFilesXml == null) {
        Log.w(ChatViewModel.TAG, "📨[DRAIN] queued prompts materialized to empty content — leaving queue untouched")
        return false
    }

    // —— 消费确定：清队（按 id 精确移除，不吃并发新入队）+ 气泡翻转 + 落盘 ——
    val queuedIds = queued.map { it.id }.toSet()
    _promptQueue.value = _promptQueue.value.filterNot { it.id in queuedIds }
    _messages.value = _messages.value.map { m ->
        if (m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)) {
            m.copy(isQueued = false, queuedPromptId = null)
        } else m
    }

    val userText = combinedText.toString()
    // [T-android-paste-mediaref] Same marker handling as the mid-loop
    // inject path above — see the note there for why queued prompts
    // need it at all.
    val drainPaste = buildPastedParts(userText, sid)
    if (drainPaste != null) {
        _pastedTexts.value =
            _pastedTexts.value.filterNot { it.id in drainPaste.consumedIds }
    }
    val userPartsJson = buildUserPartsJson(
        userText,
        prepared.mediaRefPartsJson,
        prepared.attachedFilesXml,
        bodyPartsJson = drainPaste?.partsJson,
    )
    val persistedUser = chatRepository.appendMessage(sid, "user", userPartsJson)
    // [T-queue-disk-persistence] drained prompts became real DB rows —
    // confirm their disk mirrors consumed (best-effort).
    runCatching {
        PromptQueueBridge.confirm(
            context.applicationContext,
            sid,
            queued.map { it.id },
        )
    }
    val queuedUiId = withContext(Dispatchers.Main.immediate) {
        _messages.value.firstOrNull { it.isQueued && it.content == userText }?.id
    }
    withContext(Dispatchers.Main.immediate) {
        notePersistedUiRow(queuedUiId, persistedUser.id)
    }

    appendBoundedHistory(LLMMessage(
        role = LLMMessage.Role.USER,
        content = drainPaste?.modelText ?: userText,
        imageParts = prepared.imageParts,
        contentParts = drainPaste?.let { p ->
            val bodyCount = combinedParts.takeWhile { it is AgentContentPart.Text }.size
            listOf(AgentContentPart.Text(p.modelText)) + combinedParts.drop(bodyCount)
        } ?: combinedParts,
        dbMessageId = persistedUser.id,
    ))
    return true
}
