package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-openai-assistant-empty-content]
 *
 * A tool-calls-only assistant turn used to serialize as
 * `{role:"assistant", tool_calls:[...]}` with NO `content` key. OpenAI itself
 * tolerates the omission, but several OpenAI-compatible gateways (strict
 * schema validation on relays / vendor re-implementations) reject the message
 * with a 400 because the field is required on every assistant message. The
 * documented no-text shape is `"content": ""`, so the builder now always
 * emits the key.
 *
 * What is asserted is the request BODY (the internal buildRequestBody), not a
 * mocked HTTP exchange — the defect is pure JSON construction.
 */
class OpenAIAssistantEmptyContentTest {

    private val model = LLMModel(
        id = "compat-model",
        displayName = "Compat Model",
        provider = "test",
        inputModalities = listOf("text"),
    )

    private fun provider() = OpenAIProvider(
        apiKey = "test-key",
        model = model,
        basePath = "https://example.invalid/v1",
    )

    /** A turn where the assistant answered with ONLY a tool call (no text). */
    private fun toolCallsOnlyHistory() = listOf(
        LLMMessage(LLMMessage.Role.USER, "list the files"),
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolUse(
                    id = "call_1",
                    name = "shell",
                    input = JSONObject("""{"command": "ls"}"""),
                ),
            ),
        ),
    )

    private fun bodyOf(messages: List<LLMMessage>): JSONObject =
        provider().buildRequestBody(
            messages = messages,
            systemPrompt = null,
            maxTokens = 1024,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
        )

    private fun assistantMessage(body: JSONObject): JSONObject {
        val msgs = body.getJSONArray("messages")
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            if (m.optString("role") == "assistant") return m
        }
        throw AssertionError("no assistant message in request")
    }

    @Test
    fun `tool-calls-only assistant turn carries an empty content string`() {
        val assistant = assistantMessage(bodyOf(toolCallsOnlyHistory()))
        assertTrue(
            "content key must be PRESENT (field-presence gateways 400 on omission)",
            assistant.has("content"),
        )
        assertEquals("", assistant.getString("content"))
        assertTrue(assistant.has("tool_calls"))
    }

    @Test
    fun `assistant text still rides content when both text and tool_calls exist`() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "go"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.Text("running ls"),
                    AgentContentPart.ToolUse(
                        id = "call_2",
                        name = "shell",
                        input = JSONObject("""{"command": "ls"}"""),
                    ),
                ),
            ),
        )
        val assistant = assistantMessage(bodyOf(history))
        assertEquals("running ls", assistant.getString("content"))
        assertTrue(assistant.has("tool_calls"))
    }

    @Test
    fun `plain assistant text turn keeps its content`() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "hi"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "hello"),
        )
        val assistant = assistantMessage(bodyOf(history))
        assertEquals("hello", assistant.getString("content"))
    }
}
