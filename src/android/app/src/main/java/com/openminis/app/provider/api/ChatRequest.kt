package com.openminis.app.provider.api

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel

data class ChatRequest(
    val messages: List<LLMMessage>,
    val systemPrompt: String? = null,
    val maxTokens: Int,
    val temperature: Double? = null,
    val imageParts: List<LLMMessage.ImagePart> = emptyList(),
    val tools: List<AgentToolDefinition> = emptyList(),
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
)
