package com.openminis.app.data.repository

import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [#24 provider-transfer] Pure-JVM contract of the batch provider transfer
 * document: export→import round-trip, (name+type) dedupe merge with the
 * " (imported)" suffix ladder, and keyless compatibility import.
 */
class ProviderTransferCodecTest {

    private fun instance(
        label: String,
        type: ProviderType = ProviderType.openAI,
        base: String? = "https://relay.example/v1",
    ) = ProviderInstance(
        id = "inst-$label",
        label = label,
        providerType = type,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = base,
    )

    private fun model(id: String, displayName: String = id.uppercase()) = LLMModel(
        id = id,
        displayName = displayName,
        provider = ProviderType.openAI.displayName,
        contextWindow = 128_000,
        maxOutputTokens = 4_096,
        supportsReasoning = true,
    )

    private fun entry(instanceId: String, m: LLMModel) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = m,
        overrides = ModelOverrides(temperature = 0.5),
        isCustom = true,
    )

    // ── Round-trip ───────────────────────────────────────────────────────

    @Test
    fun `export then parse reproduces every field`() {
        val inst = instance("Relay")
        val snapshot = ProviderTransferCodec.toTransferable(
            instance = inst,
            entries = listOf(entry(inst.id, model("gpt-test"))),
            apiKey = "sk-live-1",
        )
        val doc = ProviderTransferCodec.buildExportDocument(listOf(snapshot))

        val root = JSONObject(doc)
        assertEquals(1, root.optInt("version"))
        assertEquals(1, root.optJSONArray("providers").length())

        val parsed = ProviderTransferCodec.parseDocument(doc)!!
        assertEquals(1, parsed.size)
        val p = parsed[0]
        assertEquals("Relay", p.label)
        assertEquals("openAI", p.providerType)
        assertEquals("apiKey", p.credentialType)
        assertEquals("https://relay.example/v1", p.customBaseURL)
        assertEquals("sk-live-1", p.apiKey)
        assertEquals(1, p.models.size)
        val m = p.models[0]
        assertEquals("gpt-test", m.modelId)
        assertEquals("GPT-TEST", m.displayName)
        assertEquals(128_000, m.contextWindow)
        assertEquals(4_096, m.maxOutputTokens)
        assertEquals(true, m.supportsReasoning)
        assertEquals(0.5, m.temperature!!, 1e-9)
        assertEquals(true, m.isCustom)
    }

    @Test
    fun `rebuild instance and entries from snapshot`() {
        val inst = instance("Kimi", type = ProviderType.kimiCode, base = null)
        val snapshot = ProviderTransferCodec.toTransferable(inst, emptyList(), apiKey = null)
        val parsed = ProviderTransferCodec.parseDocument(
            ProviderTransferCodec.buildExportDocument(listOf(snapshot)),
        )!!
        val rebuilt = ProviderTransferCodec.toInstance(parsed[0], id = "new-id", label = parsed[0].label)
        assertEquals("Kimi", rebuilt.label)
        assertEquals(ProviderType.kimiCode, rebuilt.providerType)
        assertEquals(ProviderCredential.apiKey, rebuilt.credentialType)
        assertNull(rebuilt.customBaseURL)
        assertTrue(rebuilt.appendV1Suffix)
    }

    @Test
    fun `non-default flags round-trip`() {
        val inst = ProviderInstance(
            id = "x",
            label = "Azure Up",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://acct.azure.com",
            appendV1Suffix = false,
            useResponsesAPI = true,
            customUserAgent = "ClaudeCode/1.0",
            azureMode = true,
            imageEndpointMode = ImageEndpointMode.chatCompletions,
        )
        val doc = ProviderTransferCodec.buildExportDocument(
            listOf(ProviderTransferCodec.toTransferable(inst, emptyList(), null)),
        )
        val p = ProviderTransferCodec.parseDocument(doc)!![0]
        assertEquals(false, p.appendV1Suffix)
        assertEquals(true, p.useResponsesAPI)
        assertEquals("ClaudeCode/1.0", p.customUserAgent)
        assertEquals(true, p.azureMode)
        assertEquals(ImageEndpointMode.chatCompletions, p.imageEndpointMode)
    }

    // ── Dedupe merge ─────────────────────────────────────────────────────

    @Test
    fun `conflicting name+type gets imported suffix`() {
        val p1 = ProviderTransferCodec.toTransferable(instance("Relay"), emptyList(), null)
        val p2 = ProviderTransferCodec.toTransferable(instance("Fresh"), emptyList(), null)
        val existing = setOf("relay" to "openai")
        val resolved = ProviderTransferCodec.dedupeLabels(listOf(p1, p2), existing)
        assertEquals("Relay (imported)", resolved[0].label)
        assertEquals("Fresh", resolved[1].label)
    }

    @Test
    fun `same name different type does not conflict`() {
        val openai = ProviderTransferCodec.toTransferable(instance("Relay"), emptyList(), null)
        val claude = ProviderTransferCodec.toTransferable(instance("Relay", type = ProviderType.anthropic), emptyList(), null)
        val existing = setOf("relay" to "openai")
        val resolved = ProviderTransferCodec.dedupeLabels(listOf(openai, claude), existing)
        assertEquals("Relay (imported)", resolved[0].label)
        // Anthropic type has no stored "relay" pair → unchanged.
        assertEquals("Relay", resolved[1].label)
    }

    @Test
    fun `repeated collisions walk the suffix ladder`() {
        val p = ProviderTransferCodec.toTransferable(instance("Relay"), emptyList(), null)
        val existing = setOf(
            "relay" to "openai",
            "relay (imported)" to "openai",
        )
        val resolved = ProviderTransferCodec.dedupeLabels(listOf(p), existing)
        assertEquals("Relay (imported 2)", resolved[0].label)
    }

    @Test
    fun `dedupe is case-insensitive and batch self-aware`() {
        val p1 = ProviderTransferCodec.toTransferable(instance("relay"), emptyList(), null)
        val p2 = ProviderTransferCodec.toTransferable(instance("Relay"), emptyList(), null)
        val existing = setOf("relay" to "openai")
        val resolved = ProviderTransferCodec.dedupeLabels(listOf(p1, p2), existing)
        // Both collide case-insensitively; p1 takes the base suffix, p2 walks
        // the ladder because the batch itself already claimed that slot.
        assertEquals("relay (imported)", resolved[0].label)
        assertEquals("Relay (imported 2)", resolved[1].label)
    }

    // ── Keyless compatibility ────────────────────────────────────────────

    @Test
    fun `document without apiKey imports with null key`() {
        val json = """
            {"version":1,"providers":[
              {"name":"Local LM Studio","type":"openAI","credentialType":"apiKey",
               "baseURL":"http://192.168.1.10:1234/v1","models":[]}
            ]}
        """.trimIndent()
        val parsed = ProviderTransferCodec.parseDocument(json)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.size)
        assertNull(parsed[0].apiKey)
        assertEquals("http://192.168.1.10:1234/v1", parsed[0].customBaseURL)
        val inst = ProviderTransferCodec.toInstance(parsed[0], "id", parsed[0].label)
        assertEquals("Local LM Studio", inst.label)
        assertEquals("http://192.168.1.10:1234/v1", inst.customBaseURL)
    }

    @Test
    fun `legacy customBaseURL spelling is accepted on import`() {
        val json = """
            {"version":1,"providers":[
              {"name":"Old","type":"openAI","customBaseURL":"https://old.example/v1","models":[]}
            ]}
        """.trimIndent()
        val parsed = ProviderTransferCodec.parseDocument(json)!!
        assertEquals("https://old.example/v1", parsed[0].customBaseURL)
    }

    @Test
    fun `unknown provider type decodes to unsupported instead of failing the batch`() {
        val json = """
            {"version":1,"providers":[
              {"name":"Future","type":"quantumCloud","models":[]},
              {"name":"Fine","type":"openAI","models":[]}
            ]}
        """.trimIndent()
        val parsed = ProviderTransferCodec.parseDocument(json)!!
        assertEquals(2, parsed.size)
        assertEquals(ProviderType.unsupported, ProviderTransferCodec.toInstance(parsed[0], "id", "Future").providerType)
    }

    @Test
    fun `malformed document returns null`() {
        assertNull(ProviderTransferCodec.parseDocument("not json"))
        assertNull(ProviderTransferCodec.parseDocument("{\"version\":99,\"providers\":[]}"))
        assertNull(ProviderTransferCodec.parseDocument("{\"unrelated\":true}"))
    }

    @Test
    fun `blank label or missing type drops only that entry`() {
        val json = """
            {"version":1,"providers":[
              {"name":"  ","type":"openAI","models":[]},
              {"name":"NoType","models":[]},
              {"name":"Good","type":"openAI","models":[]}
            ]}
        """.trimIndent()
        val parsed = ProviderTransferCodec.parseDocument(json)!!
        assertEquals(listOf("Good"), parsed.map { it.label })
    }
}
