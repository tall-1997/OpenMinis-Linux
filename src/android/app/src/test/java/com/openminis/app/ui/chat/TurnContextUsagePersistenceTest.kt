package com.openminis.app.ui.chat

import com.openminis.app.data.ContextTokenMeter
import com.openminis.app.data.captureContextUsageAttempt
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.estimateProjectedContext
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.ContextUsageAnchor
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.settleContextUsageAttempt
import com.openminis.app.provider.api.ChatRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TurnContextUsagePersistenceTest {
    private val model = ModelAttributionSnapshot(
        "claude-test", "Claude Test", "anthropic", "context-persist-tests", contextRouteId = "ab".repeat(32),
    )
    private val usage = LLMUsage(10, 3, cacheCreationInputTokens = 20, cacheReadInputTokens = 30, latestContextTokens = 10)
    private val request = ChatRequest(listOf(LLMMessage(LLMMessage.Role.USER, "prompt")), systemPrompt = "system", maxTokens = 20)
    private val assistant = LLMMessage(LLMMessage.Role.ASSISTANT, "answer", contentParts = listOf(AgentContentPart.Text("answer")))

    private fun anchor(marker: String? = null): ContextUsageAnchor =
        checkNotNull(settleContextUsageAttempt(captureContextUsageAttempt(request, model, marker), usage))

    private fun row(tokenUsage: String?) = MessageEntity(
        id = "assistant-row", sessionId = "session", role = "assistant", partsJson = "[]",
        createdAt = 0L, sortOrder = 1, tokenUsage = tokenUsage,
        modelId = model.modelId, modelDisplayName = model.displayName, providerType = model.providerTypeRaw,
        providerInstanceId = model.providerInstanceId,
    )

    @Test
    fun `nested measurement round trips and does not change any billing field`() {
        val measured = anchor("summary-marker")
        val thinking = TurnThinkingUsage(requested = "HIGH", effective = null)
        val old = JSONObject(checkNotNull(buildTurnTokenUsageJson(usage, 42, thinking)))
        val encoded = checkNotNull(buildTurnTokenUsageJson(usage, 42, thinking, measured))
        val json = JSONObject(encoded)
        val measuredJson = json.getJSONObject("contextUsageAnchor")
        assertEquals(2, measuredJson.getInt("version"))
        assertEquals(model.contextRouteId, measuredJson.getString("contextRouteId"))
        assertEquals(60, measuredJson.getInt("inputTokens"))
        json.remove("contextUsageAnchor")
        assertEquals(old.keySet(), json.keySet())
        for (key in old.keySet()) assertEquals(old.get(key), json.get(key))
        assertEquals(10, json.getInt("latestContextTokens"))
        assertFalse(json.has("thinkingLevelEffective"))
        assertEquals(measured, readAssistantContextUsageAnchor(row(encoded)))
        assertNull(buildTurnTokenUsageJson(null, contextUsageAnchor = measured))
    }

    @Test
    fun `reloaded matching row provides its measured prefix without relying on live state`() {
        val measured = anchor()
        val entity = row(buildTurnTokenUsageJson(usage, contextUsageAnchor = measured)).copy(
            partsJson = buildAssistantPartsJson(assistant.contentParts, emptyMap()),
        )
        val restored = mapStoredLLMMessage(entity, mediaFile = { error("Unexpected media: $it") })
        assertEquals(entity.id, restored.dbMessageId)
        assertEquals(measured, restored.contextUsageAnchor)
        val next = request.copy(messages = request.messages + restored)
        val estimate = estimateProjectedContext(next, model, null)
        assertEquals(60, estimate.measuredPrefixTokens)
        assertNull(estimateProjectedContext(next, model, "recompacted").measuredPrefixTokens)

        for (badParts in listOf("not JSON", "[]")) {
            assertNull(mapStoredLLMMessage(entity.copy(partsJson = badParts), mediaFile = { error("Unexpected media: $it") }).contextUsageAnchor)
        }
    }

    @Test
    fun `legacy or contradictory row metadata is ignored`() {
        assertNull(readAssistantContextUsageAnchor(row(buildTurnTokenUsageJson(usage))))
        assertNull(readAssistantContextUsageAnchor(row("not JSON")))
        val encoded = buildTurnTokenUsageJson(usage, contextUsageAnchor = anchor())
        val original = row(encoded)
        assertNull(readAssistantContextUsageAnchor(row("$encoded trailing junk")))
        assertEquals(anchor(), readAssistantContextUsageAnchor(row("$encoded\n")))
        assertNull(readAssistantContextUsageAnchor(original.copy(role = "user")))
        assertNull(readAssistantContextUsageAnchor(original.copy(modelId = "another")))
        assertNull(readAssistantContextUsageAnchor(original.copy(providerType = "openAI")))
        assertNull(readAssistantContextUsageAnchor(original.copy(providerInstanceId = null)))
        assertNull(turnContextUsageAnchor(usage, model.copy(modelId = "another"), anchor()))
        val differentRoute = model.copy(contextRouteId = "cd".repeat(32))
        assertNull(turnContextUsageAnchor(usage, differentRoute, anchor()))
        assertNull(readContextUsageAnchor(encoded, differentRoute))
        assertNull(turnContextUsageAnchor(null, model, anchor()))
    }

    @Test
    fun `v1 and route-less anchors never borrow the current route`() {
        val encoded = checkNotNull(buildTurnTokenUsageJson(usage, contextUsageAnchor = anchor()))
        val json = JSONObject(encoded)
        val anchorJson = json.getJSONObject("contextUsageAnchor")
        anchorJson.put("version", 1)
        anchorJson.remove("contextRouteId")
        assertNull(readAssistantContextUsageAnchor(row(json.toString())))
        assertNull(readContextUsageAnchor(json.toString(), model))
        assertEquals(10, json.getInt("inputTokens"))
        assertEquals(30, json.getInt("cacheReadTokens"))

        anchorJson.put("version", 2)
        assertNull(readContextUsageAnchor(json.toString(), model))
        val unrouted = anchor().copy(modelSnapshot = model.copy(contextRouteId = null))
        val billingOnly = JSONObject(checkNotNull(buildTurnTokenUsageJson(usage, contextUsageAnchor = unrouted)))
        assertFalse(billingOnly.has("contextUsageAnchor"))
        assertEquals(10, billingOnly.getInt("latestContextTokens"))
    }

    @Test
    fun `unsound anchor fields fall back instead of being coerced`() {
        val encoded = checkNotNull(buildTurnTokenUsageJson(usage, contextUsageAnchor = anchor()))
        for ((key, value) in listOf(
            "version" to 3, "prefixLength" to -1, "prefixLength" to "1", "prefixLength" to 1.5,
            "prefixLength" to (Int.MAX_VALUE.toLong() + 1), "prefixFingerprint" to "corrupt",
            "inputTokens" to 0, "inputTokens" to "60", "markerId" to 41, "markerId" to "",
            "contextRouteId" to "https://example.invalid/v1", "contextRouteId" to 1234,
            "contextRouteId" to "AB".repeat(32), "contextRouteId" to JSONObject.NULL,
        )) {
            val json = JSONObject(encoded)
            json.getJSONObject("contextUsageAnchor").put(key, value)
            assertNull("bad $key=$value", readAssistantContextUsageAnchor(row(json.toString())))
        }
        val json = JSONObject(encoded)
        json.getJSONObject("contextUsageAnchor").remove("markerId")
        assertNull(readAssistantContextUsageAnchor(row(json.toString())))
    }

    @Test
    fun `backfill only binds the current assistant before later history is appended`() {
        val measured = anchor()
        val history = mutableListOf(assistant, request.messages.single(), assistant)
        backfillPersistedAssistantTurn(history, "new-row", measured)
        assertEquals("new-row", history.last().dbMessageId)
        assertEquals(measured, history.last().contextUsageAnchor)
        assertNull(history.first().dbMessageId)
        assertNull(history.first().contextUsageAnchor)
        backfillPersistedAssistantTurn(history, "ignore")
        assertEquals("new-row", history.last().dbMessageId)

        val noCurrentAssistant = mutableListOf(assistant, request.messages.single())
        backfillPersistedAssistantTurn(noCurrentAssistant, "wrong-row", measured)
        assertNull(noCurrentAssistant.first().dbMessageId)
        assertNull(noCurrentAssistant.first().contextUsageAnchor)
    }

    @Test
    fun `backfill replays repaired tool arguments in the same order as a reloaded row`() {
        val draft = AgentContentPart.ToolUse("call", "lookup", JSONObject().put("query", "draft"))
        val repaired = AgentContentPart.ToolUse.fromInputJson("call", "lookup", """{"query":"complete"}""", "signature")
        val turnParts = listOf(AgentContentPart.Text("before"), repaired, AgentContentPart.Text("after"))
        val live = assistant.copy(content = "beforeafter", contentParts = listOf(AgentContentPart.Text("beforeafter"), draft))
        val history = mutableListOf(live)
        val entity = row(null).copy(partsJson = buildAssistantPartsJson(turnParts, emptyMap()))
        backfillPersistedAssistantTurn(history, entity.id, persistedParts = turnParts)

        val restored = mapStoredLLMMessage(entity, mediaFile = { error("Unexpected media: $it") })
        assertEquals(turnParts, history.single().contentParts)
        assertEquals(restored, history.single())
        assertEquals(ContextTokenMeter.requestFingerprint(listOf(restored)), ContextTokenMeter.requestFingerprint(history))
    }

    @Test
    fun `backfill repairs tool arguments without dropping live-only text or media`() {
        val image = AgentContentPart.ImageData(byteArrayOf(1, 2), "image/png")
        val text = AgentContentPart.Text("answer\n\n![image](minis://attachments/example.png)")
        val draft = AgentContentPart.ToolUse("call", "lookup", JSONObject().put("query", "draft"))
        val repaired = AgentContentPart.ToolUse.fromInputJson("call", "lookup", """{"query":"complete"}""")
        val live = assistant.copy(content = text.text, contentParts = listOf(text, image, draft))
        val history = mutableListOf(live)
        backfillPersistedAssistantTurn(history, "row", persistedParts = listOf(AgentContentPart.Text("answer"), repaired))
        val backfilled = history.single()
        assertEquals(text.text, backfilled.content)
        assertEquals(listOf(text, image, repaired), backfilled.contentParts)
        assertSame(image, backfilled.contentParts[1])
    }
}
