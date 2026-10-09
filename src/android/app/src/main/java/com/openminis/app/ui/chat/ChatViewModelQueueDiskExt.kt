package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.queue.PromptQueueBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-queue-disk-persistence] 排队提示词磁盘镜像的宿主侧接线。
 *
 * ChatViewModel.kt 已在尺寸棘轮上限（6101 行），本特性所有对它的增量
 * 都收进这个 ext 文件：enqueue / 撤回 / 会话消亡的镜像操作 + 冷启动
 * 恢复 + 会话切换交接。磁盘格式与消费语义见 [PromptQueueBridge]。
 */
internal fun ChatViewModel.mirrorQueuedPromptToDisk(prompt: QueuedPrompt) {
    // [T-queue-mirror-session-binding] 在启动协程**之前**绑定归属会话。
    // activeSessionId 在协程体内读的是 dispatch 时刻的值——入队到派发之间
    // 有几 ms 窗口，落进来的会话切换（或草稿转正）会把这条排队提示词写进
    // 错误会话的镜像文件。
    val sid = activeSessionId
    // [T-queue-mirror-write-race] Best-effort 且保持 async——enqueuePrompt
    // 在主线程跑，为一次小文件写阻塞它（ANR 红线）正是当初做成
    // fire-and-forget 的原因——但写入 Job 由 bridge 记账、confirm 扫描前
    // join（见 PromptQueueBridge.launchMirrorWrite）：工具边界的确认在入队
    // 后几 ms 就到，也绝跑不赢它要消费的那条记录的落盘。
    val appContext = context.applicationContext
    PromptQueueBridge.launchMirrorWrite(appContext.filesDir, viewModelScope) {
        PromptQueueBridge.enqueue(appContext, sid, prompt)
    }
}

/** 撤回单条排队提示词时同步删掉磁盘镜像（用户点掉气泡 / 长按撤回）。 */
internal fun ChatViewModel.cancelQueuedPromptOnDisk(promptId: String) {
    // [T-queue-mirror-write-race] 与 enqueue 写同走 tracked launch，confirm
    // / drop 竞争时看到的是一致的台账。
    val appContext = context.applicationContext
    PromptQueueBridge.launchMirrorWrite(appContext.filesDir, viewModelScope) {
        PromptQueueBridge.cancel(appContext, promptId)
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
    val appContext = context.applicationContext
    PromptQueueBridge.launchMirrorWrite(appContext.filesDir, viewModelScope) {
        PromptQueueBridge.dropSession(appContext, sessionId)
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

/**
 * [T-queue-session-handoff] 会话切换交接：旧会话的内存排队提示词先落回
 * 它自己的镜像（cancel+enqueue 幂等对账——同 id 旧记录先删再写，正常路径
 * 不会双写；旧 build 落错会话的镜像也由 cancel 的全库扫描顺带归位），然后
 * 载入新会话的镜像。
 *
 * 调用约束：必须在宿主把会话状态（realSessionId / 消息列表）改写成新会话
 * **之前**调用——旧会话 id 默认取调用时刻的 [ChatViewModel.activeSessionId]；
 * 宿主若已完成切换，可显式传 [oldSessionId]。
 *
 * 当前架构一 VM 一会话（ChatViewModelStore 按 sessionId 缓存 VM，单个 VM
 * 内不存在会话切换），queue 侧没有可挂的切换钩子——宿主接线位置：在
 * loadSession 入口、任何会话状态改写之前，以新 sid 调用一次。其自带的
 * restoreQueuedPromptsFromDisk 调用会因内存队列已被本函数填好而非空守卫
 * 自动跳过，不会重复恢复。
 */
internal suspend fun ChatViewModel.onActiveSessionChanged(
    newSessionId: String,
    oldSessionId: String = activeSessionId,
) {
    val outgoing = _promptQueue.value
    if (outgoing.isNotEmpty()) {
        val appContext = context.applicationContext
        // 先排干在途镜像写：cancel 的全库扫描若跑在某条 enqueue 写之前，
        // 该写随后落地、又与本函数的 enqueue 各写一条同 id 记录（恢复时
        // 两个气泡）。
        PromptQueueBridge.awaitPendingWrites(appContext.filesDir)
        for (prompt in outgoing) {
            // cancel 先行：正常路径下镜像里已有同 id 记录（enqueue 时落盘、
            // 未消费），直接再 enqueue 会写出重复条目。
            PromptQueueBridge.cancel(appContext, prompt.id)
            PromptQueueBridge.enqueue(appContext, oldSessionId, prompt)
        }
        Log.i(
            ChatViewModel.TAG,
            "Handed off ${outgoing.size} queued prompt(s): $oldSessionId -> $newSessionId",
        )
    }
    // 清内存队列 + 占位气泡（同 clearQueuedPromptsEverywhere 的清法，但
    // 记录已重新落盘——不是作废，是换属主）。
    _promptQueue.value = emptyList()
    _messages.value = _messages.value.filterNot { it.isQueued }
    // 新会话镜像 → 内存 + 占位气泡。内存已清空，restore 的非空守卫放行。
    restoreQueuedPromptsFromDisk(newSessionId)
}
