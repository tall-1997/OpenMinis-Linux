// [port-upstream-1.16] Ported verbatim from OpenMinis/OpenMinis upstream 1a71df0f (src/android/app/src/main/java/com/openminis/app/quota/QuotaModels.kt).
package com.openminis.app.quota

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal

// [T-provider-quota] Upstream quota / balance domain model. Port of iOS
// QuotaModels.swift (c30ff6d72 and follow-ups).
//
// "Quota" here means ONLY what the upstream model service itself reports or
// enforces: prepaid balances, credits, request/token allowances, subscription
// windows, rate limits. Minis's own token telemetry, cost estimates and relay
// usage logs are NOT quota and have no representation in this file - there is
// deliberately no QuotaSource case for them.
//
// Pure Kotlin (no Android types) so the JVM unit tests exercise it directly.
// Times are epoch milliseconds; durations are seconds, as on iOS.

// MARK: - Identity

/** The upstream vendor a provider instance's quota comes from ([QuotaVendorDetector]). */
@Suppress("EnumEntryName")
@Serializable
enum class QuotaVendor {
    deepSeek, moonshot, siliconFlow, openRouter,
    codexSubscription, openAIPlatform,
    claudeSubscription, anthropicPlatform,
    geminiAPIKey, geminiCodeAssist, antigravity,
    xAI, kimiCode, githubCopilot,

    /** Xiaomi MiMo: its balance is only exposed to the web console's login session. */
    xiaomiMiMo,

    /**
     * [ISS-031] MiniMax (api.minimax.io / api.minimaxi.com). Its Token Plan FAQ
     * documents `GET /v1/token_plan/remains`, but not the response fields, so
     * no adapter: console link only.
     */
    miniMax,

    /** [ISS-031] OpenCode Zen / Go. No usage or balance API is documented: console link only. */
    openCode,
    customRelay, unknown,
}

/**
 * Owner of a set of quota pools. Two instances resolving to the same subject
 * (same vendor + same API key) share every pool and are queried once.
 * [accountKey] is a SHA-256 prefix of the key, or "instance:<id>" - never the
 * credential itself.
 */
@Serializable
data class QuotaSubjectID(val vendor: QuotaVendor, val accountKey: String) {
    override fun toString(): String = "${vendor.name}:$accountKey"
}

/** Canonical pool key "<vendor>:<accountKey>:<family>[:<qualifier>]". */
@Serializable(with = QuotaPoolKeySerializer::class)
data class QuotaPoolKey(val rawValue: String) : Comparable<QuotaPoolKey> {
    override fun toString(): String = rawValue
    override fun compareTo(other: QuotaPoolKey): Int = rawValue.compareTo(other.rawValue)

    companion object {
        fun of(subject: QuotaSubjectID, family: String, qualifier: String? = null): QuotaPoolKey {
            var s = "${subject.vendor.name}:${subject.accountKey}:$family"
            if (!qualifier.isNullOrEmpty()) s += ":$qualifier"
            return QuotaPoolKey(s)
        }
    }
}

object QuotaPoolKeySerializer : KSerializer<QuotaPoolKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.openminis.app.quota.QuotaPoolKey", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: QuotaPoolKey) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder): QuotaPoolKey = QuotaPoolKey(decoder.decodeString())
}

// MARK: - Subject

@Serializable
data class QuotaSubject(
    val id: QuotaSubjectID,
    val kind: Kind = Kind.credential,
    val parent: QuotaSubjectID? = null,
    /** Every instance resolving here (usually one). */
    val instanceIds: Set<String> = emptySet(),
    /** Masked key ("sk-...a1b2") - never the full credential. */
    val accountLabel: String? = null,
    val plan: QuotaPlan? = null,
    val state: State = State.Available,
    val lastRefresh: QuotaRefreshRecord? = null,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class Kind { account, credential, organization, project }

    @Serializable
    sealed class State {
        /** No upstream quota signal this build can read. */
        @Serializable @SerialName("unsupported")
        data class Unsupported(val reason: UnsupportedReason) : State()

        /** Passive-only vendor, nothing observed yet (reserved). */
        @Serializable @SerialName("awaitingFirstObservation")
        data object AwaitingFirstObservation : State()

        @Serializable @SerialName("notConfigured")
        data class NotConfigured(val reason: NotConfiguredReason) : State()

        /** Has pools (individual pools may be stale). */
        @Serializable @SerialName("available")
        data object Available : State()

        /** Tried, failed, nothing to show. */
        @Serializable @SerialName("failed")
        data object Failed : State()
    }

    @Suppress("EnumEntryName")
    @Serializable
    enum class UnsupportedReason {
        /** The vendor has no quota/balance interface. */
        vendorHasNoQuotaAPI,

        /** Unknown vendor or a custom endpoint whose capability probe found nothing. */
        unknownVendor,

        /** The provider type/credential shape does not fit any adapter. */
        providerTypeUnsupported,

        /** The vendor only exposes quota passively and that is not implemented yet. */
        passiveNotImplemented,

        /** Capability probe or a query answered 404 / an unreadable structure. */
        capabilityProbeFailed,
    }

    @Suppress("EnumEntryName")
    @Serializable
    enum class NotConfiguredReason { missingCredential, oauthNotSupported, instanceDisabled, relayProbeOff }
}

@Serializable
data class QuotaPlan(val rawId: String, val tierName: String? = null)

// MARK: - Snapshot (one fetch / one observation)

/** The unit of one fetch; the store merges it ([QuotaMerge]). */
@Serializable
data class UpstreamQuotaSnapshot(
    val subjectId: QuotaSubjectID,
    val provenance: QuotaProvenance,
    /** Local clock, epoch ms. */
    val capturedAt: Long,
    /** server - local in ms, from the response `Date` header. */
    val serverClockOffset: Long? = null,
    val plan: QuotaPlan? = null,
    val pools: List<QuotaPool>,
    val bindings: List<QuotaPoolBinding>,
    /** Upstream says the whole account can serve requests (DeepSeek `is_available`). */
    val accountAvailable: Boolean? = null,
)

/** Persisted digest of the latest snapshot per (subject, source). No bodies, no headers. */
@Serializable
data class QuotaSnapshotSummary(
    val subjectId: QuotaSubjectID,
    val source: QuotaSource,
    val adapterId: String,
    val capturedAt: Long,
    val poolKeys: List<QuotaPoolKey>,
)

@Serializable
data class QuotaProvenance(
    val source: QuotaSource,
    val confidence: QuotaConfidence,
    val adapterId: String,
    /** Host + path only; never query strings or secrets. */
    val endpoint: String? = null,
    /** Set when the data came from / through a relay. */
    val relayHost: String? = null,
)

@Suppress("EnumEntryName")
@Serializable
enum class QuotaSource {
    /** Vendor balance / quota endpoint. */
    officialQuery,

    /** Headers on a real model response. */
    officialHeaders,

    /** e.g. codex.rate_limits. */
    officialStreamEvent,

    /** 429 body, e.g. Gemini QuotaFailure. */
    officialErrorSignal,

    /** Vendor dashboard / console API that is not documented as public. */
    providerDashboardAPI,

    /** A compatible or reverse-engineered interface. */
    compatibleInterface,

    /** Relay's own enforced quota for this key (One/New API billing). */
    relayAccountLedger,

    /** Minis-computed from the above. */
    derived,
}

@Suppress("EnumEntryName")
@Serializable
enum class QuotaConfidence { documented, observedClient, unverified, thirdParty, userDeclared }

// MARK: - Pool & windows

@Serializable
data class QuotaPool(
    val id: QuotaPoolKey,
    val subjectId: QuotaSubjectID,
    val kind: Kind,
    val unit: QuotaUnit,
    /** Raw name from upstream ("GPT-5.3-Codex-Spark", "CNY balance"). */
    val vendorLabel: String? = null,
    /** Non-windowed amounts (prepaid balances, spend caps). null for pure window pools. */
    val amounts: QuotaAmounts? = null,
    /** Zero or more windows; never merged into one number. */
    val windows: List<QuotaWindow> = emptyList(),
    val verdict: Verdict? = null,
    val unlimited: Boolean = false,
    val provenance: QuotaProvenance,
    /** Newest of amounts/windows, epoch ms. */
    val observedAt: Long,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class Kind {
        /** Money/credits held, can be topped up -> never a percent. */
        prepaidBalance,

        /** Cap on spend (OpenRouter key limit, relay hard limit). */
        spendCap,

        /** Sized periodic allowance. */
        allowance,

        /** Subscription rolling windows (Codex, Claude). */
        usageWindow,

        /** Throughput (RPM/TPM/concurrency) - NOT consumable quota. */
        rateLimit,
    }

    @Suppress("EnumEntryName")
    @Serializable
    enum class Verdict { allowed, warning, rejected }
}

@Serializable
sealed class QuotaUnit {
    /** ISO 4217. */
    @Serializable @SerialName("currency")
    data class Currency(val code: String) : QuotaUnit()

    /** Not convertible unless the vendor states a rate. */
    @Serializable @SerialName("credits")
    data class Credits(val name: String? = null) : QuotaUnit()

    @Serializable @SerialName("requests")
    data object Requests : QuotaUnit()

    @Serializable @SerialName("tokens")
    data class Tokens(val kind: TokenKind) : QuotaUnit()

    /** Upstream gives only a fraction / percent. */
    @Serializable @SerialName("percentOnly")
    data object PercentOnly : QuotaUnit()

    @Serializable @SerialName("rateLimit")
    data class RateLimit(val metric: RateMetric) : QuotaUnit()

    @Serializable @SerialName("custom")
    data class Custom(val name: String) : QuotaUnit()

    @Suppress("EnumEntryName")
    @Serializable
    enum class TokenKind { input, output, total }

    @Suppress("EnumEntryName")
    @Serializable
    enum class RateMetric { requests, tokens, inputTokens, outputTokens, concurrency }
}

/**
 * All in the pool's unit; all optional. Decimals are persisted as strings so
 * no amount ever round-trips through a Double.
 */
@Serializable
data class QuotaAmounts(
    @Serializable(with = QuotaDecimalSerializer::class) val total: BigDecimal? = null,
    @Serializable(with = QuotaDecimalSerializer::class) val used: BigDecimal? = null,
    @Serializable(with = QuotaDecimalSerializer::class) val remaining: BigDecimal? = null,
    /** Same-unit components: granted / toppedUp / voucher / cash. */
    val breakdown: List<Part> = emptyList(),
) {
    @Serializable
    data class Part(
        val role: Role,
        @Serializable(with = QuotaDecimalSerializer::class) val amount: BigDecimal,
        val expiresAt: Long? = null,
    )

    @Serializable
    sealed class Role {
        @Serializable @SerialName("granted") data object Granted : Role()
        @Serializable @SerialName("toppedUp") data object ToppedUp : Role()
        @Serializable @SerialName("voucher") data object Voucher : Role()
        @Serializable @SerialName("cash") data object Cash : Role()
        @Serializable @SerialName("other") data class Other(val name: String) : Role()
    }
}

@Serializable
data class QuotaWindow(
    /** "5h", "7d", "day", "month", "300m" - stable per pool. */
    val id: String,
    val durationSeconds: Long? = null,
    val calendarPeriod: CalendarPeriod? = null,
    val isRolling: Boolean,
    /** For sized windows (requests/tokens per period). */
    val amounts: QuotaAmounts? = null,
    /** Verbatim from upstream (0...1). */
    @Serializable(with = QuotaDecimalSerializer::class) val reportedUsedFraction: BigDecimal? = null,
    @Serializable(with = QuotaDecimalSerializer::class) val reportedRemainingFraction: BigDecimal? = null,
    /** Already corrected by the snapshot's server clock offset, epoch ms. */
    val resetsAt: Long? = null,
    val observedAt: Long,
    /** Windows of one pool may come from different channels. */
    val provenance: QuotaProvenance,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class CalendarPeriod { day, week, month }
}

// MARK: - Binding (many-to-many)

@Serializable
data class QuotaPoolBinding(
    val poolKey: QuotaPoolKey,
    val target: Target,
    val evidence: Evidence,
) {
    @Serializable
    sealed class Target {
        /** Every model under the subject. */
        @Serializable @SerialName("subject") data object Subject : Target()

        /** Normalized upstream model key - ONLY with explicitUpstream or userManualMapping. */
        @Serializable @SerialName("model") data class Model(val key: String) : Target()

        /** Glob - ONLY with explicitUpstream or vendorDocumented. */
        @Serializable @SerialName("modelFamily") data class ModelFamily(val pattern: String) : Target()

        /** Non-model product ("code_review"). */
        @Serializable @SerialName("product") data class Product(val name: String) : Target()
    }

    @Suppress("EnumEntryName")
    @Serializable
    enum class Evidence {
        /** Upstream named the model/product. */
        explicitUpstream,

        /** Vendor docs say account-wide. */
        vendorDocumented,

        /** Scope unstated -> treated as shared (default). */
        assumedSubjectWide,

        /**
         * The USER declared this model draws on the pool. Never produced by an
         * adapter, so it can never overwrite or masquerade as an upstream binding.
         */
        userManualMapping;

        /** Higher wins when de-duplicating the same (pool, target). */
        val rank: Int
            get() = when (this) {
                explicitUpstream -> 3
                vendorDocumented -> 2
                assumedSubjectWide -> 1
                userManualMapping -> 0
            }
    }
}

// MARK: - Capability

/** "Provider type + normalized base URL" - the unit of capability probing, once per day. */
@Serializable
data class QuotaCapabilityKey(
    val providerType: String,
    /**
     * Lower-cased scheme://host[:port][/path] with trailing "/" and "/v1"
     * removed; "default" for the provider's built-in endpoint.
     */
    val baseURL: String,
) {
    override fun toString(): String = "$providerType@$baseURL"

    companion object {
        fun normalize(raw: String?): String {
            var s = raw?.trim().orEmpty()
            if (s.isEmpty()) return "default"
            s = s.lowercase()
            s = s.trimEnd('/')
            if (s.endsWith("/v1")) s = s.dropLast(3)
            return s.trimEnd('/')
        }
    }
}

/** What an adapter can do for an instance. */
data class QuotaCapability(
    /** Can actively query upstream. */
    val active: Boolean,
    val source: QuotaSource,
    val confidence: QuotaConfidence,
    val manualRefresh: Boolean,
    /** When `active == false`: why. */
    val unsupportedReason: QuotaSubject.UnsupportedReason?,
) {
    companion object {
        fun unsupported(reason: QuotaSubject.UnsupportedReason) = QuotaCapability(
            active = false, source = QuotaSource.officialQuery, confidence = QuotaConfidence.documented,
            manualRefresh = false, unsupportedReason = reason,
        )

        fun activeQuery(
            source: QuotaSource = QuotaSource.officialQuery,
            confidence: QuotaConfidence = QuotaConfidence.documented,
        ) = QuotaCapability(
            active = true, source = source, confidence = confidence,
            manualRefresh = true, unsupportedReason = null,
        )
    }
}

/** Outcome of the (at most daily) capability probe for one [QuotaCapabilityKey]. */
@Serializable
data class QuotaCapabilityRecord(
    val key: QuotaCapabilityKey,
    val adapterId: String,
    val status: Status,
    val source: QuotaSource? = null,
    val confidence: QuotaConfidence? = null,
    val probedAt: Long,
    /** Short, non-sensitive reason ("HTTP 404", "unreadable response"). */
    val detail: String? = null,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class Status {
        /** Queries may run. */
        supported,

        /** Nothing to query for this endpoint. */
        unsupported,

        /** A probe or query hit 404 / an unsupported structure: stop until the next daily probe. */
        failed,
    }
}

// MARK: - Refresh bookkeeping

@Serializable
data class QuotaRefreshRecord(
    val attemptedAt: Long,
    val succeededAt: Long? = null,
    val error: QuotaError? = null,
    /** Earliest time an AUTOMATIC refresh may run again. */
    val nextAllowedAt: Long? = null,
    /** Server-imposed cooldown (Retry-After) - binds manual refreshes too. */
    val serverCooldownUntil: Long? = null,
    /** Consecutive failures, for the backoff. */
    val consecutiveFailures: Int = 0,
    /** Last user-forced attempt, for the manual debounce. */
    val lastForcedAt: Long? = null,
)

@Serializable
data class QuotaError(
    val kind: Kind,
    /** Short, non-sensitive, NOT localized (the UI localizes by [kind]). */
    val detail: String,
    val httpStatus: Int? = null,
    val at: Long,
    val retryAfter: Long? = null,
) {
    @Suppress("EnumEntryName")
    @Serializable
    enum class Kind {
        network, unauthorized, forbidden, notFound, rateLimited, server,
        badResponse, unsupportedResponse, missingCredential,
    }
}

// MARK: - Derived views (computed, never persisted)

data class QuotaUtilization(
    /** May exceed 100 on overage. */
    val percent: BigDecimal,
    val basis: Basis,
) {
    @Suppress("EnumEntryName")
    enum class Basis { reported, reportedComplement, computed }
}

/** Severity-ordered: [unknown] lowest. */
@Suppress("EnumEntryName")
enum class QuotaStatus {
    unknown, available, low, rateLimited, exhausted;

    val severity: Int get() = ordinal

    /** Worth surfacing in compact UI. */
    val isWarning: Boolean get() = this == low || this == rateLimited || this == exhausted
}

@Suppress("EnumEntryName")
enum class QuotaFreshness { fresh, stale, expired }

data class PoolStatus(
    val status: QuotaStatus,
    /** Tightest window; null for non-windowed. */
    val decidingWindowId: String?,
    /** Of that window, if any. */
    val utilization: QuotaUtilization?,
    val freshness: QuotaFreshness,
)

data class ModelQuotaView(
    val instanceId: String,
    val modelKey: String,
    val subjectId: QuotaSubjectID?,
    /** Every applicable pool. */
    val bound: List<BoundPool>,
    val effective: QuotaStatus,
    /** Tightest constraint. */
    val deciding: BoundPool?,
    /** Some bound pools unknown. */
    val isIncomplete: Boolean,
    /** Any binding with a model / modelFamily / product target. */
    val hasModelSpecificPool: Boolean,
) {
    data class BoundPool(
        val poolKey: QuotaPoolKey,
        val binding: QuotaPoolBinding,
        val status: PoolStatus,
    ) {
        val isShared: Boolean get() = binding.target == QuotaPoolBinding.Target.Subject
        val isManual: Boolean get() = binding.evidence == QuotaPoolBinding.Evidence.userManualMapping
    }

    companion object {
        fun empty(instanceId: String, modelKey: String) = ModelQuotaView(
            instanceId = instanceId, modelKey = modelKey, subjectId = null, bound = emptyList(),
            effective = QuotaStatus.unknown, deciding = null, isIncomplete = false, hasModelSpecificPool = false,
        )
    }
}

data class QuotaThresholds(
    val lowPercent: BigDecimal = BigDecimal(80),
    val exhaustedPercent: BigDecimal = BigDecimal(100),
    /**
     * Optional per-subject, per-currency user thresholds for prepaid balances.
     * Empty by default: without one a prepaid balance is never judged "low" by
     * the pool status (the level rules judge it instead).
     */
    val lowBalance: Map<QuotaSubjectID, Map<String, BigDecimal>> = emptyMap(),
)

// MARK: - Decimal coding

/**
 * Decimals are persisted as strings: a JSON number read through a Double can
 * change a stored amount.
 */
object QuotaDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.openminis.app.quota.QuotaDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.quotaString)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val raw = decoder.decodeString()
        return QuotaDecimalParser.parseString(raw) ?: throw SerializationException("not a decimal: $raw")
    }
}

/**
 * Trailing zeros dropped, so equal amounts compare equal as values ("110.00"
 * and "110" are one amount, as a Swift Decimal is) and survive a store round trip.
 */
fun BigDecimal.canonical(): BigDecimal = if (signum() == 0) BigDecimal.ZERO else stripTrailingZeros()

/** Locale-independent canonical text ("110.00" is "110", "-0.5" stays "-0.5"). */
val BigDecimal.quotaString: String
    get() = if (signum() == 0) "0" else stripTrailingZeros().toPlainString()

/**
 * Parses upstream amounts. Strings and JSON numbers are accepted; anything
 * unparseable is null - NEVER zero.
 */
object QuotaDecimalParser {
    private val allowed = Regex("^[0-9.+\\-eE]+$")

    /** A JSON value: string or number. A boolean or null is never an amount. */
    fun parse(value: JsonElement?): BigDecimal? {
        if (value == null || value is JsonNull || value !is JsonPrimitive) return null
        if (!value.isString && value.booleanOrNull != null) return null
        return parseString(value.content)
    }

    fun parseString(raw: String?): BigDecimal? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        // Whole-string validation: a numeric PREFIX ("12abc") must not become a number.
        if (!allowed.matches(s) || s.none { it.isDigit() }) return null
        return try {
            BigDecimal(s).canonical()
        } catch (_: NumberFormatException) {
            null
        }
    }
}
