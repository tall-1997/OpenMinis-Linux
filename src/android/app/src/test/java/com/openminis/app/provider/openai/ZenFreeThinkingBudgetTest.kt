package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [T-zen-free-thinking-budget] End-to-end tests for the Zen free lane
 * thinking budget. Uses the real `buildRequestBody` → sendMessageClamped
 * path through MockWebServer, the same way ThinkingRulesRegressionTest
 * exercises `injectThinkingParams`.
 *
 * CONTRACT (from the desktop reference implementation, opencode2dsh):
 * the Zen free lane's gateway ignores every effort field and enforces
 * ONLY max_completion_tokens. So a thinking level maps to a real
 * generation budget that replaces the caller's maxTokens. A model whose
 * thinking cannot be switched off has its rungs doubled.
 *
 * REGRESSION: a user's paid Zen provider (apiKey != "public") must NOT
 * receive this budget treatment — it keeps the normal reasoning_effort
 * path through injectThinkingParams.
 */
class ZenFreeThinkingBudgetTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun model(
        id: String,
        supportsReasoning: Boolean? = true,
        maxOutputTokens: Int? = 32768,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = "OpenCode Zen",
        contextWindow = 128000,
        maxOutputTokens = maxOutputTokens,
        supportsReasoning = supportsReasoning,
    )

    private fun plainHistory(): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "hi"),
    )

    /**
     * Drive the real provider on the Zen free path and return the serialized
     * outbound body. `apiKey = "public"` + the Zen basePath substring triggers
     * `isZenFree` on the production OpenAIProvider, so `buildRequestBody`
     * takes the budget branch instead of `injectThinkingParams`.
     */
    private fun capture(
        model: LLMModel,
        level: ThinkingLevel,
        maxTokens: Int = 4096,
    ): JSONObject {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(ok),
            )
        }
        // apiKey="public" + basePath containing "opencode.ai/zen" triggers isZenFree
        val basePath = server.url("/opencode.ai/zen/v1").toString().trimEnd('/')
        val provider = OpenAIProvider(apiKey = "public", model = model, basePath = basePath)
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = plainHistory(),
                    systemPrompt = null,
                    maxTokens = maxTokens,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = level,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    // -- non-reasoning model: budget path not taken

    @Test
    fun `non-reasoning model keeps caller maxTokens`() {
        val body = capture(
            model = model("big-pickle", supportsReasoning = false),
            level = ThinkingLevel.MEDIUM,
            maxTokens = 4096,
        )
        // isZenFree is true, but supportsReasoning is false →
        // zenBudgetTokens returns null → body keeps the original 4096
        assertEquals(4096, body.optInt("max_completion_tokens"))
        // Normal reasoning_effort MUST NOT appear on the free-lane body
        assertEquals(false, body.has("reasoning_effort"))
    }

    // -- reasoning model: budget replaces caller maxTokens

    @Test
    fun `off → 2048`() {
        val body = capture(model("big-pickle"), ThinkingLevel.OFF, 4096)
        assertEquals(2048, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `low → 2048`() {
        val body = capture(model("big-pickle"), ThinkingLevel.LOW, 4096)
        assertEquals(2048, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `medium → 8192`() {
        val body = capture(model("big-pickle"), ThinkingLevel.MEDIUM, 4096)
        assertEquals(8192, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `high → 16384`() {
        val body = capture(model("big-pickle"), ThinkingLevel.HIGH, 4096)
        assertEquals(16384, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `xhigh → model capacity`() {
        val body = capture(
            model("big-pickle", maxOutputTokens = 32768),
            ThinkingLevel.XHIGH,
            4096,
        )
        assertEquals(32768, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `max → model capacity`() {
        val body = capture(
            model("big-pickle", maxOutputTokens = 65536),
            ThinkingLevel.MAX,
            4096,
        )
        assertEquals(65536, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `ultra → model capacity`() {
        val body = capture(
            model("big-pickle", maxOutputTokens = 131072),
            ThinkingLevel.ULTRA,
            4096,
        )
        assertEquals(131072, body.optInt("max_completion_tokens"))
    }

    // -- must-think model (mimo-v2.6): doubled rungs

    @Test
    fun `mimo-v2p6 off → 4096 doubled`() {
        val body = capture(
            model("mimo-v2.6-flash-free", maxOutputTokens = 32768),
            ThinkingLevel.OFF,
            4096,
        )
        assertEquals(4096, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `mimo-v2p6 medium → 16384 doubled`() {
        val body = capture(
            model("mimo-v2.6-flash-free", maxOutputTokens = 32768),
            ThinkingLevel.MEDIUM,
            4096,
        )
        assertEquals(16384, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `mimo-v2p6 high capped at capacity`() {
        val body = capture(
            model("mimo-v2.6-flash-free", maxOutputTokens = 32768),
            ThinkingLevel.HIGH,
            4096,
        )
        assertEquals(32768, body.optInt("max_completion_tokens"))
    }

    // -- must-think model (mimo-v2.5): doubled rungs

    @Test
    fun `mimo-v2p5 off → 4096 doubled`() {
        val body = capture(
            model("mimo-v2.5-free", maxOutputTokens = 32768),
            ThinkingLevel.OFF,
            4096,
        )
        assertEquals(4096, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `mimo-v2p5 medium → 16384 doubled`() {
        val body = capture(
            model("mimo-v2.5-free", maxOutputTokens = 32768),
            ThinkingLevel.MEDIUM,
            4096,
        )
        assertEquals(16384, body.optInt("max_completion_tokens"))
    }

    // -- budget clipped to model capacity

    @Test
    fun `budget clipped to low model capacity`() {
        val body = capture(
            model("big-pickle", maxOutputTokens = 1500),
            ThinkingLevel.MEDIUM,
            8192,
        )
        assertEquals(1500, body.optInt("max_completion_tokens"))
    }

    // -- minimum floor

    @Test
    fun `floor never below 512`() {
        val body = capture(
            model("big-pickle", maxOutputTokens = 200),
            ThinkingLevel.OFF,
            200,
        )
        assertEquals(512, body.optInt("max_completion_tokens"))
    }

    // -- regression: paid Zen provider must NOT take the budget path

    @Test
    fun `paid provider keeps reasoning_effort path`() {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok)) }
        // apiKey is a real key, not "public" → isZenFree = false
        val basePath = server.url("/opencode.ai/zen/v1").toString().trimEnd('/')
        val provider = OpenAIProvider(apiKey = "sk-real-key", model = model("big-pickle"), basePath = basePath)
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = plainHistory(),
                    systemPrompt = null,
                    maxTokens = 4096,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = ThinkingLevel.MEDIUM,
                )
            }
        }
        val body = JSONObject(server.takeRequest().body.readUtf8())
        // Paid provider: the caller's maxTokens stays unchanged
        assertEquals(4096, body.optInt("max_completion_tokens"))
        // And reasoning_effort IS present (normal ThinkingRuleResolver path)
        assertEquals("medium", body.optString("reasoning_effort"))
    }

    // -- regression: non-Zen basePath must NOT take the budget path

    @Test
    fun `non-zen basepath keeps normal path`() {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok)) }
        // apiKey="public" but basePath is api.openai.com → isZenFree = false
        val provider = OpenAIProvider(apiKey = "public", model = model("big-pickle"), basePath = server.url("/v1").toString().trimEnd('/'))
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = plainHistory(),
                    systemPrompt = null,
                    maxTokens = 4096,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = ThinkingLevel.MEDIUM,
                )
            }
        }
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(4096, body.optInt("max_completion_tokens"))
        assertEquals("medium", body.optString("reasoning_effort"))
    }

    // -- reasoning_effort must not appear on the free-lane body

    @Test
    fun `free lane body contains no reasoning_effort`() {
        for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA)) {
            val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
            repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok)) }
            val basePath = server.url("/opencode.ai/zen/v1").toString().trimEnd('/')
            val provider = OpenAIProvider(apiKey = "public", model = model("big-pickle"), basePath = basePath)
            runCatching {
                runBlocking {
                    provider.sendMessageClamped(
                        messages = plainHistory(),
                        systemPrompt = null,
                        maxTokens = 4096,
                        temperature = null,
                        imageParts = emptyList(),
                        tools = emptyList(),
                        thinkingLevel = level,
                    )
                }
            }
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(
                "reasoning_effort must NOT appear on the Zen free lane body at level=$level",
                false,
                body.has("reasoning_effort"),
            )
        }
    }
}