package com.openminis.app.provider.api

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMModel
import kotlinx.coroutines.flow.Flow

/** Stable provider protocol shared by every model adapter. */
interface ModelProvider {
    val id: String
    val capabilities: ProviderCapabilities

    suspend fun chat(
        request: ChatRequest,
        systemPrompt: String? = null,
    ): LLMResponse

    fun streamChat(request: ChatRequest): Flow<StreamEvent>

    suspend fun models(): List<LLMModel>

    suspend fun validateAuth(): Boolean = true

    suspend fun getToolDefinitions(): List<AgentToolDefinition> = emptyList()

    suspend fun getSupportedImageParts(): List<LLMMessage.ImagePart> = emptyList()
}
