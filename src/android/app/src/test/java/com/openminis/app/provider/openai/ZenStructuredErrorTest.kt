package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMModel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-zen-structured-error] OpenCode Zen answers with a structured body whose
 * error.type carries semantics the generic HTTP mapping cannot see. Mapping a
 * FreeTierError to "Invalid API key" told the user to check a key that is not
 * involved — the request's OpenCode session identity was refused, and
 * [com.openminis.app.provider.ZenDisguise] was building a malformed one
 * (measured 2026-10-05; with a canonical id the free lane streams 200).
 */
class ZenStructuredErrorTest {

    private fun zenProvider(modelId: String) = OpenAIProvider(
        apiKey = "public",
        model = LLMModel(modelId, modelId, "OpenCode Zen"),
        basePath = "https://opencode.ai/zen/v1",
    )

    @After
    fun clearHealthRecord() {
        // The retirement record is a process-wide object; tests below write
        // into it, so every test cleans its own ids out.
        listOf("mimo-v2.5-free", "exo-free", "ling-3.0-flash-fin-free", "big-pickle").forEach {
            com.openminis.app.provider.ZenFreeLaneHealth.revive(it)
        }
    }

    @Test
    fun `FreeTierError body maps to a provider refusal, not an invalid key`() {
        val body = """
            {"type":"error","error":{"type":"FreeTierError","message":
            "Error from provider (Console): OpenCode's free tier can only be used from within OpenCode"}}
        """.trimIndent()
        val err = zenStructuredProviderRefusal(body)
        assertTrue(err is com.openminis.app.data.model.LLMError.ProviderError)
        val msg = err!!.message ?: ""
        assertTrue(msg, msg.contains("FreeTierError:"))
        // [T-zen-canonical-session] The upstream text says "only within
        // OpenCode", which reads as a service boundary. It is not: the same
        // body answers a request whose session id is malformed, and a canonical
        // id makes the same model stream 200. So the hint must point at the
        // session identity, not tell the user no client can pass this.
        assertTrue(msg, msg.contains("session identity"))
        assertTrue(msg, !msg.contains("only to the official OpenCode client"))
    }

    @Test
    fun `ModelError body maps to a catalogue-mismatch refusal`() {
        val body = """
            {"type":"error","error":{"type":"ModelError","message":"Model test is not supported"}}
        """.trimIndent()
        val err = zenStructuredProviderRefusal(body)
        assertTrue(err is com.openminis.app.data.model.LLMError.ProviderError)
        val msg = err!!.message ?: ""
        assertTrue(msg, msg.contains("ModelError:"))
        assertTrue(msg, msg.contains("lists this id but refuses to serve it"))
    }

    @Test
    fun `zen 500 body with generic error type does not match the sniffer`() {
        // {"type":"error","error":{"type":"error",…}} must fall through so the
        // transient-500 mapping (retryable) stays in charge.
        assertNull(
            zenStructuredProviderRefusal(
                """{"type":"error","error":{"type":"error","message":"Internal server error"}}""",
            ),
        )
    }

    /**
     * The geo-fenced free ids answer RegionError, not FreeTierError — they
     * PASS the identity gate and are refused only on geography. Before this
     * branch they fell through to the generic mapping, which reported a plan /
     * region problem and never said the model is unreachable from here.
     */
    @Test
    fun `RegionError body names the geo-fence`() {
        val body = """
            {"type":"error","error":{"type":"RegionError",
            "message":"This model is not available in your country."}}
        """.trimIndent()
        val err = zenStructuredProviderRefusal(body)
        assertTrue(err is com.openminis.app.data.model.LLMError.ProviderError)
        val msg = err!!.message ?: ""
        assertTrue(msg, msg.contains("RegionError:"))
        assertTrue(msg, msg.contains("geo-fenced"))
    }

    /**
     * The FreeTierError hint must not send the user hunting for another free
     * model on the assumption this one is uniquely closed: with a canonical
     * session id nine free models stream 200, and a malformed id fails them
     * all identically. So the hint has to point at the request, not the roster.
     */
    @Test
    fun `FreeTierError hint points at the request rather than the model roster`() {
        val body = """
            {"type":"error","error":{"type":"FreeTierError","message":"free tier"}}
        """.trimIndent()
        val msg = zenStructuredProviderRefusal(body)?.message ?: ""
        assertTrue(msg, msg.contains("session identity"))
        assertTrue(msg, !msg.contains("Pick another -free model"))
        assertTrue(
            "must not imply the model is uniquely refused",
            !msg.contains("only to the official OpenCode client"),
        )
    }

    @Test
    fun `generic OpenAI error body does not match the sniffer`() {
        assertNull(
            zenStructuredProviderRefusal(
                """{"error":{"message":"The model does not exist","type":"invalid_request_error"}}""",
            ),
        )
    }

    @Test
    fun `non-json body does not match the sniffer`() {
        assertNull(zenStructuredProviderRefusal("gateway timeout"))
        assertNull(zenStructuredProviderRefusal(""))
    }

    // ---- [T-zen-free-lane-follow] retirement evidence + dead-record hook ----

    @Test
    fun `retirement evidence recognises the three retirement dialects`() {
        // 401 ModelError "not supported" — mimo-v2.5-free, deepseek-v4-flash-free, hy3-free
        assertTrue(
            zenRetirementEvidence(
                """{"type":"error","error":{"type":"ModelError","message":"Model mimo-v2.5-free is not supported"}}""",
            ),
        )
        // 403 RegionError — the muse pair, 2026-10-05
        assertTrue(
            zenRetirementEvidence(
                """{"type":"error","error":{"type":"RegionError","message":"not available in your country"}}""",
            ),
        )
        // "Endpoint is unavailable" on 400 (ling-3.0-flash-fin-free) AND on 503
        // wrapped in a bare server_error body with no outer envelope (exo-free)
        assertTrue(
            zenRetirementEvidence(
                """{"type":"error","error":{"type":"error","message":"Error from provider (Console): Upstream request failed: Endpoint is unavailable."}}""",
            ),
        )
        assertTrue(
            zenRetirementEvidence(
                """{"error":{"type":"server_error","message":"Error from provider (Console): Upstream request failed: Endpoint is unavailable."}}""",
            ),
        )
    }

    @Test
    fun `non-retirement dialects are not evidence`() {
        // FreeTierError answers EVERY model when the session identity is bad —
        // recording it would nuke the whole lane.
        assertFalse(
            zenRetirementEvidence(
                """{"type":"error","error":{"type":"FreeTierError","message":"OpenCode's free tier can only be used from within OpenCode"}}""",
            ),
        )
        // 429 quota: the model works, the quota doesn't.
        assertFalse(
            zenRetirementEvidence(
                """{"type":"error","error":{"type":"FreeUsageLimitError","message":"Rate limit exceeded. Please try again later."}}""",
            ),
        )
        // bare 500, generic OpenAI shape, non-JSON
        assertFalse(zenRetirementEvidence("""{"type":"error","error":{"type":"error","message":"Internal server error"}}"""))
        assertFalse(zenRetirementEvidence("""{"error":{"message":"The model does not exist","type":"invalid_request_error"}}"""))
        assertFalse(zenRetirementEvidence("gateway timeout"))
    }

    @Test
    fun `a retirement refusal records the model id in the dead record`() {
        val provider = zenProvider("mimo-v2.5-free")
        provider.mapHttpError(
            401,
            """{"type":"error","error":{"type":"ModelError","message":"Model mimo-v2.5-free is not supported"}}""",
        )
        assertTrue(
            "a 401 ModelError must mark the id dead so the picker self-heals",
            com.openminis.app.provider.ZenFreeLaneHealth.isDead("mimo-v2.5-free"),
        )
    }

    @Test
    fun `a bare endpoint-unavailable 503 records the model id`() {
        // exo-free's shape: no outer {"type":"error"} envelope, so the
        // structured sniffer misses it and the generic 503 mapping answers —
        // the dead-record hook must still fire.
        val provider = zenProvider("exo-free")
        provider.mapHttpError(
            503,
            """{"error":{"type":"server_error","message":"Error from provider (Console): Upstream request failed: Endpoint is unavailable."}}""",
        )
        assertTrue(com.openminis.app.provider.ZenFreeLaneHealth.isDead("exo-free"))
    }

    @Test
    fun `a quota refusal does not record the model id`() {
        val provider = zenProvider("big-pickle")
        provider.mapHttpError(
            429,
            """{"type":"error","error":{"type":"FreeUsageLimitError","message":"Rate limit exceeded. Please try again later."}}""",
        )
        assertFalse(com.openminis.app.provider.ZenFreeLaneHealth.isDead("big-pickle"))
    }
}
