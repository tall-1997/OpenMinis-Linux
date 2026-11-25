package com.openminis.app.quota

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

// [T-provider-quota] Vendor detection, the adapter contract / registry, and
// the active-query adapters. Port of iOS QuotaAdapters.swift.
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
        val baseClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .cache(null)
                .build()
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
     * [ISS-031] DEV_TOOLS builds only (set by QuotaStore behind
     * `BuildConfig.DEV_TOOLS`): send the request here instead of the vendor
     * host, so a fake server can stand in for an endpoint whose real reply has
     * never been captured. Only the Kimi Code adapter reads it.
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
private fun QuotaInstanceDescriptor.vendorHost(hosts: List<String>): String =
    customHost?.takeIf { it in hosts } ?: hosts[0]

private fun officialProvenance(adapterId: String, endpoint: String) =
    QuotaProvenance(QuotaSource.officialQuery, QuotaConfidence.documented, adapterId, endpoint, null)

private fun accountWideBinding(key: QuotaPoolKey) =
    QuotaPoolBinding(key, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.vendorDocumented)

// MARK: - DeepSeek

/**
 * `GET https://api.deepseek.com/user/balance` (documented).
 * `{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00",
 *   "granted_balance":"10.00","topped_up_balance":"100.00"}]}`
 * One prepaid pool PER currency - currencies are never converted or summed.
 */
object DeepSeekQuotaAdapter : ProviderQuotaAdapter {
    override val adapterId = "deepseek.balance"
    override val vendor = QuotaVendor.deepSeek
    const val ENDPOINT = "api.deepseek.com/user/balance"

    // An OAuth-typed instance on this host carries a manually pasted bearer
    // token, which is the API key; the store resolves either.
    override fun capability(d: QuotaInstanceDescriptor) = QuotaCapability.activeQuery()

    override suspend fun fetch(ctx: QuotaFetchContext): QuotaFetchOutcome {
        val r = ctx.transport.get("https://$ENDPOINT", ctx.credential)
        QuotaHttp.error(r, ENDPOINT, ctx.now)?.let { throw it }
        val snap = parse(r.body, ctx.subjectId, adapterId, ctx.now, QuotaHttp.clockOffset(r, ctx.now))
        return QuotaFetchOutcome(snap, isComplete = true)
    }

    fun parse(
        data: ByteArray,
        subjectId: QuotaSubjectID,
        adapterId: String = "deepseek.balance",
        now: Long,
        clockOffset: Long? = null,
    ): UpstreamQuotaSnapshot {
        val obj = QuotaHttp.jsonObject(data, ENDPOINT)
        val infos = obj.arr("balance_infos")
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "missing balance_infos")
        val available = obj.bool("is_available")
        val prov = officialProvenance(adapterId, ENDPOINT)
        val pools = mutableListOf<QuotaPool>()
        for (element in infos) {
            val info = element.asObject() ?: continue
            val currency = info.str("currency")?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
            val total = info.dec("total_balance")
            val parts = mutableListOf<QuotaAmounts.Part>()
            info.dec("granted_balance")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.Granted, it) }
            info.dec("topped_up_balance")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.ToppedUp, it) }
            // An item without a readable total carries no amount we can show.
            if (total == null && parts.isEmpty()) continue
            val key = QuotaPoolKey.of(subjectId, "balance", currency)
            pools += QuotaPool(
                id = key, subjectId = subjectId, kind = QuotaPool.Kind.prepaidBalance,
                unit = QuotaUnit.Currency(currency), vendorLabel = "$currency balance",
                amounts = QuotaAmounts(remaining = total, breakdown = parts),
                verdict = available?.let { if (it) QuotaPool.Verdict.allowed else QuotaPool.Verdict.rejected },
                provenance = prov, observedAt = now,
            )
        }
        if (pools.isEmpty() && infos.isNotEmpty()) {
            throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "no readable balance_infos")
        }
        return UpstreamQuotaSnapshot(
            subjectId = subjectId, provenance = prov, capturedAt = now, serverClockOffset = clockOffset,
            pools = pools, bindings = pools.map { accountWideBinding(it.id) }, accountAvailable = available,
        )
    }
}

// MARK: - Moonshot

/**
 * `GET https://api.moonshot.cn/v1/users/me/balance` (documented; the `.ai`
 * platform bills in USD, the `.cn` one in CNY).
 * `{"code":0,"data":{"available_balance":49.58,"voucher_balance":46.58,"cash_balance":3.00},"status":true}`
 */
object MoonshotQuotaAdapter : ProviderQuotaAdapter {
    override val adapterId = "moonshot.balance"
    override val vendor = QuotaVendor.moonshot
    val hosts = listOf("api.moonshot.cn", "api.moonshot.ai")

    override fun capability(d: QuotaInstanceDescriptor) = QuotaCapability.activeQuery()

    override suspend fun fetch(ctx: QuotaFetchContext): QuotaFetchOutcome {
        val host = ctx.descriptor.vendorHost(hosts)
        val endpoint = "$host/v1/users/me/balance"
        val r = ctx.transport.get("https://$endpoint", ctx.credential)
        QuotaHttp.error(r, endpoint, ctx.now)?.let { throw it }
        val snap = parse(r.body, host, ctx.subjectId, adapterId, ctx.now, QuotaHttp.clockOffset(r, ctx.now))
        return QuotaFetchOutcome(snap, isComplete = true)
    }

    fun currencyForHost(host: String): String = if (host.endsWith(".ai")) "USD" else "CNY"

    fun parse(
        data: ByteArray,
        host: String,
        subjectId: QuotaSubjectID,
        adapterId: String = "moonshot.balance",
        now: Long,
        clockOffset: Long? = null,
    ): UpstreamQuotaSnapshot {
        val endpoint = "$host/v1/users/me/balance"
        val obj = QuotaHttp.jsonObject(data, endpoint)
        val d = obj.obj("data")
        val available = d?.dec("available_balance")
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "missing data.available_balance")
        val parts = mutableListOf<QuotaAmounts.Part>()
        d.dec("voucher_balance")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.Voucher, it) }
        d.dec("cash_balance")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.Cash, it) }
        val cur = currencyForHost(host)
        val key = QuotaPoolKey.of(subjectId, "balance", cur)
        val prov = officialProvenance(adapterId, endpoint)
        val pool = QuotaPool(
            id = key, subjectId = subjectId, kind = QuotaPool.Kind.prepaidBalance, unit = QuotaUnit.Currency(cur),
            vendorLabel = "$cur balance", amounts = QuotaAmounts(remaining = available, breakdown = parts),
            provenance = prov, observedAt = now,
        )
        return UpstreamQuotaSnapshot(
            subjectId = subjectId, provenance = prov, capturedAt = now, serverClockOffset = clockOffset,
            pools = listOf(pool), bindings = listOf(accountWideBinding(key)),
        )
    }
}

// MARK: - SiliconFlow

/**
 * `GET https://api.siliconflow.cn/v1/user/info` (documented). Only the three
 * balance fields are read; id / name / email / image are never touched.
 */
object SiliconFlowQuotaAdapter : ProviderQuotaAdapter {
    override val adapterId = "siliconflow.userinfo"
    override val vendor = QuotaVendor.siliconFlow
    val hosts = listOf("api.siliconflow.cn", "api.siliconflow.com")

    override fun capability(d: QuotaInstanceDescriptor) = QuotaCapability.activeQuery()

    override suspend fun fetch(ctx: QuotaFetchContext): QuotaFetchOutcome {
        val host = ctx.descriptor.vendorHost(hosts)
        val endpoint = "$host/v1/user/info"
        val r = ctx.transport.get("https://$endpoint", ctx.credential)
        QuotaHttp.error(r, endpoint, ctx.now)?.let { throw it }
        val snap = parse(r.body, host, ctx.subjectId, adapterId, ctx.now, QuotaHttp.clockOffset(r, ctx.now))
        return QuotaFetchOutcome(snap, isComplete = true)
    }

    fun currencyForHost(host: String): String = if (host.endsWith(".com")) "USD" else "CNY"

    fun parse(
        data: ByteArray,
        host: String,
        subjectId: QuotaSubjectID,
        adapterId: String = "siliconflow.userinfo",
        now: Long,
        clockOffset: Long? = null,
    ): UpstreamQuotaSnapshot {
        val endpoint = "$host/v1/user/info"
        val obj = QuotaHttp.jsonObject(data, endpoint)
        val d = obj.obj("data")
        val total = d?.dec("totalBalance")
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "missing data.totalBalance")
        val parts = mutableListOf<QuotaAmounts.Part>()
        d.dec("chargeBalance")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.ToppedUp, it) }
        d.dec("balance")?.let { parts += QuotaAmounts.Part(QuotaAmounts.Role.Granted, it) }
        val cur = currencyForHost(host)
        val key = QuotaPoolKey.of(subjectId, "balance", cur)
        val prov = officialProvenance(adapterId, endpoint)
        val pool = QuotaPool(
            id = key, subjectId = subjectId, kind = QuotaPool.Kind.prepaidBalance, unit = QuotaUnit.Currency(cur),
            vendorLabel = "$cur balance", amounts = QuotaAmounts(remaining = total, breakdown = parts),
            provenance = prov, observedAt = now,
        )
        return UpstreamQuotaSnapshot(
            subjectId = subjectId, provenance = prov, capturedAt = now, serverClockOffset = clockOffset,
            pools = listOf(pool), bindings = listOf(accountWideBinding(key)),
        )
    }
}

// MARK: - OpenRouter

/**
 * Two documented endpoints, fetched in parallel, failing independently:
 * - `GET /api/v1/key` -> `data.limit` (null = no cap), `usage`, `limit_remaining`,
 *   `limit_reset` -> a spendCap pool, only when `limit` is set.
 * - `GET /api/v1/credits` -> `data.total_credits`, `total_usage` -> a
 *   prepaidBalance pool, remaining = total_credits - total_usage.
 */
object OpenRouterQuotaAdapter : ProviderQuotaAdapter {
    override val adapterId = "openrouter.key+credits"
    override val vendor = QuotaVendor.openRouter
    const val KEY_ENDPOINT = "openrouter.ai/api/v1/key"
    const val CREDITS_ENDPOINT = "openrouter.ai/api/v1/credits"

    override fun capability(d: QuotaInstanceDescriptor) = QuotaCapability.activeQuery()

    override suspend fun fetch(ctx: QuotaFetchContext): QuotaFetchOutcome = coroutineScope {
        val k = async { run(ctx, KEY_ENDPOINT) }
        val c = async { run(ctx, CREDITS_ENDPOINT) }
        combine(k.await(), c.await(), ctx.subjectId, adapterId, ctx.now)
    }

    private suspend fun run(ctx: QuotaFetchContext, endpoint: String): Result<QuotaHttpResponse> = try {
        val r = ctx.transport.get("https://$endpoint", ctx.credential)
        QuotaHttp.error(r, endpoint, ctx.now)?.let { Result.failure(it) } ?: Result.success(r)
    } catch (e: QuotaFetchError) {
        Result.failure(e)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(QuotaFetchError(QuotaError.Kind.network, "$endpoint: ${e.javaClass.simpleName}"))
    }

    /** Pure; exposed for tests. A failure must carry a [QuotaFetchError]. */
    fun combine(
        key: Result<QuotaHttpResponse>,
        credits: Result<QuotaHttpResponse>,
        subjectId: QuotaSubjectID,
        adapterId: String = "openrouter.key+credits",
        now: Long,
    ): QuotaFetchOutcome {
        val pools = mutableListOf<QuotaPool>()
        val errors = mutableListOf<QuotaFetchError>()
        var offset: Long? = null
        // A /key answer with limit == null is a complete answer that yields no pool.
        var keyAnswered = false
        var creditsAnswered = false

        key.fold(
            onSuccess = { r ->
                offset = QuotaHttp.clockOffset(r, now)
                try {
                    parseKey(r.body, subjectId, adapterId, now)?.let { pools += it }
                    keyAnswered = true
                } catch (e: QuotaFetchError) { errors += e }
            },
            onFailure = { errors += it.asFetchError() },
        )
        credits.fold(
            onSuccess = { r ->
                offset = offset ?: QuotaHttp.clockOffset(r, now)
                try {
                    pools += parseCredits(r.body, subjectId, adapterId, now)
                    creditsAnswered = true
                } catch (e: QuotaFetchError) { errors += e }
            },
            onFailure = { errors += it.asFetchError() },
        )

        if (!keyAnswered && !creditsAnswered) {
            // Both failed: surface the most actionable error (auth first).
            val order = listOf(
                QuotaError.Kind.unauthorized, QuotaError.Kind.forbidden, QuotaError.Kind.rateLimited,
                QuotaError.Kind.server, QuotaError.Kind.notFound,
            )
            throw order.firstNotNullOfOrNull { k -> errors.firstOrNull { it.kind == k } } ?: errors[0]
        }
        val prov = officialProvenance(adapterId, "openrouter.ai/api/v1/{key,credits}")
        val snap = UpstreamQuotaSnapshot(
            subjectId = subjectId, provenance = prov, capturedAt = now, serverClockOffset = offset,
            pools = pools, bindings = pools.map { accountWideBinding(it.id) },
        )
        return QuotaFetchOutcome(snap, isComplete = keyAnswered && creditsAnswered, partialErrors = errors)
    }

    private fun Throwable.asFetchError(): QuotaFetchError =
        this as? QuotaFetchError ?: QuotaFetchError(QuotaError.Kind.network, javaClass.simpleName)

    fun parseKey(data: ByteArray, subjectId: QuotaSubjectID, adapterId: String = "openrouter.key+credits", now: Long): QuotaPool? {
        val obj = QuotaHttp.jsonObject(data, KEY_ENDPOINT)
        val d = obj.obj("data")
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "missing data $KEY_ENDPOINT")
        // limit == null (or absent) -> the key has no spend cap: no pool, never "100% left".
        val limit = d.dec("limit") ?: return null
        val remaining = d.dec("limit_remaining")
        // With a periodic reset (`limit_reset` set) `usage` is lifetime spend and
        // does not line up with the cap; only trust it for a lifetime cap.
        val periodic = d.str("limit_reset")?.isNotEmpty() ?: false
        val used = if (periodic || remaining != null) null else d.dec("usage")
        return QuotaPool(
            id = QuotaPoolKey.of(subjectId, "key-limit"), subjectId = subjectId,
            kind = QuotaPool.Kind.spendCap, unit = QuotaUnit.Currency("USD"), vendorLabel = "Key limit",
            amounts = QuotaAmounts(total = limit, used = used, remaining = remaining),
            provenance = officialProvenance(adapterId, KEY_ENDPOINT), observedAt = now,
        )
    }

    fun parseCredits(data: ByteArray, subjectId: QuotaSubjectID, adapterId: String = "openrouter.key+credits", now: Long): QuotaPool {
        val obj = QuotaHttp.jsonObject(data, CREDITS_ENDPOINT)
        val d = obj.obj("data")
        val total = d?.dec("total_credits")
        val usage = d?.dec("total_usage")
        if (total == null || usage == null) {
            throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "missing data.total_credits")
        }
        // A prepaid balance: no total/used, so no percent is ever derived from it.
        return QuotaPool(
            id = QuotaPoolKey.of(subjectId, "account-credits"), subjectId = subjectId,
            kind = QuotaPool.Kind.prepaidBalance, unit = QuotaUnit.Currency("USD"), vendorLabel = "Credits",
            amounts = QuotaAmounts(remaining = total.subtract(usage)),
            provenance = officialProvenance(adapterId, CREDITS_ENDPOINT), observedAt = now,
        )
    }
}

// MARK: - Official usage / balance pages

/**
 * [T-quota-console-links] The vendor's own page for usage or balance, opened
 * in the in-app browser from the quota section and sheet. Offered even where
 * Minis Ultra cannot query the figure itself. Fixed per vendor: never built from a
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

// MARK: - Claude subscription (OAuth): active usage query + passive headers

/**
 * [T-quota-claude-subscription] Claude Pro / Max usage windows, from two
 * sources that write the SAME pools and windows:
 * - passive: the `anthropic-ratelimit-unified-*` headers on every reply of an
 *   OAuth instance (no extra request);
 * - active: `GET https://api.anthropic.com/api/oauth/usage`. The official CLI
 *   uses it for `/usage`; it is not publicly documented, so it is labelled
 *   providerDashboardAPI / observedClient, never "official API".
 *
 * Pools (keys shared by both sources, so they merge per window):
 * - `subscription`, windows `5h` and `7d`: the account's limit, bound
 *   account-wide (scope not stated upstream -> assumedSubjectWide);
 * - `model:opus` / `model:sonnet` (`7d`): a per-model-family weekly limit,
 *   bound to that family, named upstream (explicitUpstream);
 * - `product:oauth_apps` (`7d`): the limit for third-party OAuth apps, a
 *   product pool (never bound to a model).
 */
object ClaudeSubscriptionQuotaAdapter : ProviderQuotaAdapter {
    override val adapterId = "claude.oauth-usage"
    override val vendor = QuotaVendor.claudeSubscription
    const val ENDPOINT = "api.anthropic.com/api/oauth/usage"
    const val HEADER_PREFIX = "anthropic-ratelimit-unified-"

    override fun capability(d: QuotaInstanceDescriptor) =
        if (d.isOAuth) QuotaCapability.activeQuery(QuotaSource.providerDashboardAPI, QuotaConfidence.observedClient)
        else QuotaCapability.unsupported(QuotaSubject.UnsupportedReason.providerTypeUnsupported)

    override suspend fun fetch(ctx: QuotaFetchContext): QuotaFetchOutcome {
        val headers = ctx.extraHeaders + ("anthropic-beta" to "oauth-2025-04-20")
        val r = ctx.transport.get("https://$ENDPOINT", ctx.credential, headers)
        QuotaHttp.error(r, ENDPOINT, ctx.now)?.let { throw it }
        return QuotaFetchOutcome(parseUsage(r.body, ctx.subjectId, adapterId, ctx.now), isComplete = true)
    }

    // Window mapping (shared by both sources)

    data class WindowSpec(
        val poolFamily: String,
        val windowId: String,
        val durationSeconds: Long?,
        val target: QuotaPoolBinding.Target,
        val evidence: QuotaPoolBinding.Evidence,
    )

    /**
     * Maps an upstream window name - API key (`five_hour`, `seven_day_opus`)
     * or header segment (`5h`, `7d`, `7d_opus`) - to its pool / window.
     */
    fun spec(rawName: String): WindowSpec {
        val n = rawName.lowercase().replace("-", "_")
        val family = listOf("opus", "sonnet", "haiku").firstOrNull { n.endsWith("_$it") }
        val base = when {
            n.startsWith("five_hour") || n.startsWith("5h") -> "5h"
            n.startsWith("seven_day") || n.startsWith("7d") -> "7d"
            else -> n
        }
        val duration = when (base) {
            "5h" -> 5 * 3600L
            "7d" -> 7 * 86400L
            else -> null
        }
        if (family != null) {
            return WindowSpec(
                "model:$family", base, duration,
                QuotaPoolBinding.Target.ModelFamily("claude-$family*"), QuotaPoolBinding.Evidence.explicitUpstream,
            )
        }
        if (n.contains("oauth_apps")) {
            return WindowSpec(
                "product:oauth_apps", base, duration,
                QuotaPoolBinding.Target.Product("oauth_apps"), QuotaPoolBinding.Evidence.explicitUpstream,
            )
        }
        return WindowSpec(
            "subscription", base, duration, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.assumedSubjectWide,
        )
    }

    /**
     * Utilization as a 0..1 fraction. The API reports percent (0-100) and the
     * headers a fraction (0-1). A header value above 2 is read as a percent
     * too, so a format change degrades to the right number, not 100x.
     */
    fun fraction(value: BigDecimal, isPercent: Boolean): BigDecimal =
        if (isPercent || value > BigDecimal(2)) value.divide(BigDecimal(100)).canonical() else value

    /**
     * `{"five_hour":{"utilization":6.0,"resets_at":"2025-11-04T04:59:59.94+00:00"},
     *   "seven_day":{...}, "seven_day_opus":{...}|null, "seven_day_oauth_apps":null, "extra_usage":{...}}`
     */
    fun parseUsage(data: ByteArray, subjectId: QuotaSubjectID, adapterId: String = "claude.oauth-usage", now: Long): UpstreamQuotaSnapshot {
        val obj = QuotaHttp.jsonObject(data, ENDPOINT)
        val prov = QuotaProvenance(QuotaSource.providerDashboardAPI, QuotaConfidence.observedClient, adapterId, ENDPOINT)
        val windows = mutableListOf<Pair<WindowSpec, QuotaWindow>>()
        for ((key, value) in obj) {
            if (!key.startsWith("five_hour") && !key.startsWith("seven_day")) continue
            val w = value as? JsonObject ?: continue
            val u = w.dec("utilization") ?: continue
            val spec = spec(key)
            val resets = w.str("resets_at")?.let(::parseIsoDate)
            windows += spec to QuotaWindow(
                id = spec.windowId, durationSeconds = spec.durationSeconds, isRolling = true,
                reportedUsedFraction = fraction(u, isPercent = true), resetsAt = resets, observedAt = now, provenance = prov,
            )
        }
        if (windows.isEmpty()) {
            throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "no usage windows in $ENDPOINT")
        }
        return snapshot(windows, subjectId, prov, verdict = null, now = now)
    }

    /** null when the response carries no unified window. Keys match case-insensitively. */
    fun parseHeaders(headers: Map<String, String>, subjectId: QuotaSubjectID, adapterId: String = "claude.oauth-usage", now: Long): UpstreamQuotaSnapshot? {
        val byWindow = LinkedHashMap<String, MutableMap<String, String>>()
        var overallStatus: String? = null
        for ((rawKey, value) in headers) {
            val key = rawKey.lowercase()
            if (!key.startsWith(HEADER_PREFIX)) continue
            val rest = key.removePrefix(HEADER_PREFIX)
            if (rest == "status") { overallStatus = value.lowercase(); continue }
            for (field in listOf("utilization", "reset", "status")) {
                if (rest.endsWith("-$field")) {
                    val window = rest.dropLast(field.length + 1)
                    byWindow.getOrPut(window) { mutableMapOf() }[field] = value
                }
            }
        }
        val prov = QuotaProvenance(QuotaSource.officialHeaders, QuotaConfidence.observedClient, adapterId, "response headers")
        val windows = mutableListOf<Pair<WindowSpec, QuotaWindow>>()
        for ((name, fields) in byWindow) {
            val u = QuotaDecimalParser.parseString(fields["utilization"]) ?: continue
            val spec = spec(name)
            val resets = fields["reset"]?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            windows += spec to QuotaWindow(
                id = spec.windowId, durationSeconds = spec.durationSeconds, isRolling = true,
                reportedUsedFraction = fraction(u, isPercent = false), resetsAt = resets, observedAt = now, provenance = prov,
            )
        }
        if (windows.isEmpty()) return null
        val verdict = when (overallStatus) {
            "allowed" -> QuotaPool.Verdict.allowed
            "allowed_warning" -> QuotaPool.Verdict.warning
            "rejected" -> QuotaPool.Verdict.rejected
            else -> null
        }
        return snapshot(windows, subjectId, prov, verdict, now)
    }

    private fun snapshot(
        windows: List<Pair<WindowSpec, QuotaWindow>>,
        subjectId: QuotaSubjectID,
        provenance: QuotaProvenance,
        verdict: QuotaPool.Verdict?,
        now: Long,
    ): UpstreamQuotaSnapshot {
        val pools = LinkedHashMap<QuotaPoolKey, QuotaPool>()
        val bindings = mutableListOf<QuotaPoolBinding>()
        for ((spec, window) in windows.sortedBy { it.second.id }) {
            val key = QuotaPoolKey.of(subjectId, spec.poolFamily)
            val existing = pools[key]
            if (existing == null) {
                pools[key] = QuotaPool(
                    id = key, subjectId = subjectId, kind = QuotaPool.Kind.usageWindow, unit = QuotaUnit.PercentOnly,
                    vendorLabel = if (spec.poolFamily == "subscription") "Claude subscription" else spec.poolFamily,
                    windows = listOf(window),
                    // The overall status describes the account limit, not a family pool.
                    verdict = if (spec.poolFamily == "subscription") verdict else null,
                    provenance = provenance, observedAt = now,
                )
                bindings += QuotaPoolBinding(key, spec.target, spec.evidence)
            } else {
                pools[key] = existing.copy(windows = existing.windows + window)
            }
        }
        return UpstreamQuotaSnapshot(
            subjectId = subjectId, provenance = provenance, capturedAt = now,
            pools = pools.values.sortedBy { it.id.rawValue }, bindings = bindings,
        )
    }

    fun parseIsoDate(s: String): Long? =
        runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
}

// MARK: - ChatGPT / Codex subscription (OAuth): active usage query + passive headers

/**
 * [T-quota-codex-subscription] Same shape as Claude's: two sources, one set
 * of pools.
 * - passive: `x-codex-primary-*` / `x-codex-secondary-*` headers on every
 *   Codex reply (used percent, window minutes, reset);
 * - active: `GET https://chatgpt.com/backend-api/wham/usage`, which the Codex
 *   CLI uses for its status line. Undocumented -> providerDashboardAPI /
 *   observedClient.
 *
 * Pools:
 * - `codex`: windows keyed by duration (300 min -> `5h`, 10080 -> `7d`,
 *   otherwise `<n>m`), account-wide;
 * - `model:<name>`: an `additional_rate_limits` entry, named upstream, bound
 *   to that model;
 * - `credits`: the credit balance when the account has one (no percent).
 */
object CodexSubscriptionQuotaAdapter : ProviderQuotaAdapter {
    override val adapterId = "codex.wham-usage"
    override val vendor = QuotaVendor.codexSubscription
    const val ENDPOINT = "chatgpt.com/backend-api/wham/usage"
    const val HEADER_PREFIX = "x-codex-"

    override fun capability(d: QuotaInstanceDescriptor) =
        if (d.isOAuth) QuotaCapability.activeQuery(QuotaSource.providerDashboardAPI, QuotaConfidence.observedClient)
        else QuotaCapability.unsupported(QuotaSubject.UnsupportedReason.providerTypeUnsupported)

    override suspend fun fetch(ctx: QuotaFetchContext): QuotaFetchOutcome {
        val r = ctx.transport.get("https://$ENDPOINT", ctx.credential, ctx.extraHeaders)
        QuotaHttp.error(r, ENDPOINT, ctx.now)?.let { throw it }
        return QuotaFetchOutcome(parseUsage(r.body, ctx.subjectId, adapterId, ctx.now), isComplete = true)
    }

    fun windowId(minutes: Int): String = when {
        minutes == 300 -> "5h"
        minutes == 10080 -> "7d"
        minutes % 1440 == 0 -> "${minutes / 1440}d"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes}m"
    }

    private val HUNDRED = BigDecimal(100)

    /**
     * `{"plan_type":"plus","rate_limit":{"allowed":true,"limit_reached":false,
     *   "primary_window":{"used_percent":42,"limit_window_seconds":18000,"reset_after_seconds":3600,"reset_at":1735693200},
     *   "secondary_window":{...}},"credits":{"has_credits":true,"unlimited":false,"balance":"12.5"},
     *   "additional_rate_limits":[{"limit_name":"GPT-5.3-Codex-Spark","rate_limit":{"primary_window":{...}}}]}`
     */
    fun parseUsage(data: ByteArray, subjectId: QuotaSubjectID, adapterId: String = "codex.wham-usage", now: Long): UpstreamQuotaSnapshot {
        val obj = QuotaHttp.jsonObject(data, ENDPOINT)
        val prov = QuotaProvenance(QuotaSource.providerDashboardAPI, QuotaConfidence.observedClient, adapterId, ENDPOINT)

        fun windows(rateLimit: JsonObject): List<QuotaWindow> = listOf("primary_window", "secondary_window").mapNotNull { key ->
            val w = rateLimit.obj(key) ?: return@mapNotNull null
            val used = w.dec("used_percent") ?: return@mapNotNull null
            val seconds = w.dec("limit_window_seconds")?.toLong()
            val id = seconds?.let { windowId((it / 60).toInt()) } ?: if (key == "primary_window") "primary" else "secondary"
            val reset = w.dec("reset_at")?.let { (it.toDouble() * 1000).toLong() }
                ?: w.dec("reset_after_seconds")?.let { now + (it.toDouble() * 1000).toLong() }
            QuotaWindow(
                id = id, durationSeconds = seconds, isRolling = true,
                reportedUsedFraction = used.divide(HUNDRED).canonical(), resetsAt = reset, observedAt = now, provenance = prov,
            )
        }
        fun verdict(rateLimit: JsonObject): QuotaPool.Verdict? {
            if (rateLimit.bool("limit_reached") == true || rateLimit.bool("allowed") == false) return QuotaPool.Verdict.rejected
            return if (rateLimit.bool("allowed") == true) QuotaPool.Verdict.allowed else null
        }

        data class Group(
            val family: String,
            val target: QuotaPoolBinding.Target,
            val evidence: QuotaPoolBinding.Evidence,
            val windows: List<QuotaWindow>,
            val verdict: QuotaPool.Verdict?,
        )
        val groups = mutableListOf<Group>()
        obj.obj("rate_limit")?.let { rl ->
            val ws = windows(rl)
            if (ws.isNotEmpty()) {
                groups += Group("codex", QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.assumedSubjectWide, ws, verdict(rl))
            }
        }
        for (element in obj.arr("additional_rate_limits").orEmpty()) {
            val extra = element.asObject() ?: continue
            val name = extra.str("limit_name")?.takeIf { it.isNotEmpty() } ?: continue
            val rl = extra.obj("rate_limit") ?: continue
            val ws = windows(rl)
            if (ws.isNotEmpty()) {
                val key = QuotaModelKey.normalize(name)
                groups += Group("model:$key", QuotaPoolBinding.Target.Model(key), QuotaPoolBinding.Evidence.explicitUpstream, ws, verdict(rl))
            }
        }
        val pools = mutableListOf<QuotaPool>()
        val bindings = mutableListOf<QuotaPoolBinding>()
        for (g in groups) {
            val key = QuotaPoolKey.of(subjectId, g.family)
            pools += QuotaPool(
                id = key, subjectId = subjectId, kind = QuotaPool.Kind.usageWindow, unit = QuotaUnit.PercentOnly,
                vendorLabel = if (g.family == "codex") "ChatGPT subscription" else g.family,
                windows = g.windows, verdict = g.verdict, provenance = prov, observedAt = now,
            )
            bindings += QuotaPoolBinding(key, g.target, g.evidence)
        }
        obj.obj("credits")?.takeIf { it.bool("has_credits") == true }?.let { credits ->
            val unlimited = credits.bool("unlimited") == true
            val balance = credits.dec("balance")
            if (balance != null || unlimited) {
                val key = QuotaPoolKey.of(subjectId, "credits")
                pools += QuotaPool(
                    id = key, subjectId = subjectId, kind = QuotaPool.Kind.prepaidBalance, unit = QuotaUnit.Credits(),
                    vendorLabel = "Credits", amounts = balance?.let { QuotaAmounts(remaining = it) },
                    unlimited = unlimited, provenance = prov, observedAt = now,
                )
                bindings += QuotaPoolBinding(key, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.assumedSubjectWide)
            }
        }
        if (pools.isEmpty()) {
            throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "no rate_limit windows in $ENDPOINT")
        }
        return UpstreamQuotaSnapshot(
            subjectId = subjectId, provenance = prov, capturedAt = now,
            plan = obj.str("plan_type")?.let { QuotaPlan(it) }, pools = pools, bindings = bindings,
        )
    }

    /**
     * `x-codex-primary-used-percent: 12.0`, `x-codex-primary-window-minutes: 300`,
     * `x-codex-primary-reset-at: <epoch>` or `x-codex-primary-reset-after-seconds: <n>`,
     * same for `secondary`. null when the reply carries none.
     */
    fun parseHeaders(headers: Map<String, String>, subjectId: QuotaSubjectID, adapterId: String = "codex.wham-usage", now: Long): UpstreamQuotaSnapshot? {
        val h = headers.mapKeys { it.key.lowercase() }
        val prov = QuotaProvenance(QuotaSource.officialHeaders, QuotaConfidence.observedClient, adapterId, "response headers")
        val windows = mutableListOf<QuotaWindow>()
        for (slot in listOf("primary", "secondary")) {
            val used = QuotaDecimalParser.parseString(h["x-codex-$slot-used-percent"]) ?: continue
            val minutes = h["x-codex-$slot-window-minutes"]?.trim()?.toIntOrNull()
            val reset = h["x-codex-$slot-reset-at"]?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                ?: h["x-codex-$slot-reset-after-seconds"]?.trim()?.toDoubleOrNull()?.let { now + (it * 1000).toLong() }
            windows += QuotaWindow(
                id = minutes?.let(::windowId) ?: slot, durationSeconds = minutes?.let { it * 60L }, isRolling = true,
                reportedUsedFraction = used.divide(HUNDRED).canonical(), resetsAt = reset, observedAt = now, provenance = prov,
            )
        }
        if (windows.isEmpty()) return null
        val key = QuotaPoolKey.of(subjectId, "codex")
        val pool = QuotaPool(
            id = key, subjectId = subjectId, kind = QuotaPool.Kind.usageWindow, unit = QuotaUnit.PercentOnly,
            vendorLabel = "ChatGPT subscription", windows = windows, provenance = prov, observedAt = now,
        )
        return UpstreamQuotaSnapshot(
            subjectId = subjectId, provenance = prov, capturedAt = now, pools = listOf(pool),
            bindings = listOf(QuotaPoolBinding(key, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.assumedSubjectWide)),
        )
    }
}

// MARK: - Kimi Code subscription (usage windows)

/**
 * [ISS-031] Kimi Code membership usage, from `GET {base}/usages` with base
 * `https://api.kimi.com/coding/v1` (overseas `api.kimi.ai`). Source: the
 * official MoonshotAI/kimi-cli `/usage` command (src/kimi_cli/ui/shell/usage.py,
 * commit 9ab1286), which sends only `Authorization: Bearer <api key or OAuth
 * token>`. Not in Kimi's public API docs -> `providerDashboardAPI` /
 * `observedClient`, never "official API". Port of iOS KimiCodeQuotaAdapter
 * (d7fa94168).
 *
 * NOT verified against a real reply: the field names below are what the CLI
 * parses, and the CLI already accepts several spellings, so parsing is lenient
 * and `responseShape` (names + types only) is logged on the first fetch.
 *
 * `{"usage": {limit, used | remaining, name/title, reset_at|resetAt|reset_time|resetTime
 *   | reset_in|resetIn|ttl|window},
 *   "limits": [{"detail": {...same...}, "window": {"duration": 300, "timeUnit": "MINUTE"},
 *               "name"/"title"/"scope"}]}`
 *
 * One pool (`subscription`, account-wide) with one window per entry, like
 * Claude's / Codex's: the worst window decides the status. A window carries
 * the upstream counts (total = limit, used / remaining as given); its percent
 * is computed from them. An entry without a positive limit and a used or
 * remaining count is skipped - the CLI shows such a row as "0 used", Minis Ultra
 * shows nothing rather than an invented number.
 *
 * The request carries Minis Ultra's own User-Agent and nothing else: Kimi Code's
 * terms forbid spoofing the client identity. (Android sets it explicitly; the
 * shared OkHttp transport would otherwise send `okhttp/<version>`.)
 */
object KimiCodeQuotaAdapter : ProviderQuotaAdapter {
    override val adapterId = "kimicode.usages"
    override val vendor = QuotaVendor.kimiCode
    val hosts = listOf("api.kimi.com", "api.kimi.ai")

    /** Minis Ultra's own UA (`Minis Ultra/<version> (Android <release>; <model>)`). Replaceable in tests. */
    internal var userAgent: () -> String = { com.openminis.app.provider.MinisUserAgent.DEFAULT }

    fun endpoint(host: String): String = "$host/coding/v1/usages"

    override fun capability(d: QuotaInstanceDescriptor) =
        QuotaCapability.activeQuery(QuotaSource.providerDashboardAPI, QuotaConfidence.observedClient)

    override suspend fun fetch(ctx: QuotaFetchContext): QuotaFetchOutcome {
        val host = ctx.descriptor.vendorHost(hosts)
        val endpoint = endpoint(host)
        val url = ctx.endpointOverride ?: "https://$endpoint"
        val r = ctx.transport.get(url, ctx.credential, mapOf("User-Agent" to userAgent()))
        QuotaHttp.error(r, endpoint, ctx.now)?.let { throw it }
        val shape = runCatching { QuotaHttp.json.parseToJsonElement(r.body.decodeToString()) }.getOrNull()
            ?.let { QuotaResponseShape.describe(it) }
        val snap = parse(r.body, endpoint, ctx.subjectId, adapterId, ctx.now)
        return QuotaFetchOutcome(snap, isComplete = true, responseShape = shape)
    }

    fun parse(
        data: ByteArray,
        endpoint: String = "api.kimi.com/coding/v1/usages",
        subjectId: QuotaSubjectID,
        adapterId: String = "kimicode.usages",
        now: Long,
    ): UpstreamQuotaSnapshot {
        val obj = QuotaHttp.jsonObject(data, endpoint)
        val prov = QuotaProvenance(QuotaSource.providerDashboardAPI, QuotaConfidence.observedClient, adapterId, endpoint)
        val windows = mutableListOf<QuotaWindow>()
        val usedIds = HashMap<String, Int>()
        fun uniqueId(id: String): String {
            val n = (usedIds[id] ?: 0) + 1
            usedIds[id] = n
            return if (n == 1) id else "$id #$n"
        }

        // Top-level `usage`: the CLI's default label for it is "Weekly limit".
        obj.obj("usage")?.let { usage ->
            val amounts = amounts(usage) ?: return@let
            val name = label(usage)
            windows += QuotaWindow(
                id = uniqueId(name ?: "weekly"), durationSeconds = null,
                calendarPeriod = if (name == null) QuotaWindow.CalendarPeriod.week else null, isRolling = false,
                amounts = amounts, resetsAt = resetsAt(usage, now), observedAt = now, provenance = prov,
            )
        }
        // `limits[]`: each with its own window; `detail` holds the counts when present.
        obj.arr("limits").orEmpty().forEachIndexed { index, element ->
            val item = element.asObject() ?: return@forEachIndexed
            val detail = item.obj("detail") ?: item
            val amounts = amounts(detail) ?: return@forEachIndexed
            val window = item.obj("window") ?: JsonObject(emptyMap())
            val seconds = durationSeconds(window, item, detail)
            val id = label(item) ?: label(detail)
                ?: seconds?.let { CodexSubscriptionQuotaAdapter.windowId((it / 60).toInt()) }
                ?: "limit ${index + 1}"
            windows += QuotaWindow(
                id = uniqueId(id), durationSeconds = seconds, isRolling = seconds != null,
                amounts = amounts, resetsAt = resetsAt(detail, now), observedAt = now, provenance = prov,
            )
        }
        if (windows.isEmpty()) {
            throw QuotaFetchError(
                QuotaError.Kind.unsupportedResponse,
                "no usable usage/limits in $endpoint; shape=${QuotaResponseShape.describe(obj)}",
            )
        }
        val key = QuotaPoolKey.of(subjectId, "subscription")
        val pool = QuotaPool(
            id = key, subjectId = subjectId, kind = QuotaPool.Kind.usageWindow,
            unit = QuotaUnit.Custom("Kimi Code usage"), vendorLabel = "Kimi Code",
            windows = windows, provenance = prov, observedAt = now,
        )
        return UpstreamQuotaSnapshot(
            subjectId = subjectId, provenance = prov, capturedAt = now, pools = listOf(pool),
            bindings = listOf(
                QuotaPoolBinding(key, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.assumedSubjectWide),
            ),
        )
    }

    /**
     * limit + used, or limit + remaining (the CLI derives used = limit -
     * remaining). null when there is no positive limit or neither count, or a
     * count is negative.
     */
    fun amounts(d: JsonObject): QuotaAmounts? {
        val limit = d.dec("limit")?.takeIf { it.signum() > 0 } ?: return null
        val used = d.dec("used")
        val remaining = d.dec("remaining")
        if (used == null && remaining == null) return null
        if (used != null && used.signum() < 0) return null
        if (remaining != null && remaining.signum() < 0) return null
        return QuotaAmounts(total = limit, used = used, remaining = remaining)
    }

    fun label(d: JsonObject): String? {
        for (key in listOf("name", "title", "scope")) {
            val t = d.str(key)?.trim()
            if (!t.isNullOrEmpty()) return t.take(40)
        }
        return null
    }

    /**
     * `duration` + `timeUnit` (MINUTE / HOUR / DAY; anything else = seconds,
     * as in the CLI), looked up in window, then item, then detail.
     */
    fun durationSeconds(window: JsonObject, item: JsonObject, detail: JsonObject): Long? {
        val raw = window.dec("duration") ?: item.dec("duration") ?: detail.dec("duration") ?: return null
        if (raw.signum() <= 0) return null
        val value = raw.toDouble()
        val unit = (window.str("timeUnit") ?: item.str("timeUnit") ?: detail.str("timeUnit")).orEmpty().uppercase()
        val seconds = when {
            "MINUTE" in unit -> value * 60
            "HOUR" in unit -> value * 3600
            "DAY" in unit -> value * 86400
            else -> value
        }
        return Math.round(seconds)
    }

    /**
     * An absolute reset (ISO 8601 - the CLI trims nanoseconds - or epoch
     * seconds / milliseconds), else a relative one in seconds. Epoch ms.
     */
    fun resetsAt(d: JsonObject, now: Long): Long? {
        for (key in listOf("reset_at", "resetAt", "reset_time", "resetTime")) {
            val v = d[key] as? JsonPrimitive ?: continue
            if (v.isString) {
                val s = v.content
                parseISO(s)?.let { return it }
                s.trim().toDoubleOrNull()?.let { n -> return epoch(n) }
                continue
            }
            QuotaDecimalParser.parse(v)?.let { return epoch(it.toDouble()) }
        }
        for (key in listOf("reset_in", "resetIn", "ttl", "window")) {
            val n = d.dec(key) ?: continue
            val seconds = n.toDouble()
            if (seconds > 0) return now + Math.round(seconds * 1000)
        }
        return null
    }

    private fun epoch(n: Double): Long? {
        if (n <= 0) return null
        return if (n > 1e12) Math.round(n) else Math.round(n * 1000)
    }

    /**
     * "2025-12-23T05:24:18.443553353Z" included: the fraction is cut to
     * milliseconds first (as the CLI does), then parsed like Claude's dates.
     */
    fun parseISO(raw: String): Long? {
        var s = raw.trim()
        val dot = s.indexOf('.')
        if (dot >= 0) {
            var end = dot + 1
            while (end < s.length && s[end].isDigit()) end++
            val fraction = s.substring(dot + 1, end)
            if (fraction.length > 3) s = s.substring(0, dot + 1) + fraction.take(3) + s.substring(end)
        }
        return ClaudeSubscriptionQuotaAdapter.parseIsoDate(s)
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

/**
 * [T-quota-relay-probe] A custom relay's own balance / key quota.
 *
 * What a relay reports is its LEDGER for this key or account - what the user
 * pays the relay - not the upstream vendor's balance, so every pool is
 * relayAccountLedger / thirdParty and the UI says so.
 *
 * The type is not configured; it is detected by probing the documented
 * endpoints in order, identified by their data structure:
 * 1. Sub2API `GET /v1/usage` - a boolean `isValid` marks it. Modes:
 *    `quota_limited` (quota.remaining / limit / used), wallet (`balance`),
 *    subscription (`remaining`, -1 = unlimited). USD.
 * 2. New API `GET /api/usage/token` - `data.total_granted / total_used /
 *    total_available / unlimited_quota` in the relay's quota units (shown as
 *    units: the per-dollar rate is a per-site setting, never assumed).
 * 3. OpenAI-compatible `GET /v1/dashboard/billing/subscription` (+ `/usage`):
 *    `hard_limit_usd` (>= 1e8 = unlimited), `total_usage` in cents.
 * CLIProxyAPI has no endpoint a client key can read, so it probes as "none found".
 *
 * Requests go only to the relay the instance already sends its chats to, with
 * the same key; the root is the base URL without its trailing `/v1`.
 */
object RelayQuotaAdapter : ProviderQuotaAdapter {
    override val adapterId = "relay.probe"
    override val vendor = QuotaVendor.customRelay
    private val UNLIMITED_SENTINEL_USD = BigDecimal(100_000_000)

    override fun capability(d: QuotaInstanceDescriptor): QuotaCapability {
        if (d.customHost == null || root(d) == null) {
            return QuotaCapability.unsupported(QuotaSubject.UnsupportedReason.unknownVendor)
        }
        return QuotaCapability.activeQuery(QuotaSource.relayAccountLedger, QuotaConfidence.thirdParty)
    }

    /** Base URL without trailing "/" and "/v1" (case kept for the path), as scheme://authority/path. */
    fun root(d: QuotaInstanceDescriptor): String? {
        val raw = d.customBaseURL?.trim()
        if (raw.isNullOrEmpty()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "https" && scheme != "http") || uri.host.isNullOrEmpty()) return null
        var path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.lowercase().endsWith("/v1")) path = path.dropLast(3)
        path = path.trimEnd('/')
        return "$scheme://${uri.rawAuthority}$path"
    }

    override suspend fun fetch(ctx: QuotaFetchContext): QuotaFetchOutcome {
        val root = root(ctx.descriptor)
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "relay has no usable base URL")
        val order = ctx.relayKindHint?.let { listOf(it) } ?: RelayKind.entries
        val errors = mutableListOf<QuotaFetchError>()
        for (kind in order) {
            try {
                val snap = query(kind, root, ctx)
                return QuotaFetchOutcome(snap, isComplete = true, partialErrors = errors, relayKind = kind)
            } catch (e: QuotaFetchError) {
                // A known type that now refuses is reported as is (key rejected,
                // rate limited...). While probing, move on to the next family.
                if (ctx.relayKindHint != null) throw e
                errors += e
            }
        }
        // Every family refused the key -> it is the key (or the relay's policy),
        // not a missing endpoint. Anything else -> no endpoint found.
        val first = errors.firstOrNull()
        if (first != null && errors.all { it.kind == QuotaError.Kind.unauthorized || it.kind == QuotaError.Kind.forbidden }) {
            throw first
        }
        val checked = order.joinToString(", ") { it.path }
        throw QuotaFetchError(QuotaError.Kind.notFound, "no balance endpoint on this relay (checked $checked)")
    }

    private suspend fun query(kind: RelayKind, root: String, ctx: QuotaFetchContext): UpstreamQuotaSnapshot {
        suspend fun get(path: String): QuotaHttpResponse {
            val r = ctx.transport.get(root + path, ctx.credential, ctx.extraHeaders)
            QuotaHttp.error(r, path, ctx.now)?.let { throw it }
            return r
        }
        val host = runCatching { URI(root).host }.getOrNull().orEmpty()
        return when (kind) {
            RelayKind.sub2api -> parseSub2API(get(kind.path).body, ctx.subjectId, host, ctx.now)
            RelayKind.newAPI -> parseNewAPI(get(kind.path).body, ctx.subjectId, host, ctx.now)
            RelayKind.openAIBilling -> {
                val sub = get(kind.path).body
                val usage = try {
                    get("/v1/dashboard/billing/usage").body
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                parseOpenAIBilling(sub, usage, ctx.subjectId, host, ctx.now)
            }
        }
    }

    private fun provenance(kind: RelayKind, host: String) =
        QuotaProvenance(QuotaSource.relayAccountLedger, QuotaConfidence.thirdParty, kind.adapterId, kind.path, host)

    private fun snapshot(pool: QuotaPool, kind: RelayKind, subjectId: QuotaSubjectID, host: String, now: Long) =
        UpstreamQuotaSnapshot(
            subjectId = subjectId, provenance = provenance(kind, host), capturedAt = now, pools = listOf(pool),
            bindings = listOf(QuotaPoolBinding(pool.id, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.assumedSubjectWide)),
        )

    private fun obj(data: ByteArray, kind: RelayKind): JsonObject =
        runCatching { QuotaHttp.json.parseToJsonElement(data.decodeToString()) as? JsonObject }.getOrNull()
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "not JSON at ${kind.path}")

    // Sub2API

    fun parseSub2API(data: ByteArray, subjectId: QuotaSubjectID, relayHost: String, now: Long): UpstreamQuotaSnapshot {
        val o = obj(data, RelayKind.sub2api)
        val isValid = o.bool("isValid")
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "no isValid: not Sub2API")
        val prov = provenance(RelayKind.sub2api, relayHost)
        val keyUsed = o.obj("usage")?.obj("total")?.dec("actual_cost")
        val key = QuotaPoolKey.of(subjectId, "relay")
        var pool = QuotaPool(
            id = key, subjectId = subjectId, kind = QuotaPool.Kind.prepaidBalance, unit = QuotaUnit.Currency("USD"),
            vendorLabel = "Sub2API", verdict = if (!isValid) QuotaPool.Verdict.rejected else null,
            provenance = prov, observedAt = now,
        )
        when {
            o.str("mode") == "quota_limited" -> {
                val q = o.obj("quota")
                val limit = q?.dec("limit")
                val remaining = q?.dec("remaining")?.max(BigDecimal.ZERO)
                val used = if (o["quota"] != null) q?.dec("used") else keyUsed
                pool = pool.copy(
                    kind = if (limit != null) QuotaPool.Kind.spendCap else QuotaPool.Kind.prepaidBalance,
                    vendorLabel = "Sub2API key quota",
                    amounts = QuotaAmounts(total = limit, used = used, remaining = remaining),
                )
            }
            o.containsKey("balance") -> pool = pool.copy(
                vendorLabel = "Sub2API wallet", amounts = QuotaAmounts(remaining = o.dec("balance")),
            )
            else -> {
                val remaining = o.dec("remaining")
                pool = if (remaining != null && remaining.signum() < 0) {
                    pool.copy(vendorLabel = "Sub2API subscription", unlimited = true, amounts = keyUsed?.let { QuotaAmounts(used = it) })
                } else {
                    pool.copy(vendorLabel = "Sub2API subscription", amounts = QuotaAmounts(used = keyUsed, remaining = remaining))
                }
            }
        }
        if (pool.amounts?.remaining == null && pool.amounts?.total == null && !pool.unlimited) {
            throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "Sub2API usage without amounts")
        }
        return snapshot(pool, RelayKind.sub2api, subjectId, relayHost, now)
    }

    // New API

    fun parseNewAPI(data: ByteArray, subjectId: QuotaSubjectID, relayHost: String, now: Long): UpstreamQuotaSnapshot {
        val o = obj(data, RelayKind.newAPI)
        val d = o.obj("data")
        if (d == null || (d["total_granted"] == null && d["total_available"] == null)) {
            throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "no data.total_granted: not New API")
        }
        val granted = d.dec("total_granted")
        val used = d.dec("total_used")
        val available = d.dec("total_available")
        val unlimited = d.bool("unlimited_quota") == true || (granted?.let { it.signum() < 0 } ?: false)
        val key = QuotaPoolKey.of(subjectId, "relay")
        val pool = QuotaPool(
            id = key, subjectId = subjectId, kind = QuotaPool.Kind.allowance, unit = QuotaUnit.Custom("quota"),
            vendorLabel = d.str("name")?.let { "New API · $it" } ?: "New API token quota",
            amounts = if (unlimited) QuotaAmounts(used = used) else QuotaAmounts(total = granted, used = used, remaining = available),
            unlimited = unlimited, provenance = provenance(RelayKind.newAPI, relayHost), observedAt = now,
        )
        return snapshot(pool, RelayKind.newAPI, subjectId, relayHost, now)
    }

    // OpenAI-compatible billing

    fun parseOpenAIBilling(
        subscription: ByteArray,
        usage: ByteArray?,
        subjectId: QuotaSubjectID,
        relayHost: String,
        now: Long,
    ): UpstreamQuotaSnapshot {
        fun unwrap(o: JsonObject): JsonObject = o.obj("data") ?: o
        val sub = unwrap(obj(subscription, RelayKind.openAIBilling))
        val use = usage?.let { runCatching { QuotaHttp.json.parseToJsonElement(it.decodeToString()) as? JsonObject }.getOrNull() }
            ?.let(::unwrap) ?: JsonObject(emptyMap())
        val key = QuotaPoolKey.of(subjectId, "relay")
        var pool = QuotaPool(
            id = key, subjectId = subjectId, kind = QuotaPool.Kind.spendCap, unit = QuotaUnit.Currency("USD"),
            vendorLabel = "Relay billing", provenance = provenance(RelayKind.openAIBilling, relayHost), observedAt = now,
        )
        val usedUSD = use.dec("total_usage")?.divide(BigDecimal(100))?.canonical() ?: use.dec("used_usd")
        val balance = sub.dec("balance")
        val hard = sub.dec("hard_limit_usd")
        pool = when {
            balance != null -> pool.copy(kind = QuotaPool.Kind.prepaidBalance, amounts = QuotaAmounts(used = usedUSD, remaining = balance))
            hard != null -> if (hard >= UNLIMITED_SENTINEL_USD) {
                pool.copy(unlimited = true, amounts = usedUSD?.let { QuotaAmounts(used = it) })
            } else {
                pool.copy(amounts = QuotaAmounts(
                    total = hard, used = usedUSD, remaining = usedUSD?.let { hard.subtract(it).max(BigDecimal.ZERO).canonical() },
                ))
            }
            else -> throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "no hard_limit_usd / balance: not a billing API")
        }
        return snapshot(pool, RelayKind.openAIBilling, subjectId, relayHost, now)
    }
}
