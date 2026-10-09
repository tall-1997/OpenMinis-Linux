package com.openminis.app.provider

import com.openminis.app.harness.runtime.HarnessRoundRequest
import com.openminis.app.harness.runtime.ProviderStreamClient
import com.openminis.app.provider.api.ChatRequest
import kotlinx.coroutines.flow.Flow
import com.openminis.app.data.model.LLMStreamChunk

/**
 * [T-android-seam-extraction] [ProviderStreamClient] 的宿主实现：把接缝请求搬运成
 * provider.api.ChatRequest，chunk 流原样穿透（LLMStreamChunk 本就是 core:model 类型）。
 *
 * 存在的意义不是包装而是**解耦方向**：:harness 里的循环/压缩/恢复代码只依赖接缝
 * 接口，拿到的实例是本品（真 provider）还是测试假件由装配点决定。
 */
class LlmProviderStreamClient(private val provider: LLMProvider) : ProviderStreamClient {

    override suspend fun streamRound(request: HarnessRoundRequest): Flow<LLMStreamChunk> =
        provider.streamChat(
            ChatRequest(
                messages = request.messages,
                systemPrompt = request.systemPrompt,
                maxTokens = request.maxTokens,
                temperature = request.temperature,
                imageParts = request.imageParts,
                tools = request.tools,
                thinkingLevel = request.thinkingLevel,
            ),
        )
}
