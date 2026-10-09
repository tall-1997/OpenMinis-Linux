package com.openminis.app.harness.runtime

/**
 * [T-android-seam-extraction] 批次三接缝二：工具执行。
 *
 * 宿主把完整工具管线（schema 校验 / 门禁 / 执行 / 输出策略 / 演化钩子）藏在
 * 接缝后面；:harness 侧的循环、子代理 lane、恢复重放只按「调用 → 结果」的口径
 * 说话。UI 投影（工具块渲染）不在这道接缝上——那是 UiEventSink（接缝四）的
 * 职责；所以宿主适配器是**每轮一个实例**，创建时捕获该轮的 UI 上下文。
 *
 * [ToolOutcome] 是「一轮工具处理需要知道的一切」——比成败二字多：状态裁定要
 * timedOut，块投影要 toolTitle/browserURL/imageFilePath，结果部件要图像三元组。
 * 图像字节对 harness 是不透明载荷，只透传不解释。
 */
interface ToolExecutorPort {

    suspend fun execute(toolCallId: String, toolName: String, argsJson: String): ToolOutcome
}

/** 工具执行结果的接缝形状（轮次消费的完整字段面）。 */
data class ToolOutcome(
    val success: Boolean,
    val output: String,
    val toolTitle: String = "",
    val timedOut: Boolean = false,
    val pageURL: String? = null,
    val imageFilePath: String? = null,
    val imageData: ByteArray? = null,
    val imageMimeType: String? = null,
    val imageLinuxPath: String? = null,
)
