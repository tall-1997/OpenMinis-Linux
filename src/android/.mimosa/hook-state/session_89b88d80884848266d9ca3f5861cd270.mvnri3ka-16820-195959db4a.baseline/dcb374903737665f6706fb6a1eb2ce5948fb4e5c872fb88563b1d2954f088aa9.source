package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-llm-error-401-model-scope] A 401 must not be blamed on the key when the
 * app has already watched that key work.
 *
 * The reported failure: a relay served 200 for one model and 401 for the other
 * four — same credential, same minute, deterministic per model — because four
 * of its five upstream channels were broken. Its own `/v1/models` returned 200
 * throughout. The app mapped every one of those 401s to a bare
 * `InvalidApiKey`, so the UI said "Invalid API key" and the hint told the user
 * to check or regenerate a key that was never the problem.
 */
class CredentialAcceptanceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        CredentialAcceptance.clear()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        CredentialAcceptance.clear()
    }

    // ── the registry ───────────────────────────────────────────────────

    @Test
    fun `an acceptance is remembered and then expires`() {
        val key = ProviderKeyGate.credentialKey("https://relay.example/v1", "sk-1")
        assertFalse(CredentialAcceptance.isAccepted(key, nowMillis = 0L))

        CredentialAcceptance.note(key, nowMillis = 1_000L)
        assertTrue(CredentialAcceptance.isAccepted(key, nowMillis = 2_000L))
        assertTrue(
            "still valid just inside the TTL",
            CredentialAcceptance.isAccepted(key, nowMillis = 1_000L + CredentialAcceptance.TTL_MILLIS),
        )
        assertFalse(
            "a stale acceptance must not excuse a genuinely revoked key",
            CredentialAcceptance.isAccepted(key, nowMillis = 1_000L + CredentialAcceptance.TTL_MILLIS + 1),
        )
    }

    @Test
    fun `a blank credential is never accepted`() {
        // An unauthenticated local server that 401s has told us nothing about a
        // credential, so the honest "check your configuration" message must stay.
        CredentialAcceptance.note("", nowMillis = 0L)
        CredentialAcceptance.note(null, nowMillis = 0L)
        assertFalse(CredentialAcceptance.isAccepted(""))
        assertFalse(CredentialAcceptance.isAccepted(null))
    }

    @Test
    fun `acceptance is per credential and per host`() {
        val a = ProviderKeyGate.credentialKey("https://relay.example/v1", "sk-a")
        val b = ProviderKeyGate.credentialKey("https://relay.example/v1", "sk-b")
        val otherHost = ProviderKeyGate.credentialKey("https://other.example/v1", "sk-a")

        CredentialAcceptance.note(a, nowMillis = 0L)

        assertTrue(CredentialAcceptance.isAccepted(a, nowMillis = 1L))
        assertFalse("a different key at the same host proves nothing", CredentialAcceptance.isAccepted(b, nowMillis = 1L))
        assertFalse("the same key at a different host proves nothing", CredentialAcceptance.isAccepted(otherHost, nowMillis = 1L))
    }

    @Test
    fun `forget drops a rotated or deleted key`() {
        val key = ProviderKeyGate.credentialKey("https://relay.example/v1", "sk-1")
        CredentialAcceptance.note(key, nowMillis = 0L)
        assertTrue(CredentialAcceptance.isAccepted(key, nowMillis = 1L))
        CredentialAcceptance.forget(key)
        assertFalse(CredentialAcceptance.isAccepted(key, nowMillis = 1L))
    }

    // ── the credential scope is model-independent ──────────────────────

    @Test
    fun `credentialKey ignores the model, because the model is what varied`() {
        val flash = ProviderKeyGate.key("https://relay.example/v1", "sk-1", "deepseek-v4.1-flash")
        val pro = ProviderKeyGate.key("https://relay.example/v1", "sk-1", "deepseek-v4-pro-0813")

        assertNotEquals("gate buckets stay per model", flash, pro)
        assertEquals(
            "credential scope must collapse them",
            ProviderKeyGate.credentialScopeOf(flash),
            ProviderKeyGate.credentialScopeOf(pro),
        )
        assertEquals(
            ProviderKeyGate.credentialKey("https://relay.example/v1", "sk-1"),
            ProviderKeyGate.credentialScopeOf(flash),
        )
    }

    @Test
    fun `credentialKey is stable across path and case differences in the base`() {
        val a = ProviderKeyGate.credentialKey("https://Relay.Example/v1", "sk-1")
        val b = ProviderKeyGate.credentialKey("https://relay.example/v1/", "sk-1")
        val c = ProviderKeyGate.credentialKey("https://relay.example", "sk-1")
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `a pipe in a model id cannot shift the credential scope`() {
        // credentialScopeOf drops the last segment, so a '|' inside the model
        // would move the boundary. normalizeModel rewrites it away to keep the
        // derivation exact rather than merely usually-correct.
        val key = ProviderKeyGate.key("https://relay.example/v1", "sk-1", "weird|model")
        assertEquals(
            ProviderKeyGate.credentialKey("https://relay.example/v1", "sk-1"),
            ProviderKeyGate.credentialScopeOf(key),
        )
    }

    // ── the error the user actually sees ───────────────────────────────

    @Test
    fun `an unattributed 401 still says Invalid API key`() {
        val err = LLMError.InvalidApiKey()
        assertEquals("Invalid API key", err.message)
        assertEquals("Invalid API key", err.fallbackReason)
        assertTrue(err.actionableHint.contains("Check your API key"))
        assertFalse(err.credentialAccepted)
    }

    @Test
    fun `a model-scoped refusal does not tell the user their key is wrong`() {
        val err = LLMError.InvalidApiKey("HTTP 401 — model refused upstream", credentialAccepted = true)

        assertFalse(
            "message must not claim the key is invalid: ${err.message}",
            err.message!!.startsWith("Invalid API key"),
        )
        assertNotEquals("Invalid API key", err.fallbackReason)
        assertFalse(
            "hint must not send the user to regenerate a working key: ${err.actionableHint}",
            err.actionableHint.contains("Check your API key"),
        )
        assertTrue(
            "hint must point at the model: ${err.actionableHint}",
            err.actionableHint.contains("different model"),
        )
        // Still fallbackable, so a model group moves on to its next member.
        assertTrue(err.isFallbackable)
        assertFalse("retrying the same broken model cannot help", err.isRetryable)
    }

    @Test
    fun `the existing 403 plan-restriction hint is untouched`() {
        val err = LLMError.InvalidApiKey("HTTP 403 forbidden — model or region not permitted")
        assertTrue(
            "403 must keep its plan/access hint, got: ${err.actionableHint}",
            err.actionableHint.contains("model access settings"),
        )
        assertFalse(err.credentialAccepted)
        // No acceptance evidence, so the message keeps its historical shape.
        assertEquals("Invalid API key: HTTP 403 forbidden — model or region not permitted", err.message)
    }

    // ── end to end through the real classes ────────────────────────────

    @Test
    fun `a 401 after a successful catalog fetch is attributed to the model`() = runBlocking {
        val base = server.url("/").toString().trimEnd('/')
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = base,
        )

        // 1. The catalog fetch succeeds, which is the app's evidence that the
        //    credential is good at this host.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"object":"list","data":[{"id":"gpt-4o-mini","object":"model"}]}"""),
        )
        val models = OpenAIModelsApi.fetchModels("test-key", "$base/v1")
        assertTrue("catalog must have parsed, got $models", models.isNotEmpty())

        // 2. A chat call with the same credential then 401s — the relay's
        //    upstream channel for this model is broken, not the key.
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid token (request id: x)","type":"new_api_error"}}"""),
        )
        val err = try {
            provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 64).toList()
            null
        } catch (t: Throwable) {
            t
        }

        assertTrue("expected an LLMError, got $err", err is LLMError.InvalidApiKey)
        err as LLMError.InvalidApiKey
        assertTrue("401 must be attributed to the model, not the key", err.credentialAccepted)
        assertTrue(err.actionableHint.contains("different model"))
    }

    @Test
    fun `a 401 with no prior acceptance still blames the key`() = runBlocking {
        val base = server.url("/").toString().trimEnd('/')
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = base,
        )

        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Unauthorized"}}"""),
        )
        val err = try {
            provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 64).toList()
            null
        } catch (t: Throwable) {
            t
        }

        assertTrue("expected an LLMError, got $err", err is LLMError.InvalidApiKey)
        err as LLMError.InvalidApiKey
        assertFalse("no evidence of acceptance, so the historical mapping stands", err.credentialAccepted)
        assertEquals("Invalid API key", err.message)
    }
}
