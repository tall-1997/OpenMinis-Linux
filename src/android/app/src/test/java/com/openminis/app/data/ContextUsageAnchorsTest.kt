package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.api.ChatRequest
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.ui.chat.buildTurnTokenUsageJson
import com.openminis.app.ui.chat.readContextUsageAnchor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ContextUsageAnchorsTest {
    private val model = ModelAttributionSnapshot(
        "test-model", "Test", "openAI", UUID.randomUUID().toString(), contextRouteId = "01".repeat(32),
    )
    private val tools = listOf(
        AgentToolDefinition("find", "Find an item", mapOf("query" to AgentToolParam("string", "Query text"))),
    )

    private fun request(vararg messages: LLMMessage) = ChatRequest(
        messages.toList(), systemPrompt = "Use the listed tools", tools = tools, maxTokens = 100,
    )

    private fun user(text: String) = LLMMessage(LLMMessage.Role.USER, text)
    private fun assistant(text: String) = LLMMessage(
        LLMMessage.Role.ASSISTANT, text, contentParts = listOf(AgentContentPart.Text(text)),
    )

    @Test
    fun `reported input does not double count caches or include generated tokens`() {
        val usage = LLMUsage(11, 999, cacheCreationInputTokens = 13, cacheReadInputTokens = 17, latestContextTokens = 55)
        assertEquals(41, reportedRequestInputTokens(usage, anthropic = true))
        assertEquals(55, reportedRequestInputTokens(usage, anthropic = false))
        val request = request(user("prefix"))
        for (provider in listOf("anthropic", "openAI", "openAIResponses", "gemini")) {
            val actualModel = model.copy(providerTypeRaw = provider)
            val attempt = captureContextUsageAttempt(request, actualModel, null)
            assertEquals(if (provider == "anthropic") 41 else 55, settleContextUsageAttempt(attempt, usage)?.inputTokens)
        }
        assertEquals(17, reportedRequestInputTokens(LLMUsage(0, 99, cacheReadInputTokens = 17), anthropic = true))
        assertNull(reportedRequestInputTokens(usage.copy(latestContextTokens = 0), anthropic = false))
        assertNull(reportedRequestInputTokens(LLMUsage(inputTokens = 60, outputTokens = 5), anthropic = false))
        assertNull(reportedRequestInputTokens(usage.copy(cacheReadInputTokens = -1), anthropic = true))
        assertNull(reportedRequestInputTokens(usage.copy(inputTokens = Int.MAX_VALUE), anthropic = true))
        assertNull(reportedRequestInputTokens(null, anthropic = false))
    }

    @Test
    fun `only a successful settlement calibrates the exact raw request for that provider instance`() {
        val failed = request(user("A".repeat(500)))
        captureContextUsageAttempt(failed, model, null)
        val successful = request(user("longer prompt"))
        val raw = ContextTokenMeter.rawRequestTokens(successful)
        val attempt = captureContextUsageAttempt(successful, model, null, rawRequestTokens = raw)
        assertEquals(raw, estimateProjectedContext(successful, model, null).tokens)
        assertNull(settleContextUsageAttempt(attempt, LLMUsage(0, 12)))
        assertEquals(raw, estimateProjectedContext(successful, model, null).tokens)
        val actual = raw * 4
        val anchor = checkNotNull(settleContextUsageAttempt(attempt, LLMUsage(0, 12, latestContextTokens = actual)))
        assertEquals(actual, anchor.inputTokens)
        assertEquals((raw * 1.9).toInt(), estimateProjectedContext(successful, model, null).tokens)
        assertEquals(raw, estimateProjectedContext(successful, model.copy(providerInstanceId = "other"), null).tokens)
        assertNull(settleContextUsageAttempt(captureContextUsageAttempt(successful, model.copy(providerInstanceId = null), null), LLMUsage(1, 0, latestContextTokens = 1)))
    }

    @Test
    fun `measured input plus calibrated suffix counts the producing assistant exactly once`() {
        val prefix = request(user("prefix"))
        val raw = ContextTokenMeter.rawRequestTokens(prefix)
        val anchor = checkNotNull(settleContextUsageAttempt(
            captureContextUsageAttempt(prefix, model, "marker"), LLMUsage(0, 666, latestContextTokens = raw * 4),
        ))
        val produced = assistant("A".repeat(40)).copy(contextUsageAnchor = anchor)
        val continuation = user("B".repeat(20))
        val estimate = estimateProjectedContext(prefix.copy(messages = prefix.messages + produced + continuation), model, "marker")
        val rawSuffix = ContextTokenMeter.rawMessagesTokens(listOf(produced, continuation))
        assertEquals(15, rawSuffix) // Mirror text in `content` is not counted again.
        assertEquals(anchor.inputTokens, estimate.measuredPrefixTokens)
        assertEquals(anchor.inputTokens + (rawSuffix * 1.9).toInt(), estimate.tokens)
    }

    @Test
    fun `rewrites marker changes or different provider instances use a full estimate`() {
        val prefix = request(user("prefix"))
        val anchor = checkNotNull(settleContextUsageAttempt(
            captureContextUsageAttempt(prefix, model, "original-marker"), LLMUsage(0, 0, latestContextTokens = 500),
        ))
        val reply = assistant("reply").copy(contextUsageAnchor = anchor)
        val next = prefix.copy(messages = prefix.messages + reply + user("tail"))
        assertNotNull(estimateProjectedContext(next, model.copy(displayName = "renamed"), "original-marker").measuredPrefixTokens)
        fun assertEstimated(request: ChatRequest, snapshot: ModelAttributionSnapshot? = model, marker: String? = "original-marker") {
            val estimate = estimateProjectedContext(request, snapshot, marker)
            assertNull(estimate.measuredPrefixTokens)
            assertEquals(calibratedContextTokens(snapshot, ContextTokenMeter.rawRequestTokens(request)), estimate.tokens)
        }
        assertEstimated(next, marker = "replacement-marker")
        assertEstimated(next, marker = null)
        assertEstimated(next, snapshot = model.copy(modelId = "different"))
        assertEstimated(next, snapshot = model.copy(providerTypeRaw = "gemini"))
        assertEstimated(next, snapshot = model.copy(providerInstanceId = "another-instance"))
        assertEstimated(next, snapshot = null)
        assertEstimated(next.copy(systemPrompt = "changed system"))
        assertEstimated(next.copy(tools = listOf(tools.single().copy(description = "changed schema"))))
        assertEstimated(next.copy(messages = listOf(user("edited"), reply, user("tail"))))
        assertEstimated(next.copy(messages = listOf(user("inserted")) + next.messages))
        assertEstimated(next.copy(messages = next.messages.drop(1)))
        assertEstimated(next.copy(messages = next.messages.map { it.copy(contextUsageAnchor = null) }))
        assertEstimated(next.copy(messages = prefix.messages + reply.copy(contextUsageAnchor = anchor.copy(inputTokens = -1))))
        assertEstimated(next.copy(messages = prefix.messages + reply.copy(role = LLMMessage.Role.USER)))
    }

    @Test
    fun `retargeting the same instance selects new client and isolates its measured anchor and scale`() = runBlocking {
        val instance = openAiInstance("http://127.0.0.1:49158/Aa")
        val firstProvider = ProviderFactory.create(instance, "test-key", LLMModel.gpt4oMini) as OpenAIProvider
        val first = ProviderFactory.requestAttribution(firstProvider)
        assertNotNull(first.contextRouteId)
        assertEquals("/Aa/v1/chat/completions", firstProvider.buildRequest("{}").url.encodedPath)

        // Same String.hashCode() and same instance/model, but a different actual route.
        val oldHash = instance.hashCode()
        instance.customBaseURL = "http://127.0.0.1:49158/BB"
        assertEquals(oldHash, instance.hashCode())
        val secondProvider = ProviderFactory.create(instance, "test-key", LLMModel.gpt4oMini) as OpenAIProvider
        val second = ProviderFactory.requestAttribution(secondProvider)
        assertNotSame(firstProvider, secondProvider)
        assertEquals("/BB/v1/chat/completions", secondProvider.buildRequest("{}").url.encodedPath)
        assertEquals(first, ProviderFactory.requestAttribution(firstProvider))
        assertEquals(first.copy(contextRouteId = null), second.copy(contextRouteId = null))
        assertNotNull(second.contextRouteId)
        assertNotEquals(first.contextRouteId, second.contextRouteId)

        val prefix = request(user("prefix"))
        val raw = ContextTokenMeter.rawRequestTokens(prefix)
        val usage = LLMUsage(0, 0, latestContextTokens = raw * 4)
        val anchor = checkNotNull(settleContextUsageAttempt(captureContextUsageAttempt(prefix, first, null), usage))
        val next = prefix.copy(messages = prefix.messages + assistant("answer").copy(contextUsageAnchor = anchor))
        assertEquals(usage.latestContextTokens, estimateProjectedContext(next, first, null).measuredPrefixTokens)
        assertEquals(ContextTokenEstimate(ContextTokenMeter.rawRequestTokens(next)), estimateProjectedContext(next, second, null))
        assertEquals((raw * 1.9).toInt(), calibratedContextTokens(first, raw))
        assertEquals(raw, calibratedContextTokens(second, raw))
    }

    @Test
    fun `chat and responses separate usage but the same route is reusable after a cold rebuild`() = runBlocking {
        val base = "http://127.0.0.1:49158/relay"
        val instance = openAiInstance(base)
        val chat = ProviderFactory.create(instance, "old-fixture-key", LLMModel.gpt4oMini) as OpenAIProvider
        val chatAttribution = ProviderFactory.requestAttribution(chat)
        assertEquals("/relay/v1/chat/completions", chat.buildRequest("{}").url.encodedPath)

        val responses = ProviderFactory.create(instance.copy(useResponsesAPI = true), "old-fixture-key", LLMModel.gpt4oMini) as OpenAIProvider
        val responsesAttribution = ProviderFactory.requestAttribution(responses)
        assertEquals("/relay/v1/responses", responses.buildRequest("{}").url.encodedPath)
        assertEquals(chatAttribution.copy(contextRouteId = null), responsesAttribution.copy(contextRouteId = null))
        assertNotNull(responsesAttribution.contextRouteId)
        assertNotEquals(chatAttribution.contextRouteId, responsesAttribution.contextRouteId)

        val prefix = request(user("prefix"))
        val raw = ContextTokenMeter.rawRequestTokens(prefix)
        val usage = LLMUsage(2, 3, latestContextTokens = raw * 4)
        val anchor = checkNotNull(settleContextUsageAttempt(captureContextUsageAttempt(prefix, chatAttribution, null), usage))
        val encoded = checkNotNull(buildTurnTokenUsageJson(usage, contextUsageAnchor = anchor))
        assertFalse(encoded.contains(base))
        assertFalse(encoded.contains("old-fixture-key"))
        // Stable across processes, not a provider object, credential, or runtime hashCode.
        assertEquals("ce908ccf12ba96fb28b3d55a3d265a33a8b44c56a342dbd89df0938286c3ac0f", chatAttribution.contextRouteId)

        ProviderFactory.invalidateInstance(instance.id)
        val rebuilt = ProviderFactory.create(instance.copy(customBaseURL = "$base/v1/"), "rotated-fixture-key", LLMModel.gpt4oMini)
        assertNotSame(chat, rebuilt)
        val rebuiltAttribution = ProviderFactory.requestAttribution(rebuilt)
        assertEquals(chatAttribution, rebuiltAttribution)
        val restored = checkNotNull(readContextUsageAnchor(encoded, rebuiltAttribution.copy(contextRouteId = null)))
        val next = prefix.copy(messages = prefix.messages + assistant("answer").copy(contextUsageAnchor = restored))
        assertEquals(usage.latestContextTokens, estimateProjectedContext(next, rebuiltAttribution, null).measuredPrefixTokens)
        assertEquals(ContextTokenEstimate(ContextTokenMeter.rawRequestTokens(next)), estimateProjectedContext(next, responsesAttribution, null))
        assertEquals(raw, calibratedContextTokens(responsesAttribution, raw))
    }

    @Test
    fun `azure API version participates while ambiguous URL credentials or overrides disable measurement`() = runBlocking {
        val instance = openAiInstance("http://127.0.0.1:49158?api-version=2024-10-21").copy(azureMode = true)
        val first = ProviderFactory.create(instance, "test-key", LLMModel.gpt4oMini) as OpenAIProvider
        val old = ProviderFactory.requestAttribution(first)
        val oldUrl = first.buildRequest("{}").url
        assertEquals("2024-10-21", oldUrl.queryParameter("api-version"))
        assertNotNull(old.contextRouteId)
        val alias = ProviderFactory.create(
            instance.copy(customBaseURL = "http://127.0.0.1:49158/openai/v1/?api-version=2024-10-21"),
            "test-key", LLMModel.gpt4oMini,
        ) as OpenAIProvider
        assertEquals(oldUrl, alias.buildRequest("{}").url)
        assertEquals(old.contextRouteId, ProviderFactory.requestAttribution(alias).contextRouteId)
        val next = ProviderFactory.create(instance.copy(customBaseURL = "http://127.0.0.1:49158?api-version=2025-04-01-preview"), "test-key", LLMModel.gpt4oMini)
        assertNotNull(ProviderFactory.requestAttribution(next).contextRouteId)
        assertNotEquals(old.contextRouteId, ProviderFactory.requestAttribution(next).contextRouteId)

        first.absoluteEndpointOverride = "/different/endpoint"
        assertEquals("/different/endpoint", first.buildRequest("{}").url.encodedPath)
        assertNull(ProviderFactory.requestAttribution(first).contextRouteId)
        assertNotNull(old.contextRouteId) // Existing attempt snapshots do not change.
        val ambiguous = ProviderFactory.create(instance.copy(customBaseURL = "http://127.0.0.1:49158?key=fixture-secret"), "test-key", LLMModel.gpt4oMini)
        val snapshot = ProviderFactory.requestAttribution(ambiguous)
        val prefix = request(user("prefix"))
        assertNull(snapshot.contextRouteId)
        assertNull(settleContextUsageAttempt(captureContextUsageAttempt(prefix, snapshot, null), LLMUsage(1, 2, latestContextTokens = 50)))
        assertEquals(ContextTokenMeter.rawRequestTokens(prefix), calibratedContextTokens(snapshot, ContextTokenMeter.rawRequestTokens(prefix)))
    }

    private fun openAiInstance(base: String) = ProviderInstance(
        id = UUID.randomUUID().toString(), label = "route test", providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey, customBaseURL = base,
    )

    @Test
    fun `capture freezes the fingerprint before mutable tool args change`() {
        val args = JSONObject().put("query", "earlier")
        val call = AgentContentPart.ToolUse("call", "find", args)
        val request = request(user("prompt"), assistant("").copy(contentParts = listOf(call)))
        val attempt = captureContextUsageAttempt(request, model, null)
        args.put("query", "changed")
        val anchor = checkNotNull(settleContextUsageAttempt(attempt, LLMUsage(0, 0, latestContextTokens = 200)))
        val appended = request.copy(messages = request.messages + assistant("answer").copy(contextUsageAnchor = anchor))
        assertNull(estimateProjectedContext(appended, model, null).measuredPrefixTokens)
    }

    @Test
    fun `explicit images belong to the full projection last user not the preceding one`() {
        val image = LLMMessage.ImagePart(byteArrayOf(1, 2, 3), "image/png")
        val prefix = request(user("prompt")).copy(imageParts = listOf(image))
        val anchor = checkNotNull(settleContextUsageAttempt(
            captureContextUsageAttempt(prefix, model, null), LLMUsage(0, 0, latestContextTokens = 600),
        ))
        val answer = assistant("answer").copy(contextUsageAnchor = anchor)
        val samePlacement = prefix.copy(messages = prefix.messages + answer)
        assertEquals(600, estimateProjectedContext(samePlacement, model, null).measuredPrefixTokens)
        assertNull(estimateProjectedContext(samePlacement.copy(imageParts = emptyList()), model, null).measuredPrefixTokens)
        assertNull(estimateProjectedContext(samePlacement.copy(imageParts = listOf(image.copy(data = byteArrayOf(4)))), model, null).measuredPrefixTokens)
        val moved = samePlacement.copy(messages = samePlacement.messages + user("new user"))
        assertNull(estimateProjectedContext(moved, model, null).measuredPrefixTokens)

        val noImagePrefix = prefix.copy(imageParts = emptyList())
        val earlier = checkNotNull(settleContextUsageAttempt(
            captureContextUsageAttempt(noImagePrefix, model, null), LLMUsage(0, 0, latestContextTokens = 75),
        ))
        val imageSuffix = listOf(answer.copy(contextUsageAnchor = earlier), user("new user"))
        val withImageSuffix = moved.copy(messages = noImagePrefix.messages + imageSuffix)
        val total = estimateProjectedContext(withImageSuffix, model, null)
        assertEquals(75, total.measuredPrefixTokens)
        assertEquals(75 + calibratedContextTokens(model, ContextTokenMeter.rawRequestTokens(imageSuffix, imageParts = listOf(image))), total.tokens)
    }
}
