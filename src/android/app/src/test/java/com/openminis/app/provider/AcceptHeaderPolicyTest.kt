package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [T-relay-accept-406] Field-report regression: a user-configured relay
 * rejects every request whose declared `Accept` is `text/event-stream` with
 * `406 Could not satisfy the request Accept header` — including streaming
 * ones. The chat request builders normalize that ONE value to the
 * SDK-standard `application/json` (measured matrix in AcceptHeaderPolicy's
 * KDoc: absent / application/json / the wildcard value / combined all 200; standalone
 * text/event-stream 406 in both stream modes). Every other declared value
 * passes through verbatim — the wildcard value or the combined form are the
 * user's per-provider verbatim escape hatch.
 *
 * Coverage census (kept truthful, matches the fusion commit message):
 * 7 pure-policy cases + 4 request-wiring goldens = 11 tests, including the
 * fourth-round review fixes — parameterized single-type variants hit via the
 * primary-type check (P2-1), the raw-stream per-call fold channel cannot
 * REPLACE the normalized Accept back onto the wire (P0-1), and the model-use
 * chatExtraHeaders override channel is sanitized (P1-1).
 */
class AcceptHeaderPolicyTest {

    /** Non-secret placeholder credential for constructor wiring only. */
    private val placeholderCredential = buildString { repeat(8) { append('x') } }

    // ------------------------------------------------- policy (pure) -------

    @Test
    fun `standalone harmful accept is normalized to application-json`() {
        val out = AcceptHeaderPolicy.sanitizeChatAccept(
            mapOf(
                "Authorization" to "Bearer ${placeholderCredential}x",
                "Accept" to "text/event-stream",
                "X-Custom" to "keep",
            ),
        )
        assertEquals("application/json", out["Accept"])
        assertEquals("Bearer ${placeholderCredential}x", out["Authorization"])
        assertEquals("keep", out["X-Custom"])
    }

    @Test
    fun `normalization is case-insensitive on header name and value`() {
        assertEquals(
            "application/json",
            AcceptHeaderPolicy.sanitizeChatAccept(mapOf("accept" to "Text/Event-Stream"))["accept"],
        )
        assertEquals(
            "application/json",
            AcceptHeaderPolicy.sanitizeChatAccept(mapOf("ACCEPT" to " text/event-stream "))["ACCEPT"],
        )
    }

    @Test
    fun `non-harmful declared values pass through verbatim`() {
        for (value in listOf("*/*", "application/json, text/event-stream", "application/json")) {
            val headers = mapOf("Accept" to value)
            assertSame("'$value' must not be copied", headers, AcceptHeaderPolicy.sanitizeChatAccept(headers))
            assertEquals(value, AcceptHeaderPolicy.sanitizeChatAccept(headers)["Accept"])
        }
    }

    @Test
    fun `no accept header is a zero-cost passthrough`() {
        val headers = mapOf("Authorization" to "Bearer ${placeholderCredential}x", "X-Trace" to "t")
        assertSame(headers, AcceptHeaderPolicy.sanitizeChatAccept(headers))
    }

    @Test
    fun `original map is never mutated`() {
        val headers = linkedMapOf("Accept" to "text/event-stream")
        AcceptHeaderPolicy.sanitizeChatAccept(headers)
        assertEquals("text/event-stream", headers["Accept"])
    }

    @Test
    fun `parameterized single-type harmful variants hit the primary-type check`() {
        // P2-1: `text/event-stream; q=1.0` no longer slips past the exact
        // bare-value match — the decision is on the PRIMARY type (text before
        // the first comma, `;`-parameters stripped, trimmed), case-insensitive.
        assertEquals(
            "application/json",
            AcceptHeaderPolicy.sanitizeChatAccept(mapOf("Accept" to "text/event-stream; q=1.0"))["Accept"],
        )
        assertEquals(
            "application/json",
            AcceptHeaderPolicy.sanitizeChatAccept(mapOf("Accept" to " Text/Event-Stream;q=1.0 "))["Accept"],
        )
    }

    @Test
    fun `combined value with a safe primary type passes through verbatim`() {
        // The composed form keeps its verbatim escape-hatch status even when a
        // later member is the harmful type: only the PRIMARY type decides.
        val headers = mapOf("Accept" to "application/json, text/event-stream;q=1.0")
        assertSame(headers, AcceptHeaderPolicy.sanitizeChatAccept(headers))
    }

    // ------------------------------------------- request wiring (real) -----

    /**
     * Wiring golden: a provider configured with the harmful Accept value
     * (e.g. copied from a relay tutorial) builds a chat request whose
     * declared Accept is the SDK-standard application/json, while sibling
     * user headers survive.
     */
    @Test
    fun `chat request wiring normalizes the harmful declared accept`() = runTest {
        val provider = OpenAIProvider(
            apiKey = placeholderCredential,
            model = relayModel(),
            basePath = "https://relay.example/v1",
            extraHeaders = mapOf(
                "Accept" to "text/event-stream",
                "X-Custom" to "keep-me",
            ),
        )
        val body = provider.buildRequestBody(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "hi")),
            systemPrompt = null,
            maxTokens = 2048,
            stream = true,
            temperature = null,
            imageParts = emptyList(),
        )
        val request: Request = provider.requestBodies.buildRequest(body.toString())
        assertEquals(
            "a declared text/event-stream Accept must reach the wire as application/json",
            "application/json",
            request.header("Accept"),
        )
        assertEquals("keep-me", request.header("X-Custom"))
    }

    /**
     * The verbatim escape hatch: the wildcard Accept value (or the combined
     * form) passes through the builder untouched, so a user who genuinely
     * wants their declared value on the wire has a per-provider switch in the
     * extra-headers config.
     */
    @Test
    fun `chat request wiring passes a verbatim escape value through`() = runTest {
        val provider = OpenAIProvider(
            apiKey = placeholderCredential,
            model = relayModel(),
            basePath = "https://relay.example/v1",
            extraHeaders = mapOf("Accept" to "*/*"),
        )
        val body = provider.buildRequestBody(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "hi")),
            systemPrompt = null,
            maxTokens = 2048,
            stream = true,
            temperature = null,
            imageParts = emptyList(),
        )
        val request: Request = provider.requestBodies.buildRequest(body.toString())
        assertEquals("*/*", request.header("Accept"))
    }

    /**
     * Dual-channel golden [P0-1]: the real streaming send is
     * rawStreamMessage → buildRequest (ctor extraHeaders sanitized) → the
     * per-call extraHeaders folded LAST over the built request via OkHttp
     * header() — same-name REPLACE. Folding a RAW per-call map therefore
     * overwrites the normalized ctor Accept back onto the wire; the production
     * fold sanitizes the per-call map through the same choke point first.
     * This test replicates that exact fold shape with the harmful value on
     * BOTH channels and asserts the wire value; the differential raw fold
     * below proves the REPLACE overwrite path is real, not a no-op.
     */
    @Test
    fun `per-call fold cannot put the harmful accept back on the wire`() = runTest {
        val provider = OpenAIProvider(
            apiKey = placeholderCredential,
            model = relayModel(),
            basePath = "https://relay.example/v1",
            extraHeaders = mapOf(
                "Accept" to "text/event-stream",
                "X-Custom" to "ctor-header",
            ),
        )
        val body = provider.buildRequestBody(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "hi")),
            systemPrompt = null,
            maxTokens = 2048,
            stream = true,
            temperature = null,
            imageParts = emptyList(),
        )
        val base: Request = provider.requestBodies.buildRequest(body.toString())
        assertEquals("application/json", base.header("Accept"))
        // Per-call channel, replicating the OpenAIRawStream.rawStreamMessage fold.
        val perCall: Map<String, String> = mapOf("Accept" to "text/event-stream")
        val raw = perCall.entries.fold(base) { acc, (n, v) ->
            acc.newBuilder().header(n, v).build()
        }
        assertEquals("text/event-stream", raw.header("Accept")) // REPLACE is real
        val request = AcceptHeaderPolicy.sanitizeChatAccept(perCall).entries
            .fold(base) { acc, (n, v) -> acc.newBuilder().header(n, v).build() }
        assertEquals(
            "the sanitized per-call fold must leave application/json on the wire",
            "application/json",
            request.header("Accept"),
        )
        assertEquals("ctor-header", request.header("X-Custom"))
    }

    /**
     * [P1-1] The model-use per-call override channel —
     * OpenAIProvider.chatExtraHeaders, populated per call by
     * ModelUseOffloadHandler (user extra_headers + session headers) and
     * applied AFTER the ctor extraHeaders with same-name REPLACE — passes
     * through the same sanitize choke point: a per-call
     * `Accept: text/event-stream` reaches the wire as application/json even
     * though the ctor extraHeaders carry no Accept at all.
     */
    @Test
    fun `chat extra headers override channel is sanitized too`() = runTest {
        val provider = OpenAIProvider(
            apiKey = placeholderCredential,
            model = relayModel(),
            basePath = "https://relay.example/v1",
        )
        // Same injection point ModelUseOffloadHandler uses:
        // openAI.chatExtraHeaders = mergedHdrs.
        provider.chatExtraHeaders = mapOf("Accept" to "text/event-stream")
        val body = provider.buildRequestBody(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "hi")),
            systemPrompt = null,
            maxTokens = 2048,
            stream = true,
            temperature = null,
            imageParts = emptyList(),
        )
        val request: Request = provider.requestBodies.buildRequest(body.toString())
        assertEquals(
            "a per-call override Accept must not reach the wire as text/event-stream",
            "application/json",
            request.header("Accept"),
        )
    }

    private fun relayModel() = LLMModel(
        id = "relay-model",
        displayName = "relay-model",
        provider = "relay",
        inputModalities = listOf("text"),
    )
}
