package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-llm-middleware] Contract of the LLM request middleware chain:
 * chain ORDER, header TRANSFORM effectiveness, and the EMPTY-chain
 * passthrough. The context factory ([LlmRequestContext.of]) is exercised
 * through a real [OpenAIProvider] so the production shapes are pinned, not
 * a test double.
 */
class LlmMiddlewareTest {

    private fun context(extraHeaders: Map<String, String> = emptyMap()): LlmRequestContext =
        LlmRequestContext.of(
            provider = OpenAIProvider(apiKey = "test-key", model = LLMModel.gpt4oMini),
            messages = emptyList(),
            systemPrompt = "sys",
            maxTokens = 512,
            temperature = 0.7,
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
            extraHeaders = extraHeaders,
        )

    /** Middleware that records its position in the invocation order. */
    private class Recorder(
        private val log: MutableList<String>,
        private val name: String,
        private val mutate: ((MutableMap<String, String>) -> Unit)? = null,
    ) : LlmMiddleware {
        override fun onRequest(context: LlmRequestContext): LlmRequestContext {
            log.add(name)
            mutate?.invoke(context.headers)
            return context
        }
    }

    // -- chain order -------------------------------------------------------

    @Test
    fun `chain runs middlewares in list order`() {
        val log = mutableListOf<String>()
        val ctx = context()
        LlmMiddlewareChain.run(
            ctx,
            listOf(
                Recorder(log, "a"),
                Recorder(log, "b"),
                Recorder(log, "c"),
            ),
        )
        assertEquals(listOf("a", "b", "c"), log)
    }

    // -- passthrough -------------------------------------------------------

    @Test
    fun `empty chain passes context through untouched`() {
        val ctx = context(extraHeaders = mapOf("X-Call" to "1"))
        val out = LlmMiddlewareChain.run(ctx, emptyList())
        assertTrue(out === ctx)
        assertEquals(mapOf("X-Call" to "1"), out.headers)
    }

    // -- transform effectiveness ------------------------------------------

    @Test
    fun `middleware header transform is visible downstream`() {
        val ctx = context(extraHeaders = mapOf("X-Call" to "1"))
        val out = LlmMiddlewareChain.run(
            ctx,
            listOf(
                object : LlmMiddleware {
                    override fun onRequest(context: LlmRequestContext): LlmRequestContext =
                        context.withHeaders(context.headers + mapOf("X-Added" to "yes", "X-Call" to "2"))
                },
            ),
        )
        assertEquals("yes", out.headers["X-Added"])
        // Same-name REPLACE semantics — last write wins, mirroring the
        // historical per-call extraHeaders merge.
        assertEquals("2", out.headers["X-Call"])
    }

    @Test
    fun `later middleware sees earlier middleware's transform`() {
        val seen = mutableListOf<String?>()
        val ctx = context()
        LlmMiddlewareChain.run(
            ctx,
            listOf(
                object : LlmMiddleware {
                    override fun onRequest(context: LlmRequestContext): LlmRequestContext =
                        context.withHeaders(context.headers + mapOf("X-First" to "v"))
                },
                object : LlmMiddleware {
                    override fun onRequest(context: LlmRequestContext): LlmRequestContext {
                        seen.add(context.headers["X-First"])
                        return context
                    }
                },
            ),
        )
        assertEquals(listOf("v"), seen)
    }

    // -- built-ins ---------------------------------------------------------

    @Test
    fun `ExtraHeadersMiddleware seeds per-call headers into effective map`() {
        val ctx = context(extraHeaders = mapOf("X-Session" to "abc", "Authorization" to "Bearer t"))
        ctx.headers.clear() // simulate an earlier middleware wiping the map
        val out = ExtraHeadersMiddleware.onRequest(ctx)
        assertEquals("abc", out.headers["X-Session"])
        assertEquals("Bearer t", out.headers["Authorization"])
    }

    @Test
    fun `ExtraHeadersMiddleware overrides same-name entries from prior middlewares`() {
        val ctx = context(extraHeaders = mapOf("X-Call" to "caller"))
        val out = LlmMiddlewareChain.run(
            ctx,
            listOf(
                object : LlmMiddleware {
                    override fun onRequest(context: LlmRequestContext): LlmRequestContext =
                        context.withHeaders(context.headers + mapOf("X-Call" to "mw"))
                },
                ExtraHeadersMiddleware,
            ),
        )
        // The absorbed per-call step always wins — caller's map reached the
        // wire verbatim before the chain existed.
        assertEquals("caller", out.headers["X-Call"])
    }

    @Test
    fun `DebugLoggingMiddleware redacts header values`() {
        val redacted = DebugLoggingMiddleware.redactHeaders(mapOf("Authorization" to "Bearer secret", "X-Trace" to "1"))
        assertEquals("***", redacted["Authorization"])
        assertEquals("***", redacted["X-Trace"])
        assertEquals(setOf("Authorization", "X-Trace"), redacted.keys)
    }

    @Test
    fun `DebugLoggingMiddleware passes context through unchanged`() {
        val ctx = context(extraHeaders = mapOf("X-Call" to "1"))
        val out = DebugLoggingMiddleware.onRequest(ctx)
        assertTrue(out === ctx)
    }

    @Test
    fun `default chain is always ExtraHeaders first and debug adds logging second`() {
        val release = LlmMiddlewareRegistry.defaultChain(debug = false)
        assertEquals(1, release.size)
        assertEquals(ExtraHeadersMiddleware, release[0])

        val debug = LlmMiddlewareRegistry.defaultChain(debug = true)
        assertEquals(2, debug.size)
        assertEquals(ExtraHeadersMiddleware, debug[0])
        assertEquals(DebugLoggingMiddleware, debug[1])
    }

    // -- context factory ---------------------------------------------------

    @Test
    fun `context factory snapshots request identity`() {
        val provider = OpenAIProvider(
            apiKey = "k",
            model = LLMModel.gpt4oMini,
            basePath = "https://relay.example/v1",
            extraHeaders = mapOf("X-Ctor" to "1"),
        )
        val ctx = LlmRequestContext.of(
            provider,
            messages = emptyList(),
            systemPrompt = "abc",
            maxTokens = 128,
            temperature = null,
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.LOW,
            extraHeaders = mapOf("X-Call" to "2"),
        )
        // Directly-constructed provider: nothing bound → null type and base.
        assertNull(ctx.providerType)
        assertNull(ctx.baseUrl)
        assertEquals(LLMModel.gpt4oMini.id, ctx.modelId)
        assertEquals(0, ctx.body.messageCount)
        assertEquals(3, ctx.body.systemPromptLength)
        assertEquals(128, ctx.body.maxTokens)
        assertEquals(0, ctx.body.toolCount)
        assertEquals("LOW", ctx.body.thinkingLevel)
        assertEquals(mapOf("X-Call" to "2"), ctx.requestHeaders)
        // ctor extraHeaders do NOT leak into the per-call snapshot
        assertEquals(mapOf("X-Call" to "2"), ctx.headers)
    }

    @Test
    fun `factory-bound provider reports type and custom base`() {
        val instance = com.openminis.app.data.model.ProviderInstance(
            id = "mw-test",
            label = "mw-test",
            providerType = com.openminis.app.data.model.ProviderType.anthropic,
            credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
            customBaseURL = "https://relay.example",
        )
        val provider = ProviderFactory.create(
            instance, "key", LLMModel.claudeHaiku45,
        )
        try {
            val ctx = LlmRequestContext.of(
                provider,
                messages = emptyList(),
                systemPrompt = null,
                maxTokens = 64,
                temperature = null,
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
                extraHeaders = emptyMap(),
            )
            assertEquals("anthropic", ctx.providerType)
            // effectiveBaseURL appends /v1 per the instance default.
            assertEquals("https://relay.example/v1", ctx.baseUrl)
        } finally {
            ProviderFactory.invalidateAll()
        }
    }
}
