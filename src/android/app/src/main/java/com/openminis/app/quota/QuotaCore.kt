package com.openminis.app.quota

import com.openminis.app.network.applyAppTrustStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

// [T-provider-quota] Vendor detection, the adapter contract / registry, and the
// shared plumbing every per-provider adapter file builds on. Port of the
// shared half of iOS QuotaAdapters.swift.
//
// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (quota/QuotaAdapters.kt), split so each vendor adapter lives in its own
// QuotaAdapter<Provider>.kt file — adding a provider means adding one file and
// one QuotaAdapterRegistry branch. The only difference from upstream:
// QuotaResponseShape stays here instead of sitting beside the Kimi Code
// adapter because it is a generic diagnostic, not vendor logic. The transport
// routes through this tree's network/NetworkHttp exactly like upstream.
//
// Works on [QuotaInstanceDescriptor] instead of ProviderInstance so the JVM
// unit tests exercise it without the repository.
//
// Safety rules every adapter follows:
// - A credential is only ever sent to the vendor's own fixed HTTPS host, the
//   same host the instance is already configured to talk to. No adapter builds
//   a request URL from user-supplied path components.
// - Response bodies are parsed into the structured model and then dropped;
//   nothing raw is persisted or logged. Personal fields (SiliconFlow's name /
//   email) are never read.

// MARK: - Instance descriptor

/** The quota-relevant, secret-free facts about one provider instance. */
data class QuotaInstanceDescriptor(
    val instanceId: String,
    val label: String,
    val providerKind: ProviderKind,
    val isOAuth: Boolean,
    val isEnabled: Boolean,
    val customBaseURL: String?,
    /**
     * [ISS-031] The instance is on the OpenCode channel - decided by the app's
     * own OpenCode detection (QuotaStore.descriptor, via OpenCodeSessionHeader),
     * carried in so this file does not grow a second one.
     */
    val isOpenCodeChannel: Boolean = false,
) {
    @Suppress("EnumEntryName")
    enum class ProviderKind {
        openAI, anthropic, gemini, antigravity, openRouter, openAIResponses,
        xAI, kimiCode, githubCopilot, unsupported,
    }

    val customHost: String?
        get() {
            val raw = customBaseURL?.trim()
            if (raw.isNullOrEmpty()) return null
            val host = runCatching { URI(raw).host }.getOrNull()?.lowercase()
            return host?.takeIf { it.isNotEmpty() }
        }

    val capabilityKey: QuotaCapabilityKey
        get() = QuotaCapabilityKey(providerKind.name, QuotaCapabilityKey.normalize(customBaseURL))
}

// MARK: - Vendor detection

object QuotaVendorDetector {
    fun vendor(d: QuotaInstanceDescriptor): QuotaVendor {
        if (d.providerKind == QuotaInstanceDescriptor.ProviderKind.unsupported) return QuotaVendor.unknown
        if (d.isOpenCodeChannel) return QuotaVendor.openCode
        val host = d.customHost
        if (host != null) {
            vendorForHost(host)?.let { return it }
            // A custom endpoint on the vendor's own public host behaves like the default.
            if (host == "api.openai.com") return if (d.isOAuth) QuotaVendor.codexSubscription else QuotaVendor.openAIPlatform
            if (host == "api.anthropic.com") return if (d.isOAuth) QuotaVendor.claudeSubscription else QuotaVendor.anthropicPlatform
            if (host == "generativelanguage.googleapis.com") return QuotaVendor.geminiAPIKey
            return QuotaVendor.customRelay
        }
        return when (d.providerKind) {
            QuotaInstanceDescriptor.ProviderKind.openAI, QuotaInstanceDescriptor.ProviderKind.openAIResponses ->
                if (d.isOAuth) QuotaVendor.codexSubscription else QuotaVendor.openAIPlatform
            QuotaInstanceDescriptor.ProviderKind.anthropic ->
                if (d.isOAuth) QuotaVendor.claudeSubscription else QuotaVendor.anthropicPlatform
            QuotaInstanceDescriptor.ProviderKind.gemini ->
                if (d.isOAuth) QuotaVendor.geminiCodeAssist else QuotaVendor.geminiAPIKey
            QuotaInstanceDescriptor.ProviderKind.antigravity -> QuotaVendor.antigravity
            QuotaInstanceDescriptor.ProviderKind.openRouter -> QuotaVendor.openRouter
            QuotaInstanceDescriptor.ProviderKind.xAI -> QuotaVendor.xAI
            QuotaInstanceDescriptor.ProviderKind.kimiCode -> QuotaVendor.kimiCode
            QuotaInstanceDescriptor.ProviderKind.githubCopilot -> QuotaVendor.githubCopilot
            QuotaInstanceDescriptor.ProviderKind.unsupported -> QuotaVendor.unknown
        }
    }

    fun vendorForHost(host: String): QuotaVendor? = when (host) {
        "api.deepseek.com" -> QuotaVendor.deepSeek
        "api.moonshot.cn", "api.moonshot.ai" -> QuotaVendor.moonshot
        "api.siliconflow.cn", "api.siliconflow.com" -> QuotaVendor.siliconFlow
        "openrouter.ai" -> QuotaVendor.openRouter
        // [ISS-031] Kimi Code: China and overseas hosts (both in the official
        // Kimi Code docs; both answer 401, not 404, on /coding/v1/usages).
        "api.kimi.com", "api.kimi.ai" -> QuotaVendor.kimiCode
        "api.minimax.io", "api.minimaxi.com" -> QuotaVendor.miniMax
        "api.x.ai" -> QuotaVendor.xAI
        "api.xiaomimimo.com" -> QuotaVendor.xiaomiMiMo
        else -> null
    }
}

// MARK: - Transport

class QuotaHttpResponse(
    val status: Int,
    val body: ByteArray,
    /** Lower-cased names. */
    val headers: Map<String, String>,
)

interface QuotaHttpTransport {
    /** [headers] are extra request headers (e.g. the Claude CLI fingerprint); never logged. */
    suspend fun get(url: String, bearer: String, headers: Map<String, String> = emptyMap()): QuotaHttpResponse
}

/**
 * Dedicated client: 10 s timeout, no cache, no cookies - a balance response
 * must never land in a shared HTTP cache.
 *
 * [FEAT-261021-16] [netTag] is the provider instance's Settings > Network
 * tag (prov:<id>): a balance query takes the same route as the provider's
 * chat requests. One client for every instance; NetworkHttp derives a routed
 * one only when a route applies.
 */
class QuotaOkHttpTransport(private val netTag: String? = null) : QuotaHttpTransport {
    private val client: OkHttpClient get() = com.openminis.app.network.NetworkHttp.client(netTag, baseClient)

    private companion object {
        /**
         * [GH#258] Rebuilt when [AppTrustStore.generation] changes so a CA
         * imported from Settings takes effect on the next balance query, not
         * only after a process restart. The quota client is the one long-lived
         * provider client in the app.
         */
        @Volatile
        private var builtGeneration: Int = -1

        @Volatile
        private var built: OkHttpClient? = null

        val baseClient: OkHttpClient
            get() {
                val generation = com.openminis.app.network.AppTrustStore.generation
                built?.let { if (builtGeneration == generation) return it }
                synchronized(this) {
                    built?.let { if (builtGeneration == generation) return it }
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .callTimeout(20, TimeUnit.SECONDS)
                        .cache(null)
                        // [GH#258][GH#287] Balance queries share the provider's
                        // host, so they must trust the same user-imported CA.
                        .applyAppTrustStore()
                        .build()
                    built = client
                    builtGeneration = generation
                    return client
                }
            }
    }

    override suspend fun get(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).get()
            for ((k, v) in headers) builder.header(k, v)
            builder.header("Authorization", "Bearer $bearer")
            builder.header("Accept", "application/json")
            client.newCall(builder.build()).execute().use { resp ->
                // Allow-list: only what the quota layer reads.
                val kept = HashMap<String, String>()
                resp.header("retry-after")?.let { kept["retry-after"] = it }
                resp.header("date")?.let { kept["date"] = it }
                QuotaHttpResponse(resp.code, resp.body?.bytes() ?: ByteArray(0), kept)
            }
        }
}

/** Thrown by adapters; converted to the persisted [QuotaError] by the store. */
class QuotaFetchError(
    val kind: QuotaError.Kind,
    val detail: String,
    val httpStatus: Int? = null,
    val retryAfter: Long? = null,
) : Exception(detail) {
    fun persisted(at: Long) = QuotaError(kind, detail, httpStatus, at, retryAfter)
}

object QuotaHttp {
    /** Maps a non-2xx response to an error; null for 2xx. */
    fun error(r: QuotaHttpResponse, endpoint: String, now: Long): QuotaFetchError? = when (r.status) {
        in 200..299 -> null
        401 -> QuotaFetchError(QuotaError.Kind.unauthorized, "HTTP 401 $endpoint", 401)
        403 -> QuotaFetchError(QuotaError.Kind.forbidden, "HTTP 403 $endpoint", 403)
        404 -> QuotaFetchError(QuotaError.Kind.notFound, "HTTP 404 $endpoint", 404)
        429 -> QuotaFetchError(
            QuotaError.Kind.rateLimited, "HTTP 429 $endpoint", 429, retryAfter(r.headers["retry-after"], now),
        )
        in 500..599 -> QuotaFetchError(
            QuotaError.Kind.server, "HTTP ${r.status} $endpoint", r.status, retryAfter(r.headers["retry-after"], now),
        )
        else -> QuotaFetchError(QuotaError.Kind.badResponse, "HTTP ${r.status} $endpoint", r.status)
    }

    /**
     * `Retry-After` as delta-seconds or an HTTP-date. Capped at one day so a
     * broken header cannot park a subject forever.
     */
    fun retryAfter(raw: String?, now: Long): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val cap = 24 * 3_600_000L
        s.toDoubleOrNull()?.let { secs ->
            if (secs >= 0) return now + minOf((secs * 1000).toLong(), cap)
        }
        val date = httpDate(s) ?: return null
        return minOf(maxOf(date, now), now + cap)
    }

    fun httpDate(raw: String?): Long? {
        if (raw == null) return null
        val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        f.timeZone = TimeZone.getTimeZone("GMT")
        return runCatching { f.parse(raw)?.time }.getOrNull()
    }

    /** server - local, when the response carries a `Date` header. */
    fun clockOffset(r: QuotaHttpResponse, now: Long): Long? = httpDate(r.headers["date"])?.let { it - now }

    val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun jsonObject(data: ByteArray, endpoint: String): JsonObject =
        runCatching { json.parseToJsonElement(data.decodeToString()) as? JsonObject }.getOrNull()
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "unreadable JSON $endpoint")
}

// Small JSON accessors (a missing or mistyped field is null, never a default).
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
internal fun JsonObject.dec(key: String): BigDecimal? = QuotaDecimalParser.parse(this[key])
internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

// MARK: - Credential-derived account key

object QuotaAccountKey {
    /**
     * First 16 hex chars of SHA-256(credential). Enough to tell keys apart and
     * to share pools between two instances holding the same key; not reversible.
     */
    fun derive(credential: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(credential.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** "sk-...a1b2" - safe to persist / show. */
    fun mask(credential: String): String {
        val trimmed = credential.trim()
        if (trimmed.length <= 8) return "…"
        val prefix = if (trimmed.startsWith("sk-")) "sk-" else ""
        return "$prefix…${trimmed.takeLast(4)}"
    }
}

// MARK: - Adapter contract & registry

class QuotaFetchContext(
    val descriptor: QuotaInstanceDescriptor,
    val subjectId: QuotaSubjectID,
    val credential: String,
    val transport: QuotaHttpTransport,
    val now: Long,
    /** Extra request headers the adapter needs (supplied by the store; never logged). */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** [T-quota-relay-probe] A relay's type detected by an earlier probe (within a day); null = probe now. */
    val relayKindHint: RelayKind? = null,
    /**
     * Send the request here instead of the vendor host. Only the Kimi Code
     * adapter reads it. [port-upstream-1.16] Upstream wires this from a
     * DEV_TOOLS-only debug file; this build has no such flag, so the store
     * always passes null.
     */
    val endpointOverride: String? = null,
)

/**
 * One active fetch. `isComplete == false` when part of a multi-endpoint fetch
 * failed: the store then merges without retiring pools it did not see.
 */
data class QuotaFetchOutcome(
    val snapshot: UpstreamQuotaSnapshot,
    val isComplete: Boolean,
    /** Errors of the parts that failed (for logging only). */
    val partialErrors: List<QuotaFetchError> = emptyList(),
    /** [T-quota-relay-probe] The relay type this fetch used (probed or hinted). */
    val relayKind: RelayKind? = null,
    /**
     * [ISS-031] Field names and value types of the response, never a value -
     * logged once per launch for an adapter whose response shape is not yet
     * confirmed by a captured reply (Kimi Code).
     */
    val responseShape: String? = null,
)

interface ProviderQuotaAdapter {
    val adapterId: String
    val vendor: QuotaVendor

    /** Static, no I/O. */
    fun capability(d: QuotaInstanceDescriptor): QuotaCapability

    /**
     * Active query -> one snapshot with ALL pools of the subject. One call per
     * subject; multiple currencies / windows come back from the same call.
     */
    suspend fun fetch(ctx: QuotaFetchContext): QuotaFetchOutcome
}

object QuotaAdapterRegistry {
    // [port-upstream-1.16] One branch per vendor; each adapter lives in its own
    // QuotaAdapter<Provider>.kt file beside this one.
    fun adapter(vendor: QuotaVendor): ProviderQuotaAdapter = when (vendor) {
        QuotaVendor.deepSeek -> DeepSeekQuotaAdapter
        QuotaVendor.moonshot -> MoonshotQuotaAdapter
        QuotaVendor.siliconFlow -> SiliconFlowQuotaAdapter
        QuotaVendor.openRouter -> OpenRouterQuotaAdapter
        QuotaVendor.claudeSubscription -> ClaudeSubscriptionQuotaAdapter
        QuotaVendor.codexSubscription -> CodexSubscriptionQuotaAdapter
        QuotaVendor.openAIPlatform, QuotaVendor.anthropicPlatform, QuotaVendor.geminiAPIKey ->
            UnsupportedQuotaAdapter(vendor, QuotaSubject.UnsupportedReason.passiveNotImplemented)
        QuotaVendor.antigravity ->
            UnsupportedQuotaAdapter(vendor, QuotaSubject.UnsupportedReason.passiveNotImplemented)
        QuotaVendor.xAI, QuotaVendor.xiaomiMiMo, QuotaVendor.openCode ->
            UnsupportedQuotaAdapter(vendor, QuotaSubject.UnsupportedReason.vendorHasNoQuotaAPI)
        QuotaVendor.kimiCode -> KimiCodeQuotaAdapter
        // [ISS-031] The endpoint is official but its response fields are not
        // documented, and the one known field is reported misnamed
        // (MiniMax-AI/MiniMax-M2#99): no parser until a reply confirms it.
        QuotaVendor.miniMax ->
            UnsupportedQuotaAdapter(vendor, QuotaSubject.UnsupportedReason.providerTypeUnsupported)
        // Candidate interfaces exist but no captured response has confirmed
        // their fields; never ship an unverified parser.
        QuotaVendor.geminiCodeAssist, QuotaVendor.githubCopilot ->
            UnsupportedQuotaAdapter(vendor, QuotaSubject.UnsupportedReason.providerTypeUnsupported)
        // [T-quota-relay-probe] The relay's own account ledger, labelled as
        // relay-reported (never as the upstream vendor's balance).
        QuotaVendor.customRelay -> RelayQuotaAdapter
        QuotaVendor.unknown -> UnsupportedQuotaAdapter(vendor, QuotaSubject.UnsupportedReason.unknownVendor)
    }

    fun adapter(d: QuotaInstanceDescriptor): ProviderQuotaAdapter = adapter(QuotaVendorDetector.vendor(d))
}

class UnsupportedQuotaAdapter(
    override val vendor: QuotaVendor,
    val reason: QuotaSubject.UnsupportedReason,
) : ProviderQuotaAdapter {
    override val adapterId: String get() = "unsupported.${vendor.name}"
    override fun capability(d: QuotaInstanceDescriptor) = QuotaCapability.unsupported(reason)
    override suspend fun fetch(ctx: QuotaFetchContext): QuotaFetchOutcome =
        throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "no active quota query for ${vendor.name}")
}

// MARK: - Shared helpers for balance adapters

/** The configured host when it is one of [hosts], else the first (default) host. */
internal fun QuotaInstanceDescriptor.vendorHost(hosts: List<String>): String =
    customHost?.takeIf { it in hosts } ?: hosts[0]

internal fun officialProvenance(adapterId: String, endpoint: String) =
    QuotaProvenance(QuotaSource.officialQuery, QuotaConfidence.documented, adapterId, endpoint, null)

internal fun accountWideBinding(key: QuotaPoolKey) =
    QuotaPoolBinding(key, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.vendorDocumented)

// MARK: - Official usage / balance pages

/**
 * [T-quota-console-links] The vendor's own page for usage or balance, opened
 * in the in-app browser from the quota section and sheet. Offered even where
 * Minis cannot query the figure itself. Fixed per vendor: never built from a
 * user-supplied base URL, and null for relays and unknown hosts.
 */
object QuotaConsoleLink {
    fun url(d: QuotaInstanceDescriptor): String? {
        val host = d.customHost.orEmpty()
        return when (QuotaVendorDetector.vendor(d)) {
            QuotaVendor.codexSubscription -> "https://chatgpt.com/codex/cloud/settings/analytics#usage"
            QuotaVendor.openAIPlatform -> "https://platform.openai.com/usage"
            QuotaVendor.claudeSubscription -> "https://claude.ai/settings/usage"
            QuotaVendor.anthropicPlatform -> "https://console.anthropic.com/settings/billing"
            QuotaVendor.deepSeek -> "https://platform.deepseek.com/usage"
            QuotaVendor.moonshot ->
                if (host.endsWith(".ai")) "https://platform.moonshot.ai/console/account"
                else "https://platform.moonshot.cn/console/account"
            // Kept next to the quota windows: the top-up pack balance is only
            // shown on the site.
            QuotaVendor.kimiCode -> "https://www.kimi.com/code"
            QuotaVendor.openRouter -> "https://openrouter.ai/settings/credits"
            QuotaVendor.xiaomiMiMo -> "https://platform.xiaomimimo.com/console/plan-manage"
            // [ISS-031] The pages the official Token Plan FAQ links from "How to
            // check Token Plan usage": Billing > Token Plan (international) and
            // Subscription billing > Plan usage (China).
            QuotaVendor.miniMax ->
                if (host == "api.minimaxi.com") "https://platform.minimaxi.com/console/usage"
                else "https://platform.minimax.io/user-center/payment/token-plan"
            // [ISS-031] The "console" link of opencode.ai/docs/go ("You can
            // track your current usage in the console").
            QuotaVendor.openCode -> "https://opencode.ai/auth"
            QuotaVendor.siliconFlow, QuotaVendor.geminiAPIKey, QuotaVendor.geminiCodeAssist,
            QuotaVendor.antigravity, QuotaVendor.xAI, QuotaVendor.githubCopilot,
            QuotaVendor.customRelay, QuotaVendor.unknown -> null
        }
    }

    /** "platform.deepseek.com" for the row's second line. */
    fun host(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
}

// MARK: - Capability failure

object QuotaCapabilityRules {
    fun isSubscription(v: QuotaVendor): Boolean =
        v == QuotaVendor.claudeSubscription || v == QuotaVendor.codexSubscription

    /**
     * Whether a failed query marks the endpoint unusable (24 h pause, user
     * re-check allowed). A 404 or an unreadable answer always does. For a
     * subscription, 401 / 403 do too: its chat requests work with the same
     * token, so the refusal is about the undocumented usage endpoint, not the
     * credential - and passive header updates would otherwise clear the error
     * and re-arm a query every 5 minutes against an endpoint that refuses.
     */
    fun failureDisablesEndpoint(kind: QuotaError.Kind, vendor: QuotaVendor): Boolean = when (kind) {
        QuotaError.Kind.notFound, QuotaError.Kind.unsupportedResponse -> true
        QuotaError.Kind.unauthorized, QuotaError.Kind.forbidden -> isSubscription(vendor)
        else -> false
    }
}

/**
 * [ISS-031] A response's structure with every value replaced by its type:
 * `{"limits":[{"detail":{"limit":int,"used":int},...}]x2,"usage":{...}}`.
 * Object keys are kept (they are the schema); strings, numbers and booleans
 * never appear. Bounded in depth and length so a large reply cannot flood the
 * log. Port of iOS QuotaResponseShape.
 */
object QuotaResponseShape {
    fun describe(value: JsonElement): String {
        val text = shape(value, 0)
        return if (text.length > 1200) text.take(1200) + "..." else text
    }

    private fun shape(value: JsonElement, depth: Int): String {
        if (depth > 5) return "..."
        return when (value) {
            is JsonObject -> {
                val keys = value.keys.sorted()
                val parts = keys.take(30).map { "\"$it\":${shape(value.getValue(it), depth + 1)}" }
                "{" + parts.joinToString(",") + (if (keys.size > 30) ",..." else "") + "}"
            }
            is JsonArray -> if (value.isEmpty()) "[]" else "[" + shape(value[0], depth + 1) + "]x${value.size}"
            is JsonPrimitive -> when {
                value is kotlinx.serialization.json.JsonNull -> "null"
                value.isString -> {
                    val s = value.content
                    when {
                        ClaudeSubscriptionQuotaAdapter.parseIsoDate(s) != null ||
                            KimiCodeQuotaAdapter.parseISO(s) != null -> "iso-date"
                        s.toDoubleOrNull() != null -> "numeric-string"
                        else -> "string"
                    }
                }
                value.booleanOrNull != null -> "bool"
                value.content.any { it == '.' || it == 'e' || it == 'E' } -> "num"
                else -> "int"
            }
        }
    }
}

// MARK: - Relays (Sub2API / New API / OpenAI-compatible billing)

/** [T-quota-relay-probe] Which relay family answered, and so which endpoint to use next time. */
@Suppress("EnumEntryName")
enum class RelayKind {
    sub2api, newAPI, openAIBilling;

    val displayName: String
        get() = when (this) {
            sub2api -> "Sub2API"
            newAPI -> "New API"
            openAIBilling -> "OpenAI-compatible billing"
        }

    val path: String
        get() = when (this) {
            sub2api -> "/v1/usage"
            newAPI -> "/api/usage/token"
            openAIBilling -> "/v1/dashboard/billing/subscription"
        }

    val adapterId: String get() = "relay.$name"

    companion object {
        fun fromAdapterId(adapterId: String): RelayKind? {
            if (!adapterId.startsWith("relay.")) return null
            val raw = adapterId.removePrefix("relay.")
            return entries.firstOrNull { it.name == raw }
        }
    }
}
