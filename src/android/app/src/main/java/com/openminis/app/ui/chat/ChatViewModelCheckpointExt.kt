package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.checkpoint.CheckpointBridge
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.harness.checkpoint.RewindScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * [T-checkpoint-rewind] 「撤回到此轮」的用户入口逻辑。
 *
 * 与上游 `HarnessLoop.beginTurn` / `ChatViewModel.rewindToMessage` 同一语义：
 * 按 checkpoint 的 `anchorMessageId`（= 用户气泡 id，因为宿主 ChatMessage.id
 * 就是 DB 行 id，见 ChatViewModelSendExt 的持久化分支）定位轮次，
 * prepare/commit 两段式执行；CONVERSATION/BOTH 会派生回退分支并由调用方切换过去。
 */
internal object RewindEvents {
    // 进程级事件（对齐 InterceptFeedback 的做法）：一次 rewind 的提示与
    // 「撤销回滚」动作只在发起它的屏幕上消费，无需按会话分桶。
    private val _events = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<String> = _events

    fun emit(text: String) {
        _events.tryEmit(text)
    }
}

/**
 * 用户轮起点：以**最后一条用户消息**为锚开启本轮 checkpoint。
 * 在 `runAgentLoop` 入口调用，因此 send / retry / resume / drain / rerun
 * 五条进 loop 的路径自动覆盖，且每条路径拿到的都是「模型即将看到的对话尾」。
 */
internal fun ChatViewModel.beginCheckpointTurn() {
    val anchor = agentHistory.lastOrNull { it.role == LLMMessage.Role.USER }
    CheckpointBridge.beginTurn(
        context,
        activeSessionId,
        anchor?.content.orEmpty(),
        anchor?.dbMessageId,
    )
}

/**
 * 撤回到 [messageId] 所在用户轮。轮次定位失败（该轮没有写工具触碰，因而没有
 * checkpoint）时给出可解释的提示，而不是静默无反应。
 *
 * @param onForked CONVERSATION/BOTH 派生出的回退分支 id；由 UI 层负责切换过去
 *   （原会话保留不动，切回即可）。
 */
internal fun ChatViewModel.rewindToMessage(
    messageId: String,
    scope: RewindScope,
    onForked: (String) -> Unit = {},
) {
    if (_isStreaming.value) return
    val sid = activeSessionId
    viewModelScope.launch(Dispatchers.IO) {
        // 同一锚点可能有多个轮（用户重试过）：取最新的一个，它的轮初快照
        // 才是当前磁盘状态的正确回滚点。
        val turn = CheckpointBridge.checkpoints(context, sid)
            .lastOrNull { it.anchorMessageId == messageId }
        if (turn == null) {
            RewindEvents.emit(context.getString(R.string.chat_rewind_no_checkpoint))
            return@launch
        }
        runCatching {
            val controller = CheckpointBridge.controller(context, chatRepository)
            val plan = controller.prepare(sid, turn.turn, scope)
            // workspace 参数在宿主侧承载 sessionId —— 见 AppRewindFileAccess.withBase。
            val result = controller.commit(plan, workspace = sid)
            RewindEvents.emit(
                buildString {
                    append(context.getString(R.string.chat_rewind_done, result.filesRestored, result.filesDeleted))
                    if (result.forkedSessionId != null) {
                        append(" · ").append(context.getString(R.string.chat_rewind_switched))
                    }
                    result.note?.let { append("\n").append(it) }
                },
            )
            result.forkedSessionId?.let(onForked)
        }.onFailure { throwable ->
            RewindEvents.emit(
                context.getString(R.string.chat_rewind_failed, throwable.message ?: "unknown"),
            )
        }
    }
}

/** 撤销最近一次 rewind 的文件改动（对话侧切回原会话即可，不在 undo 范围）。 */
internal fun ChatViewModel.undoLastRewind() {
    val sid = activeSessionId
    viewModelScope.launch(Dispatchers.IO) {
        val result = runCatching {
            CheckpointBridge.controller(context, chatRepository).undoLastRewind(sid, workspace = sid)
        }.getOrNull()
        if (result == null) {
            RewindEvents.emit(context.getString(R.string.chat_rewind_no_undo))
            return@launch
        }
        RewindEvents.emit(
            buildString {
                append(context.getString(R.string.chat_rewind_undone, result.filesRestored, result.filesDeleted))
                result.note?.let { append("\n").append(it) }
            },
        )
    }
}
