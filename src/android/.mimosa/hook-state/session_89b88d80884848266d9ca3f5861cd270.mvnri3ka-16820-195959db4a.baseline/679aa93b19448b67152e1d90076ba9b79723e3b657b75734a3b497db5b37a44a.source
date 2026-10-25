package com.openminis.app.provider

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class LLMProviderGateFlowTest {

    private class Stub : LLMProvider {
        override val name = "stub"
        override var model = LLMModel.gpt4oMini
        override val callGateKey =
            ProviderKeyGate.key("https://relay.example/v1", "sk-flow", "flash")

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): LLMResponse = error("unused")

        override fun streamMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
            systemStablePrefixLen: Int,
        ): Flow<LLMStreamChunk> = flow {
            emit(LLMStreamChunk.Text("hi"))
            emit(LLMStreamChunk.Finished("stop"))
        }
    }

    @Test
    fun `gated streamMessage emit does not violate flow invariant`() = runBlocking {
        val chunks = withContext(Dispatchers.IO) {
            Stub().streamMessage(
                listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
                null,
                1024,
            ).toList()
        }
        assertEquals(
            listOf(LLMStreamChunk.Text("hi"), LLMStreamChunk.Finished("stop")),
            chunks,
        )
    }
}
