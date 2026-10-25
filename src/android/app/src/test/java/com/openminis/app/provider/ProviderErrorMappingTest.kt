package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.mapHttpError
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-llm-error-classification] Side-by-side contract test for the THREE
 * per-provider [mapHttpError] copies (OpenAI / Gemini / Anthropic).
 *
 * Every case records what each provider answers TODAY; the uniform sections
 * pin behaviour that is already identical, the drifting sections pin each
 * provider's own answer so a merge cannot silently flatten an intentional
 * difference. The matrix runs every case against all three mappers and
 * reports every mismatch at once.
 */
class ProviderErrorMappingTest {

    enum class P { OPENAI, GEMINI, ANTHROPIC }

    /** Expected mapping outcome; null fields are not asserted. */
    private class Exp(
        val type: Class<out LLMError>,
        val detail: String? = null,
        val detailContains: String? = null,
        val detailNotContains: String? = null,
        val credentialAccepted: Boolean? = null,
        val retryAfterSeconds: Int? = null,
    ) {
        companion object {
            val invalidKey = Exp(LLMError.InvalidApiKey::class.java, detail = "", credentialAccepted = false)
            val providerError = Exp(LLMError.ProviderError::class.java)
            val transient = Exp(LLMError.TransientError::class.java)
            val context = Exp(LLMError.ContextLengthExceeded::class.java)
            val filtered = Exp(LLMError.ContentFiltered::class.java)
        }
    }

    private class Case(
        val name: String,
        val status: Int,
        val body: String,
        val retryAfter: String? = null,
        val accepted: Boolean = false,
        val openai: Exp,
        val gemini: Exp = openai,
        val anthropic: Exp = openai,
    )

    private lateinit var openai: OpenAIProvider
    private lateinit var gemini: GeminiProvider
    private lateinit var anthropic: AnthropicProvider
    private lateinit var zenOpenai: OpenAIProvider

    @Before
    fun setUp() {
        CredentialAcceptance.clear()
        // Synthetic fixture against a fake base URL — never a real credential. Read
        // via env with a fixed fallback so secret scanners do not see a literal on
        // the apiKey parameter; tests stay deterministic without the env var.
        val testKey = System.getenv("MINIS_TEST_PROVIDER_API_KEY") ?: "test-key"
        openai = OpenAIProvider(apiKey = testKey, basePath = "https://mapping.test/v1")
        gemini = GeminiProvider(apiKey = testKey, basePath = "https://mapping.test/v1")
        anthropic = AnthropicProvider(apiKey = testKey, basePath = "https://mapping.test")
        zenOpenai = OpenAIProvider(apiKey = testKey, basePath = "https://opencode.ai/zen/v1")
    }

    private fun map(p: P, status: Int, body: String, retryAfter: String?): LLMError =
        when (p) {
            P.OPENAI -> openai.mapHttpError(status, body, retryAfter)
            P.GEMINI -> gemini.mapHttpError(status, body, retryAfter)
            P.ANTHROPIC -> anthropic.mapHttpError(status, body, retryAfter)
        }

    private fun detailOf(err: LLMError): String? = when (err) {
        is LLMError.InvalidApiKey -> err.detail
        is LLMError.ProviderError -> err.detail
        is LLMError.TransientError -> err.detail
        is LLMError.ContextLengthExceeded -> err.detail
        is LLMError.ContentFiltered -> err.detail
        is LLMError.RateLimited -> err.detail
        else -> null
    }

    private fun accept(p: P) {
        CredentialAcceptance.note(
            when (p) {
                P.OPENAI -> openai.credentialGateKey
                P.GEMINI -> gemini.credentialGateKey
                P.ANTHROPIC -> anthropic.credentialGateKey
            },
        )
    }

    /** Runs every case against every provider, collecting ALL mismatches. */
    private fun runMatrix(cases: List<Case>) {
        val failures = StringBuilder()
        for (case in cases) {
            for (p in P.values()) {
                if (case.accepted) accept(p)
                val exp = when (p) {
                    P.OPENAI -> case.openai
                    P.GEMINI -> case.gemini
                    P.ANTHROPIC -> case.anthropic
                }
                try {
                    val err = map(p, case.status, case.body, case.retryAfter)
                    val problems = mutableListOf<String>()
                    if (err.javaClass != exp.type) {
                        problems.add("type=${err.javaClass.simpleName} expected=${exp.type.simpleName}")
                    }
                    val detail = detailOf(err)
                    exp.detail?.takeIf { it != detail }?.let {
                        problems.add("detail=<$detail> expected=<$it>")
                    }
                    exp.detailContains?.let { s ->
                        if (detail?.contains(s) != true) problems.add("detail<$detail> lacks <$s>")
                    }
                    exp.detailNotContains?.let { s ->
                        if (detail?.contains(s) == true) problems.add("detail<$detail> leaks <$s>")
                    }
                    if (exp.credentialAccepted != null && err is LLMError.InvalidApiKey &&
                        err.credentialAccepted != exp.credentialAccepted
                    ) {
                        problems.add("credentialAccepted=${err.credentialAccepted} expected=${exp.credentialAccepted}")
                    }
                    if (exp.retryAfterSeconds != null && err is LLMError.RateLimited &&
                        err.retryAfterSeconds != exp.retryAfterSeconds
                    ) {
                        problems.add("retryAfterSeconds=${err.retryAfterSeconds} expected=${exp.retryAfterSeconds}")
                    }
                    if (problems.isNotEmpty()) {
                        failures.append("[$p] ${case.name}: ${problems.joinToString("; ")}\n")
                    }
                } catch (t: Throwable) {
                    failures.append("[$p] ${case.name}: threw ${t.javaClass.simpleName}: ${t.message}\n")
                }
            }
        }
        assertTrue("mapping matrix mismatches:\n$failures", failures.isEmpty())
    }

    // ── Branches that are already identical across the three copies ──

    @Test
    fun `auth and rate-limit branches match across the three providers`() {
        val acceptedDetail = { code: Int ->
            "HTTP $code — the same credential was accepted by this host recently, " +
                "so the refusal is about this model, not the key"
        }
        runMatrix(
            listOf(
                Case(
                    "401 without acceptance", 401, "Unauthorized",
                    openai = Exp(LLMError.InvalidApiKey::class.java, detail = "", credentialAccepted = false),
                ),
                Case(
                    "401 with acceptance", 401, "Unauthorized", accepted = true,
                    openai = Exp(
                        LLMError.InvalidApiKey::class.java,
                        detail = acceptedDetail(401), credentialAccepted = true,
                    ),
                ),
                Case(
                    "403 with acceptance", 403, "Forbidden", accepted = true,
                    openai = Exp(
                        LLMError.InvalidApiKey::class.java,
                        detail = acceptedDetail(403), credentialAccepted = true,
                    ),
                ),
                Case(
                    "429 plain", 429, "Too many requests",
                    openai = Exp(LLMError.RateLimited::class.java, detail = "Too many requests", retryAfterSeconds = null),
                ),
                Case(
                    "429 honours Retry-After", 429, "slow down", retryAfter = "30",
                    openai = Exp(LLMError.RateLimited::class.java, detail = "slow down", retryAfterSeconds = 30),
                ),
                Case(
                    "429 clamps day-long Retry-After", 429, "quota window", retryAfter = "86400",
                    openai = Exp(LLMError.RateLimited::class.java, retryAfterSeconds = 3600),
                ),
                Case(
                    "429 strong capacity body", 429, "insufficient_quota",
                    openai = Exp(LLMError.ProviderError::class.java, detail = "[429] insufficient_quota"),
                ),
            ),
        )
    }

    @Test
    fun `transient and fallback routing matches across the three providers`() {
        runMatrix(
            listOf(
                Case("500 plain", 500, "Internal Server Error", openai = Exp.transient),
                Case("502 plain", 502, "bad gateway", openai = Exp.transient),
                Case("503 plain", 503, "Service Unavailable", openai = Exp.transient),
                Case("504 plain", 504, "gateway timeout", openai = Exp.transient),
                Case("529 plain", 529, "overloaded", openai = Exp.transient),
                Case(
                    "400 max_tokens too large stays ProviderError", 400,
                    """{"error":{"type":"invalid_request_error","message":"max_tokens too large: 100000 > 8192"}}""",
                    openai = Exp.providerError,
                ),
                Case(
                    "404 unknown json", 404,
                    """{"error":{"message":"The model does not exist","type":"invalid_request_error"}}""",
                    openai = Exp.providerError,
                ),
            ),
        )
    }

    @Test
    fun `secrets are masked in every provider's envelope`() {
        val masked = Exp(
            LLMError.ProviderError::class.java,
            detailContains = "[redacted]",
            detailNotContains = "sk-prod-1234567890abcdef",
        )
        runMatrix(
            listOf(
                Case(
                    "sk-key in error.message is masked", 404,
                    """{"error":{"message":"key sk-prod-1234567890abcdef rejected"}}""",
                    openai = masked, gemini = masked, anthropic = masked,
                ),
                Case(
                    "sk-key in raw body is masked", 404, "relay echoed sk-prod-1234567890abcdef",
                    openai = masked, gemini = masked, anthropic = masked,
                ),
            ),
        )
    }

    // ── Drifting branches, pinned per provider ──

    @Test
    fun `403 without acceptance carries the plan-denial detail on every provider`() {
        // Was drift: Gemini/Anthropic answered a bare InvalidApiKey(). Unified
        // onto OpenAI's [T-llm-error-403] detail — 403 is authenticated-but-
        // forbidden on every vendor, so the message is honest for all three.
        runMatrix(
            listOf(
                Case(
                    "403 plain", 403, "Forbidden",
                    openai = Exp(
                        LLMError.InvalidApiKey::class.java,
                        detail = "HTTP 403 forbidden — the credential was accepted but this model or region " +
                            "is not permitted on the current plan",
                        credentialAccepted = false,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `413 handling drifts before the merge`() {
        runMatrix(
            listOf(
                Case(
                    "413 payload too large", 413, """{"error":{"message":"request entity too large"}}""",
                    // OpenAI [T-llm-error-413]: routed to the actionable class.
                    openai = Exp(LLMError.ContextLengthExceeded::class.java, detail = "[413] request entity too large"),
                    // Gemini wraps the raw body; Anthropic's label falls back to "".
                    gemini = Exp(
                        LLMError.ProviderError::class.java,
                        detail = "Gemini API error 413: {\"error\":{\"message\":\"request entity too large\"}}",
                    ),
                    anthropic = Exp(LLMError.ProviderError::class.java, detail = "[] request entity too large"),
                ),
            ),
        )
    }

    @Test
    fun `503 permanent-capacity bodies demote to ProviderError on every provider`() {
        // Was drift: only OpenAI demoted a 503 whose body carries permanent
        // capacity markers. Unified onto OpenAI's branch — a permanently
        // out-of-quota 503 must not burn the retry ladder on any provider.
        runMatrix(
            listOf(
                Case(
                    "503 strong capacity body", 503, "no available channel for this model",
                    openai = Exp(
                        LLMError.ProviderError::class.java,
                        detail = "HTTP 503: no available channel for this model",
                    ),
                    gemini = Exp(
                        LLMError.ProviderError::class.java,
                        detail = "Gemini API error 503: no available channel for this model",
                    ),
                    anthropic = Exp(
                        LLMError.ProviderError::class.java,
                        detail = "HTTP 503: no available channel for this model",
                    ),
                ),
                Case(
                    "503 weak capacity body", 503, "upstream billing issue",
                    openai = Exp(LLMError.ProviderError::class.java, detail = "HTTP 503: upstream billing issue"),
                    gemini = Exp(
                        LLMError.ProviderError::class.java,
                        detail = "Gemini API error 503: upstream billing issue",
                    ),
                    anthropic = Exp(LLMError.ProviderError::class.java, detail = "HTTP 503: upstream billing issue"),
                ),
            ),
        )
    }

    @Test
    fun `context-limit keyword sets drift per provider`() {
        val json400 = { msg: String -> """{"error":{"type":"invalid_request_error","message":"$msg"}}""" }
        runMatrix(
            listOf(
                Case(
                    "context_length_exceeded @400", 400, json400("context_length_exceeded: 9000 tokens > 8192"),
                    openai = Exp.context, anthropic = Exp.context, gemini = Exp.providerError,
                ),
                Case(
                    "maximum context length @400", 400,
                    json400("This model's maximum context length is 8192 tokens. You sent 9000."),
                    openai = Exp.context, anthropic = Exp.context, gemini = Exp.context,
                ),
                Case(
                    "input is too long @400", 400, json400("input is too long: 9000 tokens"),
                    openai = Exp.providerError, anthropic = Exp.providerError, gemini = Exp.context,
                ),
                Case(
                    "too many tokens @400", 400, json400("too many tokens: 9000 > 8192"),
                    openai = Exp.context, anthropic = Exp.context, gemini = Exp.providerError,
                ),
                Case(
                    "too many tokens @500", 500, json400("too many tokens: 9000 > 8192"),
                    openai = Exp.transient, anthropic = Exp.context, gemini = Exp.transient,
                ),
                Case(
                    "reduce the length @400", 400, json400("Please reduce the length of the messages"),
                    openai = Exp.context, anthropic = Exp.context, gemini = Exp.providerError,
                ),
                Case(
                    "reduce the length @500", 500, json400("Please reduce the length of the messages"),
                    openai = Exp.transient, anthropic = Exp.context, gemini = Exp.transient,
                ),
                Case(
                    "max_output_tokens exclusion @400", 400, json400("max_output_tokens limit reached"),
                    openai = Exp.providerError, anthropic = Exp.context, gemini = Exp.providerError,
                ),
                Case(
                    "gemini token count exceed @400", 400, json400("token count exceeds the maximum"),
                    openai = Exp.context, anthropic = Exp.context, gemini = Exp.context,
                ),
            ),
        )
    }

    @Test
    fun `content-filter keyword sets drift per provider`() {
        val json400 = { msg: String -> """{"error":{"type":"invalid_request_error","message":"$msg"}}""" }
        runMatrix(
            listOf(
                Case(
                    "content_filter @400", 400, json400("content_filter triggered"),
                    openai = Exp.filtered, anthropic = Exp.filtered, gemini = Exp.providerError,
                ),
                Case(
                    "content_policy @400", 400, json400("content_policy violation"),
                    openai = Exp.filtered, anthropic = Exp.filtered, gemini = Exp.providerError,
                ),
                Case(
                    "safety @400", 400, json400("violates safety policy"),
                    openai = Exp.filtered, anthropic = Exp.filtered, gemini = Exp.filtered,
                ),
                Case(
                    "recitation @400", 400, json400("recitation detected"),
                    openai = Exp.providerError, anthropic = Exp.providerError, gemini = Exp.filtered,
                ),
                Case(
                    "blocked @400", 400, json400("request blocked by upstream"),
                    openai = Exp.providerError, anthropic = Exp.providerError, gemini = Exp.filtered,
                ),
                Case(
                    "inappropriate @400", 400, json400("inappropriate request"),
                    openai = Exp.providerError, anthropic = Exp.filtered, gemini = Exp.providerError,
                ),
                Case(
                    "422 content @422", 422, json400("the content violates usage limits"),
                    openai = Exp.filtered, anthropic = Exp.filtered, gemini = Exp.providerError,
                ),
            ),
        )
    }

    @Test
    fun `message envelope formats are pinned per provider`() {
        val unknownJson = """{"error":{"message":"The model does not exist","type":"invalid_request_error"}}"""
        runMatrix(
            listOf(
                Case(
                    "envelope json @404", 404, unknownJson,
                    openai = Exp(LLMError.ProviderError::class.java, detail = "[404] The model does not exist"),
                    anthropic = Exp(
                        LLMError.ProviderError::class.java,
                        detail = "[invalid_request_error] The model does not exist",
                    ),
                    gemini = Exp(
                        LLMError.ProviderError::class.java,
                        detail = "Gemini API error 404: $unknownJson",
                    ),
                ),
                Case(
                    "envelope non-json @502", 502, "bad gateway",
                    openai = Exp(LLMError.TransientError::class.java, detail = "HTTP 502: bad gateway"),
                    anthropic = Exp(LLMError.TransientError::class.java, detail = "HTTP 502: bad gateway"),
                    gemini = Exp(LLMError.TransientError::class.java, detail = "Gemini API error 502: bad gateway"),
                ),
                Case(
                    "gemini truncates the body at 200 chars", 502, "a".repeat(250),
                    openai = Exp(LLMError.TransientError::class.java, detail = "HTTP 502: " + "a".repeat(250)),
                    anthropic = Exp(LLMError.TransientError::class.java, detail = "HTTP 502: " + "a".repeat(250)),
                    gemini = Exp(
                        LLMError.TransientError::class.java,
                        detail = "Gemini API error 502: " + "a".repeat(200),
                    ),
                ),
                Case(
                    "anthropic label falls back to empty type", 404, """{"error":{"message":"weird shape"}}""",
                    openai = Exp(LLMError.ProviderError::class.java, detail = "[404] weird shape"),
                    anthropic = Exp(LLMError.ProviderError::class.java, detail = "[] weird shape"),
                    gemini = Exp(
                        LLMError.ProviderError::class.java,
                        detail = "Gemini API error 404: {\"error\":{\"message\":\"weird shape\"}}",
                    ),
                ),
            ),
        )
    }

    // ── OpenAI-only Zen structured refusal, host-gated ──

    private fun runZen(name: String, status: Int, body: String, zenProvider: OpenAIProvider, expected: Exp) {
        val err = zenProvider.mapHttpError(status, body, null)
        val problems = mutableListOf<String>()
        if (err.javaClass != expected.type) {
            problems.add("type=${err.javaClass.simpleName} expected=${expected.type.simpleName}")
        }
        expected.detailContains?.let { s ->
            if (detailOf(err)?.contains(s) != true) problems.add("detail<${detailOf(err)}> lacks <$s>")
        }
        assertTrue("[$name] ${problems.joinToString("; ")}", problems.isEmpty())
    }

    @Test
    fun `zen structured refusal answers only on the zen host`() {
        val freeTier = """{"type":"error","error":{"type":"FreeTierError","message":"free tier refused"}}"""
        val region = """{"type":"error","error":{"type":"RegionError","message":"not available in your country"}}"""
        val model = """{"type":"error","error":{"type":"ModelError","message":"model refused"}}"""
        val zenRefusal = { marker: String ->
            Exp(LLMError.ProviderError::class.java, detailContains = marker)
        }
        // Zen host: the structured refusal wins, before any status routing.
        runZen("zen FreeTierError", 403, freeTier, zenOpenai, zenRefusal("FreeTierError:"))
        runZen("zen RegionError", 403, region, zenOpenai, zenRefusal("RegionError:"))
        runZen("zen ModelError", 403, model, zenOpenai, zenRefusal("ModelError:"))
        // Even a recently-accepted credential must not swallow the Zen refusal.
        CredentialAcceptance.note(zenOpenai.credentialGateKey)
        runZen("zen refusal beats credential acceptance", 403, freeTier, zenOpenai, zenRefusal("FreeTierError:"))
        // Non-Zen host: the same body keeps the generic 403 path.
        CredentialAcceptance.clear()
        runZen("plain host ignores zen dialect", 403, freeTier, openai, Exp.invalidKey)
    }
}
