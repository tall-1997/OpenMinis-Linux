package com.openminis.app.provider.api

import com.openminis.app.provider.LLMProvider
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.flow.Flow

/** Compatibility adapter while existing callers migrate from LLMProvider. */
class LegacyProviderAdapter(private val legacy: LLMProvider) : ModelProvider {
    override val id: String get() = legacy.name
    override val capabilities = ProviderCapabilities(
        streaming = true,
        images = true,
        tools = true,
        thinking = true,
        models = false,
        video = false,
    )
    override suspend fun chat(request: ChatRequest, systemPrompt: String?): LLMResponse =
        legacy.sendMessage(
            request.messages,
            systemPrompt ?: request.systemPrompt,
            request.maxTokens,
            request.temperature,
            request.imageParts,
            request.tools,
            request.thinkingLevel,
        )
    override fun streamChat(request: ChatRequest): Flow<LLMStreamChunk> =
        legacy.streamMessage(
            request.messages,
            request.systemPrompt,
            request.maxTokens,
            request.temperature,
            request.imageParts,
            request.tools,
            request.thinkingLevel,
        )
    override suspend fun models(): List<com.openminis.app.data.model.LLMModel> = listOf(legacy.model)
}
