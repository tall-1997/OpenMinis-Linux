// [port-upstream-1.16] Ported verbatim from OpenMinis/OpenMinis upstream 1a71df0f (src/android/app/src/main/java/com/openminis/app/quota/QuotaPersistence.kt).
package com.openminis.app.quota

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

// [T-provider-quota] The store's on-disk shape, its keyed single flight, and
// display formatting / tone / level rules. Port of iOS QuotaPersistence.swift.
// Pure Kotlin: every user-visible word comes through [QuotaStrings].

// MARK: - On-disk file

/**
 * `noBackupFilesDir/quota/store.json`. Device-local only: the directory is
 * excluded from Android backup, the Minis backup exporter never reads it, and
 * no sync path carries it.
 *
 * Holds structured, secret-free data only: pools, bindings, subject
 * bookkeeping (a masked key label, never the key), capability records and
 * snapshot digests. No response bodies, headers or credentials.
 */
@Serializable
data class QuotaStoreFile(
    val version: Int = CURRENT_VERSION,
    val subjects: List<QuotaSubject> = emptyList(),
    val pools: List<QuotaPool> = emptyList(),
    val bindings: List<QuotaPoolBinding> = emptyList(),
    val capabilities: List<QuotaCapabilityRecord> = emptyList(),
    val summaries: List<QuotaSnapshotSummary> = emptyList(),
    /** Instance id -> the subject it last resolved to. */
    val instanceSubjects: Map<String, QuotaSubjectID> = emptyMap(),
) {
    companion object {
        const val CURRENT_VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            allowStructuredMapKeys = true
        }

        fun encode(file: QuotaStoreFile): String = json.encodeToString(serializer(), file)

        /**
         * null for an unreadable or future-version file. The caller then starts
         * empty: quota data is a cache and is re-fetched, never worth a crash.
         */
        fun decode(text: String): QuotaStoreFile? {
            val file = runCatching { json.decodeFromString(serializer(), text) }.getOrNull() ?: return null
            return file.takeIf { it.version <= CURRENT_VERSION }
        }
    }
}

// MARK: - Single flight

/**
 * One in-flight operation per key. Concurrent callers with the same key (two
 * instances sharing one account, a tick racing a manual refresh) await the
 * same deferred instead of each issuing a request. The operation runs in
 * [scope], so a caller leaving does not cancel the fetch the others wait on.
 */
class QuotaSingleFlight<K : Any, V>(private val scope: CoroutineScope) {
    private val inFlight = HashMap<K, Deferred<V>>()

    fun isRunning(key: K): Boolean = synchronized(inFlight) { inFlight.containsKey(key) }

    suspend fun run(key: K, operation: suspend () -> V): V {
        val deferred = synchronized(inFlight) {
            inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) { operation() }.also { inFlight[key] = it }
        }
        deferred.start()
        try {
            return deferred.await()
        } finally {
            synchronized(inFlight) {
                if (inFlight[key] === deferred && deferred.isCompleted) inFlight.remove(key)
            }
        }
    }
}

// MARK: - Words

/**
 * Every word the pure formatting rules need. The app supplies the string
 * resources (QuotaResourceStrings); [English] mirrors the iOS source strings
 * and backs the unit tests.
 */
interface QuotaStrings {
    fun credits(): String
    fun requests(n: String): String
    fun tokens(n: String): String
    fun used(figure: String): String
    fun balance(figure: String): String
    fun account(figure: String): String
    fun left(figure: String): String
    fun quota(): String
    fun role(role: QuotaAmounts.Role): String
    fun period(period: QuotaWindow.CalendarPeriod): String
    fun source(source: QuotaSource): String

    object English : QuotaStrings {
        override fun credits() = "credits"
        override fun requests(n: String) = "$n requests"
        override fun tokens(n: String) = "$n tokens"
        override fun used(figure: String) = "$figure used"
        override fun balance(figure: String) = "Balance $figure"
        override fun account(figure: String) = "Account $figure"
        override fun left(figure: String) = "$figure left"
        override fun quota() = "Quota"
        override fun role(role: QuotaAmounts.Role) = when (role) {
            QuotaAmounts.Role.Granted -> "Granted"
            QuotaAmounts.Role.ToppedUp -> "Topped up"
            QuotaAmounts.Role.Voucher -> "Voucher"
            QuotaAmounts.Role.Cash -> "Cash"
            is QuotaAmounts.Role.Other -> role.name
        }
        override fun period(period: QuotaWindow.CalendarPeriod) = when (period) {
            QuotaWindow.CalendarPeriod.day -> "Daily"
            QuotaWindow.CalendarPeriod.week -> "Weekly"
            QuotaWindow.CalendarPeriod.month -> "Monthly"
        }
        override fun source(source: QuotaSource) = when (source) {
            QuotaSource.officialQuery -> "Official API"
            QuotaSource.officialHeaders -> "Official response headers"
            QuotaSource.officialStreamEvent -> "Official stream event"
            QuotaSource.officialErrorSignal -> "Official error response"
            QuotaSource.providerDashboardAPI -> "Provider dashboard API"
            QuotaSource.compatibleInterface -> "Compatible interface (unverified)"
            QuotaSource.relayAccountLedger -> "Relay account quota (relay-reported)"
            QuotaSource.derived -> "Estimated"
        }
    }
}

// MARK: - Formatting

object QuotaFormat {
    /**
     * Amount in the pool's own unit. Currencies with the locale's currency
     * style (symbol + minor digits); nothing is converted.
     */
    fun amount(
        value: BigDecimal,
        unit: QuotaUnit,
        locale: Locale = Locale.getDefault(),
        strings: QuotaStrings = QuotaStrings.English,
    ): String = when (unit) {
        is QuotaUnit.Currency -> currency(value, unit.code, locale)
        is QuotaUnit.Credits -> "${number(value, locale)} ${unit.name ?: strings.credits()}"
        QuotaUnit.Requests -> strings.requests(number(value, locale))
        is QuotaUnit.Tokens -> strings.tokens(compact(value, locale))
        QuotaUnit.PercentOnly -> percent(value)
        is QuotaUnit.RateLimit -> number(value, locale)
        is QuotaUnit.Custom -> "${number(value, locale)} ${unit.name}"
    }

    fun currency(value: BigDecimal, code: String, locale: Locale = Locale.getDefault()): String {
        val currency = runCatching { Currency.getInstance(code.uppercase()) }.getOrNull()
            ?: return "${number(value, locale)} ${code.uppercase()}"
        val f = NumberFormat.getCurrencyInstance(locale)
        f.currency = currency
        f.minimumFractionDigits = currency.defaultFractionDigits.coerceAtLeast(0)
        f.maximumFractionDigits = currency.defaultFractionDigits.coerceAtLeast(0)
        return f.format(value)
    }

    fun number(value: BigDecimal, locale: Locale = Locale.getDefault()): String {
        val f = NumberFormat.getNumberInstance(locale)
        f.maximumFractionDigits = 6
        return f.format(value)
    }

    /** "1.2M": for token counts. */
    fun compact(value: BigDecimal, locale: Locale = Locale.getDefault()): String {
        val abs = value.abs()
        val (div, suffix) = when {
            abs >= BigDecimal("1000000000") -> BigDecimal("1000000000") to "B"
            abs >= BigDecimal("1000000") -> BigDecimal("1000000") to "M"
            abs >= BigDecimal("1000") -> BigDecimal("1000") to "K"
            else -> return number(value, locale)
        }
        val f = NumberFormat.getNumberInstance(locale)
        f.maximumFractionDigits = 1
        return f.format(value.divide(div, MathContext.DECIMAL64)) + suffix
    }

    /**
     * "81%": whole numbers, one decimal below 10% so a small value is not shown
     * as 0%. Rounded in decimal (half up), so 4.25 is 4.3% on every device.
     */
    fun percent(value: BigDecimal): String {
        val scale = if (value.signum() > 0 && value < BigDecimal.TEN) 1 else 0
        return "${value.setScale(scale, RoundingMode.HALF_UP).quotaString}%"
    }

    /**
     * [T-quota-percent-used] Every vendor's percent is shown as usage:
     * "44% used". Claude (`utilization`) and ChatGPT / Codex (`used_percent`)
     * both report what is used, so one wording fits all.
     */
    fun percentUsedText(usedPercent: BigDecimal, strings: QuotaStrings = QuotaStrings.English): String =
        strings.used(percent(usedPercent))

    /** "Granted CN¥10.00 · Topped up CN¥100.00". */
    fun breakdown(
        amounts: QuotaAmounts,
        unit: QuotaUnit,
        locale: Locale = Locale.getDefault(),
        strings: QuotaStrings = QuotaStrings.English,
    ): String? {
        if (amounts.breakdown.isEmpty()) return null
        return amounts.breakdown.joinToString(" · ") {
            "${strings.role(it.role)} ${amount(it.amount, unit, locale, strings)}"
        }
    }

    /** Window id as shown: the stable id is already short ("5h", "7d", "day"). */
    fun windowLabel(w: QuotaWindow, strings: QuotaStrings = QuotaStrings.English): String =
        w.calendarPeriod?.let { strings.period(it) } ?: w.id

    /**
     * Short headline for compact places (picker section header): unit-true,
     * with a meaning prefix, never a percent for a prepaid balance.
     */
    fun shortText(
        pool: QuotaPool,
        status: PoolStatus,
        locale: Locale = Locale.getDefault(),
        strings: QuotaStrings = QuotaStrings.English,
    ): String? = when (pool.kind) {
        QuotaPool.Kind.prepaidBalance -> pool.amounts?.remaining?.let {
            strings.balance(amount(it, pool.unit, locale, strings))
        }
        QuotaPool.Kind.spendCap, QuotaPool.Kind.allowance, QuotaPool.Kind.usageWindow -> {
            val u = status.utilization
            val r = pool.amounts?.remaining
            when {
                u != null -> {
                    val window = status.decidingWindowId?.let { "$it " }.orEmpty()
                    strings.account(window + percentUsedText(u.percent, strings))
                }
                r != null -> strings.left(amount(r, pool.unit, locale, strings))
                else -> null
            }
        }
        QuotaPool.Kind.rateLimit -> null
    }
}

// MARK: - Status -> tone, and when a bar may be drawn

/** Visual tier for a quota status; the view maps it to a colour. */
@Suppress("EnumEntryName")
enum class QuotaTone {
    /** Available, but nothing measures "how much" (a prepaid balance): neutral, never green. */
    normal,

    /** Available AND backed by a reliable percent: the one case green is used. */
    healthy,

    /** Low or rate limited: orange. */
    caution,

    /** Exhausted / rejected: red. */
    critical,

    /** Unknown or expired data: grey, numbers are not trusted. */
    muted,
}

object QuotaToneRules {
    fun tone(status: QuotaStatus, freshness: QuotaFreshness, reliablePercent: Boolean): QuotaTone {
        if (freshness == QuotaFreshness.expired) return QuotaTone.muted
        return when (status) {
            QuotaStatus.unknown -> QuotaTone.muted
            QuotaStatus.exhausted -> QuotaTone.critical
            QuotaStatus.low, QuotaStatus.rateLimited -> QuotaTone.caution
            QuotaStatus.available -> if (reliablePercent) QuotaTone.healthy else QuotaTone.normal
        }
    }

    fun tone(s: PoolStatus): QuotaTone = tone(s.status, s.freshness, s.utilization != null)

    /** The compact picker pill never goes green; it only turns orange / red to warn. */
    fun pillTone(s: PoolStatus): QuotaTone = tone(s).let { if (it == QuotaTone.healthy) QuotaTone.normal else it }
}

object QuotaBarRules {
    /**
     * 0..1 fill for a progress bar, or null when no reliable percent exists.
     * Over-100% (overage) draws full; the text still says the real figure.
     */
    fun fraction(u: QuotaUtilization?): Double? {
        val d = u?.percent?.toDouble()?.div(100) ?: return null
        if (!d.isFinite()) return null
        return d.coerceIn(0.0, 1.0)
    }

    /** Bar for a pool's own amounts. Never for a prepaid balance, a rate limit or an unlimited pool. */
    fun poolFraction(pool: QuotaPool): Double? =
        pool.amounts?.let { fraction(it.computedUtilization(pool.kind, pool.unlimited)) }

    /** Bar for one window of a pool. */
    fun windowFraction(w: QuotaWindow, pool: QuotaPool): Double? =
        fraction(w.utilization(pool.kind, pool.unlimited))
}

// MARK: - Quota level (nav bar dot, picker tags, composer)

/**
 * [T-quota-tag-level] Three levels, shared by the nav bar dot and the picker
 * tags so they can never disagree:
 * - [depleted] (red tag): nothing left - a percent at or past 100% used, a
 *   currency balance at or below 0, or the vendor refusing the account;
 * - [low] (yellow tag / yellow dot): under [QuotaLevelRules.LOW_REMAINING_PERCENT]
 *   left, or under [QuotaLevelRules.LOW_BALANCE_USD];
 * - [normal]: otherwise, and whenever there is no usable data.
 */
@Suppress("EnumEntryName")
enum class QuotaLevel { normal, low, depleted }

object QuotaLevelRules {
    val LOW_REMAINING_PERCENT = BigDecimal(10)
    val LOW_BALANCE_USD: BigDecimal = BigDecimal.ONE

    /**
     * Units of the currency per 1 USD. Deliberately fixed (no live FX): the
     * rate only places a warning threshold and is never shown.
     */
    val UNITS_PER_USD: Map<String, BigDecimal> =
        mapOf("USD" to BigDecimal.ONE, "CNY" to BigDecimal("7.2"), "CNH" to BigDecimal("7.2"))

    fun usd(amount: BigDecimal, currency: String): BigDecimal? =
        UNITS_PER_USD[currency.uppercase()]?.let { amount.divide(it, MathContext.DECIMAL128) }

    /** Remaining share of a used percent. */
    fun remainingPercent(usedPercent: BigDecimal): BigDecimal = BigDecimal(100).subtract(usedPercent)

    /**
     * Over (status, pool) pairs. Prepaid currency balances of one account are
     * judged TOGETHER (DeepSeek keeps one balance per currency: CN¥36 + $0 is
     * not low); every other pool on its own; the worst level wins.
     */
    fun level(items: List<Pair<PoolStatus, QuotaPool>>): QuotaLevel {
        var worst = QuotaLevel.normal
        var prepaidUSD = BigDecimal.ZERO
        var hasPrepaid = false
        for ((status, p) in items) {
            if (status.status == QuotaStatus.unknown || status.freshness == QuotaFreshness.expired ||
                p.kind == QuotaPool.Kind.rateLimit || p.unlimited
            ) continue
            if (p.verdict == QuotaPool.Verdict.rejected) return QuotaLevel.depleted
            status.utilization?.let { u ->
                val left = remainingPercent(u.percent)
                if (left.signum() <= 0) return QuotaLevel.depleted
                if (left < LOW_REMAINING_PERCENT) worst = maxOf(worst, QuotaLevel.low)
            }
            val remaining = p.amounts?.remaining
            if (remaining == null) {
                if (status.status == QuotaStatus.exhausted && p.kind != QuotaPool.Kind.prepaidBalance) {
                    return QuotaLevel.depleted
                }
                continue
            }
            val unit = p.unit
            val inUSD = (unit as? QuotaUnit.Currency)?.let { usd(remaining, it.code) }
            if (inUSD != null) {
                when {
                    p.kind == QuotaPool.Kind.prepaidBalance -> {
                        prepaidUSD = prepaidUSD.add(inUSD)
                        hasPrepaid = true
                    }
                    inUSD.signum() <= 0 -> return QuotaLevel.depleted
                    inUSD < LOW_BALANCE_USD -> worst = maxOf(worst, QuotaLevel.low)
                }
            } else if (remaining.signum() <= 0) {
                // Credits / relay quota units: only "nothing left" is judged by
                // amount (no rate to place a $1 threshold); the rest via percent.
                return QuotaLevel.depleted
            }
        }
        if (hasPrepaid) {
            if (prepaidUSD.signum() <= 0) return QuotaLevel.depleted
            if (prepaidUSD < LOW_BALANCE_USD) worst = maxOf(worst, QuotaLevel.low)
        }
        return worst
    }

    /** The model's bound pools (its own and its account's). */
    fun level(v: ModelQuotaView, pools: Map<QuotaPoolKey, QuotaPool>): QuotaLevel =
        level(v.bound.mapNotNull { b -> pools[b.poolKey]?.let { b.status to it } })
}

/** [T-quota-low-dot] The nav bar dot turns yellow at [QuotaLevel.low] or worse. */
object QuotaLowRules {
    fun isLow(v: ModelQuotaView, pools: Map<QuotaPoolKey, QuotaPool>): Boolean =
        QuotaLevelRules.level(v, pools) != QuotaLevel.normal
}
