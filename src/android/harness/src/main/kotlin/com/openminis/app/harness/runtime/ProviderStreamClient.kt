package com.openminis.app.harness.runtime

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.flow.Flow

/**
 * [T-android-seam-extraction] 批次三接缝一：provider 流式客户端。
 *
 * P0-1 剩余（agent 循环 / 上下文治理迁出 :harness）与 2.4 compaction 本体都卡在
 * 同一面墙上：循环体直接攥着具体 provider 对象。本接缝把「发一轮请求、拿一条
 * _chunk 流」收窄成一个 suspend 函数，:harness 自此可以不认识任何 provider 实现。
 *
 * 事件类型直接复用 core:model 的 [LLMStreamChunk]（harness 的依赖白名单内含
 * core:model）——适配器因此几乎零映射：chunk 流原样穿透，只有**请求**方向需要
 * 一次字段搬运。思考级别用 core:model 的 [ThinkingLevel] 原样携带，不在接缝上
 * 做二次抽象。
 *
 * 消费方尚未切换（runAgentLoop 仍直连 provider）：接缝与适配器先落地并带契约
 * 测试，循环迁移是批次三的下一刀——不在同一刀里既换地基又换房子。
 */
interface ProviderStreamClient {

    suspend fun streamRound(request: HarnessRoundRequest): Flow<LLMStreamChunk>
}

/** 一轮请求的接缝形状：字段与 provider.api.ChatRequest 对齐，搬运即适配。 */
data class HarnessRoundRequest(
    val messages: List<LLMMessage>,
    val systemPrompt: String? = null,
    val maxTokens: Int,
    val temperature: Double? = null,
    val imageParts: List<LLMMessage.ImagePart> = emptyList(),
    val tools: List<AgentToolDefinition> = emptyList(),
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
)
