package com.openminis.app.harness.runtime

/**
 * [T-android-seam-extraction] 批次三接缝四：轮次事件的宿主反应面。
 *
 * 循环片段迁进 :harness 后，UI 投影（AssistantBlock 状态机）不能跟着走——那是
 * Compose/Android 的地盘。本接缝把「循环里发生了什么」以窄事件流交给宿主，
 * 宿主决定块怎么渲染、消息怎么刷新、通知怎么收。
 *
 * 宿主实现是**每轮一个实例**（捕获该轮的块列表 / assistantId / 当前文本），
 * 与 [ToolExecutorPort] 同款过渡形态——轮次上下文由捕获参数过渡，等循环本体
 * 迁完由轮次对象统一持有。
 */
interface UiEventSink {

    /**
     * 工具调用在执行前被拒（参数校验失败 / 流被截断）。宿主应把对应块置为
     * FAILED 并立即刷新——静默的成功外观正是 #119 报的 bug。
     */
    suspend fun onToolCallRejected(toolCallId: String, uiMessage: String)

    /** 工具调用执行完毕（成败/超时/取消）。宿主更新块状态、清通知进度条。 */
    suspend fun onToolCallFinished(event: ToolFinish)
}

/** 工具轮终局的状态口径（块投影与通知图标的共同上游）。 */
enum class RoundStatus { SUCCESS, TIMEOUT, FAILED, CANCELLED }

/**
 * 一次工具调用的完成事件。durationMs 不在事件里——宿主从块的 startTimeMs
 * 自算（块是宿主的表，起点在宿主手上）。
 */
data class ToolFinish(
    val toolCallId: String,
    val status: RoundStatus,
    val content: String,
    val toolTitle: String = "",
    val browserURL: String? = null,
    val imageFilePath: String? = null,
)
