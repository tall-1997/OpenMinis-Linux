package com.openminis.app.harness.runtime

/**
 * [T-android-seam-extraction] 批次三接缝二：工具执行。
 *
 * 宿主把完整工具管线（schema 校验 / 门禁 / 执行 / 输出策略 / 演化钩子）藏在
 * 接缝后面；:harness 侧的循环、子代理 lane、恢复重放只按「名字 + 参数 → 结果」
 * 的口径说话。UI 投影（工具块渲染）不在这道接缝上——那是 UiEventSink（接缝四）
 * 的职责；所以宿主适配器是**每轮一个实例**，创建时捕获该轮的 UI 上下文。
 */
interface ToolExecutorPort {

    suspend fun execute(toolName: String, argsJson: String): ToolOutcome
}

/** 工具执行结果的接缝形状：成败 + 文本输出（图像等宿主专有载荷不跨接缝）。 */
data class ToolOutcome(
    val success: Boolean,
    val output: String,
)
