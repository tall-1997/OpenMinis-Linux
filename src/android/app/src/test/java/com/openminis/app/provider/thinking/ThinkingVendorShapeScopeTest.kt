package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thinking-vendor-shape-scope] Pins the general RULE, not one vendor.
 *
 * A model-id rule says how the VENDOR spells "reasoning on". It says nothing
 * about the endpoint serving the request. A third-party relay carrying the same
 * model id is an OpenAI-compatible proxy: it ignores fields it does not know, so
 * a vendor-PRIVATE shape silently yields no reasoning at all.
 *
 * Field evidence: relay `susu.wiki`, `deepseek-v4.1-flash`, level HIGH — the
 * request carried the private `thinking` object, the stream returned ~180 text
 * chunks and ZERO reasoning chunks.
 *
 * The invariant, vendor-agnostic:
 *
 * private shape => only its owner host may receive it,
 * otherwise the PORTABLE shape (plain OpenAI `reasoning_effort`) is used.
 *
 * Crucially this does NOT require the endpoint to declare effort tiers: a
 * manually configured relay never does, and gating on that declaration is why
 * the previous attempt fixed nothing in the field.
 */
class ThinkingVendorShapeScopeTest {

    private fun ctx(
        modelId: String,
        host: String?,
        declared: List<String>? = null,
        level: ThinkingLevel = ThinkingLevel.HIGH,
    ) = ThinkingResolveContext(
        modelId = modelId,
        instanceId = null,
        supportsReasoning = true,
        declaredEffortValues = declared,
        level = level,
        maxTokens = 4096,
        isOpenRouter = false,
        usesUnifiedReasoningEffort = false,
        isMistral = false,
        isDashScope = false,
        offEffort = null,
        endpointHost = host,
    )

    private fun resolve(modelId: String, host: String?, level: ThinkingLevel = ThinkingLevel.HIGH): JSONObject =
        JSONObject().also { ThinkingRuleResolver.apply(it, ctx(modelId = modelId, host = host, level = level)) }

    // ---- the reported field case ----

    @Test
    fun `a relay carrying a deepseek id gets the PORTABLE shape only`() {
        // Declared tiers are NULL on purpose: a manually configured relay never
        // declares them, and the old gate required them, so it never fired.
        val body = resolve("deepseek-v4.1-flash-1017", "https://susu.wiki/v1")
        assertFalse("a relay cannot read the vendor-private object: $body", body.has("thinking"))
        assertEquals("the portable contract must be sent instead", "high", body.optString("reasoning_effort", null))
    }

    @Test
    fun `a relay whose PATH names the vendor is still a relay`() {
        // `contains("deepseek")` read this as the vendor's own host; a path is not
        // a host. This is why hostOf() parses the URI instead of substring-matching.
        val body = resolve("deepseek-v4.1-flash-1017", "https://susu.wiki/deepseek/v1")
        assertFalse("path must not decide the vendor: $body", body.has("thinking"))
        assertTrue("portable shape expected: $body", body.has("reasoning_effort"))
    }

    @Test
    fun `the vendor's own host keeps its private shape`() {
        val body = resolve("deepseek-v4.1-flash-1017", "https://api.deepseek.com/v1")
        assertTrue("the owner host reads its own spelling: $body", body.has("thinking"))
    }

    // ---- conservative defaults ----

    @Test
    fun `an unknown host keeps the declared shape`() {
        // Null = "the caller does not know its host". Guessing created this bug;
        // an unknown host must not silently lose the vendor shape either.
        val body = resolve("deepseek-v4.1-flash-1017", null)
        assertTrue("unknown host must not downgrade blindly: $body", body.has("thinking"))
    }

    @Test
    fun `a portable rule resolves identically on any host`() {
        // glm has no vendor-private rule: it already rides the portable shape, so
        // the fallback must be a byte-level no-op for it.
        val relay = resolve("glm-5.3", "https://susu.wiki/v1")
        val official = resolve("glm-5.3", "https://api.z.ai/api/paas/v4")
        assertEquals("a portable shape must not depend on the host", relay.toString(), official.toString())
    }

    @Test
    fun `OFF on a relay stays silent because no off tier is documented`() {
        // OpenAIOffEffort is an allow-list (official OpenAI / Ark only): a relay's
        // off tier is undocumented, so `offEffort` is null and there is no value to
        // send. Omitting is the vendor's own default. This is the SAFE branch —
        // the host exemption must not invent a tier that could 400.
        val body = resolve("deepseek-v4.1-flash-1017", "https://susu.wiki/v1", ThinkingLevel.OFF)
        assertFalse("OFF must not emit the vendor object: $body", body.has("thinking"))
        assertFalse("no documented off tier means no field: $body", body.has("reasoning_effort"))
    }

    @Test
    fun `the host exemption does not invent an off tier on a relay`() {
        // Regression guard for the exemption itself: it may only bypass the
        // FAMILY SKIP, never the "no documented tier => omit" rule.
        val body = resolve("glm-5.3", "https://susu.wiki/v1", ThinkingLevel.OFF)
        assertFalse("a portable rule must stay silent too: $body", body.has("reasoning_effort"))
    }

    // ---- host parsing edges ----

    @Test
    fun `a bailian workspace host still counts as the owner`() {
        // Bailian serves each workspace from its own host; enumerating only
        // dashscope.* here is what made an official endpoint look like a relay.
        val body = resolve("qwen3-max", "https://ws-abc123.maas.aliyuncs.com/compatible-mode/v1")
        assertFalse("an official Bailian workspace must not be downgraded: $body", body.isEmpty)
        assertTrue("owner host keeps its own shape: $body", body.has("enable_thinking") || body.has("thinking"))
    }

    @Test
    fun `a host-suffix impostor is still a relay`() {
        // Exact-match ownership: `api.deepseek.com.evil.example` is NOT the vendor.
        val body = resolve("deepseek-v4.1-flash-1017", "https://api.deepseek.com.evil.example/v1")
        assertFalse("a suffix impostor is a relay: $body", body.has("thinking"))
        assertTrue("portable shape expected: $body", body.has("reasoning_effort"))
    }

    @Test
    fun `a port in the base URL does not defeat host parsing`() {
        val body = resolve("deepseek-v4.1-flash-1017", "http://localhost:1234/v1")
        assertFalse("localhost is a relay: $body", body.has("thinking"))
        assertTrue("portable shape expected: $body", body.has("reasoning_effort"))
    }
}
