package com.openminis.app.harness

import com.openminis.app.harness.model.ToolImageRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Adapted from taixu HarnessMessage (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 序列化格式即持久化格式，同时由 Agent loop 转换为 LLM 请求/响应格式。
 * 纯 JVM：不引任何平台类型，图片引用只带路径不携带二进制。
 */

/** Harness 基础工具，与 LLM tool-calling 协议对齐。 */
@Serializable
enum class HarnessTool {
    @SerialName("read") READ,
    @SerialName("write") WRITE,
    @SerialName("edit") EDIT,
    @SerialName("base") BASE,
    @SerialName("process") PROCESS,
    @SerialName("host") HOST,
    @SerialName("download") DOWNLOAD,
    @SerialName("memory") MEMORY,
    @SerialName("plan") PLAN,
    @SerialName("scratchpad") SCRATCHPAD,
    @SerialName("history_search") HISTORY_SEARCH,
    @SerialName("history_read") HISTORY_READ,
    @SerialName("build_script") BUILD_SCRIPT,
    @SerialName("invoke_subagent") SUBAGENT,
    @SerialName("mcp") MCP,
    @SerialName("load_rule") LOAD_RULE,
    @SerialName("load_skill") LOAD_SKILL,
    @SerialName("compress") COMPRESS,
    @SerialName("ask_user") ASK_USER,
    @SerialName("render_surface") RENDER_SURFACE,
}

@Serializable
sealed interface HarnessMessage {
    val id: String
    val createdAt: Long
}

@Serializable
@SerialName("skill_suggestion")
data class SkillSuggestion(
    override val id: String,
    override val createdAt: Long,
    /** create = 沉淀为新技能；update = 修复既有技能的指导规则 */
    val action: String,
    val skillName: String,
    val description: String,
    val systemPrompt: String,
    val triggerCommand: String? = null,
    /** action=update 时的目标技能 id */
    val targetSkillId: String? = null,
    val reason: String = "",
    /** pending = 待处理；applied / dismissed 由 UI 内存态过滤（转写里保留原样） */
    val status: String = "pending",
) : HarnessMessage

/** UI-only capability activation event. It is persisted for the transcript but never sent to the model. */
@Serializable
@SerialName("capability_event")
data class CapabilityEvent(
    override val id: String,
    override val createdAt: Long,
    val kind: Kind,
    val name: String,
    val details: String = "",
) : HarnessMessage {
    @Serializable
    enum class Kind { SKILL, MCP }
}

/** UI-only model switch record. Persisted in the transcript, never sent to the provider. */
@Serializable
@SerialName("model_switch")
data class ModelSwitchEvent(
    override val id: String,
    override val createdAt: Long,
    val fromLabel: String = "",
    val toLabel: String,
    val fromContextTokens: Int? = null,
    val toContextTokens: Int,
    val compacted: Boolean = false,
    val foldedMessageCount: Int = 0,
    val compactionPending: Boolean = false,
) : HarnessMessage

@Serializable
@SerialName("user")
data class UserMessage(
    override val id: String,
    override val createdAt: Long,
    val text: String,
    val imageUrls: List<String> = emptyList(),
) : HarnessMessage

@Serializable
@SerialName("assistant")
data class AssistantText(
    override val id: String,
    override val createdAt: Long,
    val text: String,
    /** 推理模型（如 DeepSeek-R1）返回的 reasoning_content，多轮时需原样传回 API。 */
    val reasoning: String? = null,
    /**
     * 本轮执行总耗时（从用户发送到该条最终回复产生，毫秒）。
     * 仅在作为本轮收尾消息（最终回复 / 中断提示）时记录；旧数据无此字段，默认 null。
     */
    val totalMs: Long? = null,
    val modelId: String? = null,
    val providerId: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cachedTokens: Int? = null,
    /**
     * 模型思考（reasoning）流的确切耗时（毫秒，从首个 reasoning 增量到 reasoning 结束）。
     * 用于前端展示「已深度思考 N.N 秒」；旧数据无此字段，默认 null。
     */
    val reasoningMs: Long? = null,
) : HarnessMessage

@Serializable
@SerialName("tool_call")
data class ToolCall(
    override val id: String,
    override val createdAt: Long,
    val tool: HarnessTool,
    val args: JsonObject,
    /** 触发本次调用的 assistant 轮次的推理内容，多轮时需原样传回 API。 */
    val reasoning: String? = null,
    /** 原始工具名称（用于 MCP 动态工具或子智能体识别） */
    val rawToolName: String? = null,
) : HarnessMessage

@Serializable
@SerialName("tool_result")
data class ToolResult(
    override val id: String,
    override val createdAt: Long,
    val toolCallId: String,
    val success: Boolean,
    val output: String,
    /** 该工具调用实际执行耗时（毫秒，不含排队与 LLM 时间）。旧数据无此字段，默认 null。 */
    val durationMs: Long? = null,
    /** Host approval gate paused this tool call; the model loop must wait for the user. */
    val awaitingApproval: Boolean = false,
    val approvalRequestId: String? = null,
    /**
     * 该调用需要审批，但执行环境无法承载审批暂停（子智能体后台 Lane），因此未执行。
     * 与 [awaitingApproval] 的区别：这里没有待审批请求可供用户批准，必须由主智能体重新发起。
     */
    val approvalDeferred: Boolean = false,
    /**
     * 工具产物中的图片附件引用列表（如截图工具落盘的 PNG）。
     * 持久化兼容：旧数据无此字段；序列化默认空数组。
     */
    val imageAttachments: List<ToolImageRef> = emptyList(),
    /**
     * 工具结果的结构化元数据（不进入模型上下文）。当前用于 edit 工具：
     * metadata["diff"] 携带 Unified Diff，供前端 DiffView 渲染对比视图。
     * 持久化兼容：旧数据无此字段；序列化默认空表。
     */
    val metadata: Map<String, String> = emptyMap(),
    /**
     * 沙箱图片多模态直通载荷：`data:image/png;base64,...`。
     * read 工具识别到图片后缀时写入，由投影层在 visionEnabled 时追加为图片消息
     * 发给多模态模型；非视觉模型会被忽略。旧数据默认 null。
     */
    val imageDataUrl: String? = null,
) : HarnessMessage
