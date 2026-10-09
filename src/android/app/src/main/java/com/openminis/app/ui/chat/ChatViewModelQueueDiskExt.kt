package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.queue.PromptQueueBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-queue-disk-persistence] 排队提示词磁盘镜像的宿主侧接线。
 *
 * ChatViewModel.kt 已在尺寸棘轮上限（6101 行），本特性所有对它的增量
 * 都收进这个 ext 文件：enqueue / 撤回 / 会话消亡的镜像操作 + 冷启动
 * 恢复。磁盘格式与消费语义见 [PromptQueueBridge]。
 */
internal fun ChatViewModel.mirrorQueuedPromptToDisk(prompt: QueuedPrompt) {
    // Best-effort: the memory queue stays the runtime source of truth; the
    // disk copy only matters at the next loadSession. Ordering note:
    // consumption (confirm) can only happen at turn end (network
    // round-trip away), far past this launch's dispatch latency — the
    // enqueue-vs-confirm race is theoretical.
    viewModelScope.launch {
        PromptQueueBridge.enqueue(context.applicationContext, activeSessionId, prompt)
    }
}

/** 撤回单条排队提示词时同步删掉磁盘镜像（用户点掉气泡 / 长按撤回）。 */
internal fun ChatViewModel.cancelQueuedPromptOnDisk(promptId: String) {
    viewModelScope.launch {
        PromptQueueBridge.cancel(context.applicationContext, promptId)
    }
}

/**
 * 内存队列 + 磁盘镜像同步遗忘一条排队提示词。**不动占位气泡**——调用方对
 * 消息列表各有各的处置（重写 / 截断 / 整体清空），这里只保证「队列事实源
 * 与它的重启保险不会分叉」。
 *
 * 三个调用点：removeQueuedPrompt、retryFromMessage（T189 的 queued 气泡
 * 重试）、deleteFromMessage（截断范围内的 queued 气泡）。后两者在磁盘镜像
 * 落地前就存在，漏接的后果是重启后给已被用户重试/删除的消息还原出幽灵气泡。
 */
internal fun ChatViewModel.forgetQueuedPrompt(promptId: String) {
    _promptQueue.value = _promptQueue.value.filterNot { it.id == promptId }
    // [T-queue-disk-persistence] keep the disk mirror in step so a
    // restart doesn't resurrect a prompt the user explicitly removed.
    cancelQueuedPromptOnDisk(promptId)
}

/**
 * removeQueuedPrompt 的完整实现（从 ChatViewModel.kt 迁入以守尺寸棘轮）：
 * 内存队列 + 占位气泡 + 磁盘镜像三处同步删除。
 */
internal fun ChatViewModel.removeQueuedPromptWithDiskMirror(promptId: String) {
    _messages.value = _messages.value.filterNot { it.queuedPromptId == promptId }
    forgetQueuedPrompt(promptId)
}

/**
 * 队列整体作废：内存队列 + 占位气泡 + 该会话的磁盘镜像一起清。
 * 用于「排队提示词的归属轮次已经不存在了」的场合（群聊收尾——无论摘要
 * 成功还是失败，排队的那些跟进都不再有任何可注入的轮次）。
 */
internal fun ChatViewModel.clearQueuedPromptsEverywhere() {
    _promptQueue.value = emptyList()
    _messages.value = _messages.value.filterNot { it.isQueued }
    dropQueuedPromptsOnDisk(activeSessionId)
}

/**
 * withdrawQueuedMessage 的完整实现（从 ChatViewModel.kt 迁入以守尺寸棘轮）：
 * 按 UI 气泡 id 反查排队提示词并撤回。
 */
internal fun ChatViewModel.withdrawQueuedMessageWithDiskMirror(messageId: String) {
    val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
    if (!msg.isQueued) return
    val pid = msg.queuedPromptId ?: return
    _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
    _messages.value = _messages.value.filterNot { it.id == messageId }
    // [T-queue-disk-persistence] withdraw = remove, same disk step.
    cancelQueuedPromptOnDisk(pid)
    Log.i(ChatViewModel.TAG, "Withdrew queued message, queue=${_promptQueue.value.size}")
}

/** clearChat：会话内容清空，排队提示词的磁盘镜像随之消亡。 */
internal fun ChatViewModel.dropQueuedPromptsOnDisk(sessionId: String) {
    viewModelScope.launch {
        PromptQueueBridge.dropSession(context.applicationContext, sessionId)
    }
}

/**
 * loadSession 冷启动恢复：磁盘上未消费的排队提示词重建进内存队列 +
 * 占位气泡。占位气泡用与 enqueuePrompt 完全一致的 id 公式
 * （"queued_msg_<pid>"）和形状，撤回路径无需特判。
 *
 * 仅在内存队列为空（新 VM）时执行——同一 VM 内重载会话不得复制仍在
 * 排队的提示词。挂到 Main.immediate 是因为 _messages 的其余写入都在
 * 主线程（StateFlow 读者在 Compose 侧）。
 */
internal suspend fun ChatViewModel.restoreQueuedPromptsFromDisk(sessionId: String) {
    if (_promptQueue.value.isNotEmpty()) return
    val restored = PromptQueueBridge.restore(context.applicationContext, sessionId)
    if (restored.isEmpty()) return
    _promptQueue.value = restored
    withContext(Dispatchers.Main.immediate) {
        _messages.value = trimLoadedWindow(
            _messages.value + restored.map { p ->
                ChatMessage(
                    id = "queued_msg_${p.id}",
                    role = "user",
                    content = p.text,
                    imageUris = p.attachments.filter { it.isImage }.map { it.uri },
                    attachmentNames = p.attachments.map { it.fileName },
                    attachmentUris = p.attachments.filterNot { it.isImage }.map { it.uri },
                    isQueued = true,
                    queuedPromptId = p.id,
                )
            },
        )
    }
    Log.i(ChatViewModel.TAG, "Restored ${restored.size} queued prompt(s) from disk for $sessionId")
}
