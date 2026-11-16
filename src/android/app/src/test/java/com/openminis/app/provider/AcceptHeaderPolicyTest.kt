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

    private fun relayModel() = LLMModel(
        id = "relay-model",
        displayName = "relay-model",
        provider = "relay",
        inputModalities = listOf("text"),
    )
}
