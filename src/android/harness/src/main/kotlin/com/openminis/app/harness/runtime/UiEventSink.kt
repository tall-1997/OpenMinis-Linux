package com.openminis.app.harness.runtime

/**
 * [T-android-seam-extraction] 批次三接缝四：轮次事件的宿主反应面。
 *
 * 循环片段迁进 :harness 后，UI 投影（AssistantBlock 状态机）不能跟着走——那是
 * Compose/Android 的地盘。本接缝把「循环里发生了什么」以窄事件流交给宿主，
 * 宿主决定块怎么渲染、消息怎么刷新。事件面随循环迁移逐步扩：本刀只落
 * [onToolCallRejected]（preflight 拒绝与截断拒绝两条路径的公共形状），
 * started/finished 在工具轮本体迁移时再进。
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
}
