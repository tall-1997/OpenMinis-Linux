// [port-upstream-1.16] Ported verbatim from OpenMinis/OpenMinis upstream 1a71df0f (src/android/app/src/main/java/com/openminis/app/quota/QuotaRules.kt).
package com.openminis.app.quota

import java.math.BigDecimal
import java.math.MathContext

// [T-provider-quota] Pure rules over the quota model: percent derivation,
// freshness, pool / model status, snapshot merging, and the refresh / probe
// rate gates. Port of iOS QuotaRules.swift. No I/O and no Android types.

private val HUNDRED = BigDecimal(100)
private val MINUS_ONE = BigDecimal(-1)

internal fun BigDecimal.percentOf(total: BigDecimal): BigDecimal =
    divide(total, MathContext.DECIMAL128).multiply(HUNDRED)

// MARK: - Percent

/**
 * `computed` utilization - only for capped pool kinds, a positive total, and a
 * used/remaining figure in the same amounts (hence the same unit).
 */
fun QuotaAmounts.computedUtilization(kind: QuotaPool.Kind, unlimited: Boolean): QuotaUtilization? {
    if (unlimited) return null
    when (kind) {
        QuotaPool.Kind.spendCap, QuotaPool.Kind.allowance, QuotaPool.Kind.usageWindow -> Unit
        // Prepaid balances can be topped up without limit, and rate limits are
        // not consumable quota: neither gets a percent.
        QuotaPool.Kind.prepaidBalance, QuotaPool.Kind.rateLimit -> return null
    }
    val total = total ?: return null
    if (total.signum() <= 0) return null
    val used = used ?: remaining?.let { total.subtract(it) } ?: return null
    return QuotaUtilization(used.percentOf(total), QuotaUtilization.Basis.computed)
}

fun QuotaWindow.utilization(poolKind: QuotaPool.Kind, unlimited: Boolean): QuotaUtilization? {
    if (unlimited) return null
    if (poolKind == QuotaPool.Kind.prepaidBalance) return null
    reportedUsedFraction?.let { return QuotaUtilization(it.multiply(HUNDRED), QuotaUtilization.Basis.reported) }
    reportedRemainingFraction?.let {
        return QuotaUtilization(BigDecimal.ONE.subtract(it).multiply(HUNDRED), QuotaUtilization.Basis.reportedComplement)
    }
    return amounts?.computedUtilization(poolKind, unlimited)
}

// MARK: - Freshness

data class QuotaFreshnessPolicy(
    /** Active queries refresh at most every 10 minutes; anything younger is current. */
    val activeFreshMs: Long = 15 * 60_000L,
    /** Balances / spend caps: shown greyed with "N min ago" up to this age. */
    val activeAmountsStaleMs: Long = 6 * 3_600_000L,
    /** Allowances / windows move faster; hide their numbers sooner. */
    val activeWindowStaleMs: Long = 3_600_000L,
) {
    fun freshnessOfAmounts(pool: QuotaPool, now: Long): QuotaFreshness {
        val age = now - pool.observedAt
        if (age < activeFreshMs) return QuotaFreshness.fresh
        return if (age <= activeAmountsStaleMs) QuotaFreshness.stale else QuotaFreshness.expired
    }

    fun freshness(window: QuotaWindow, now: Long): QuotaFreshness {
        window.resetsAt?.let { if (it <= now) return QuotaFreshness.expired }
        val age = now - window.observedAt
        if (age < activeFreshMs) return QuotaFreshness.fresh
        return if (age <= activeWindowStaleMs) QuotaFreshness.stale else QuotaFreshness.expired
    }

    /** A pool is as fresh as its freshest part (amounts or any live window). */
    fun freshness(pool: QuotaPool, now: Long): QuotaFreshness {
        val parts = mutableListOf<QuotaFreshness>()
        if (pool.amounts != null || pool.windows.isEmpty()) parts += freshnessOfAmounts(pool, now)
        pool.windows.mapTo(parts) { freshness(it, now) }
        if (QuotaFreshness.fresh in parts) return QuotaFreshness.fresh
        if (QuotaFreshness.stale in parts) return QuotaFreshness.stale
        return QuotaFreshness.expired
    }
}

// MARK: - Status

object QuotaStatusRules {
    fun status(utilization: QuotaUtilization, thresholds: QuotaThresholds): QuotaStatus {
        if (utilization.percent >= thresholds.exhaustedPercent) return QuotaStatus.exhausted
        if (utilization.percent >= thresholds.lowPercent) return QuotaStatus.low
        return QuotaStatus.available
    }

    fun poolStatus(
        pool: QuotaPool,
        now: Long,
        policy: QuotaFreshnessPolicy = QuotaFreshnessPolicy(),
        thresholds: QuotaThresholds = QuotaThresholds(),
    ): PoolStatus {
        val freshness = policy.freshness(pool, now)
        if (freshness == QuotaFreshness.expired) {
            return PoolStatus(QuotaStatus.unknown, null, null, QuotaFreshness.expired)
        }
        if (pool.verdict == QuotaPool.Verdict.rejected) {
            return PoolStatus(QuotaStatus.exhausted, null, null, freshness)
        }

        if (pool.kind == QuotaPool.Kind.rateLimit) {
            val limited = pool.windows.firstOrNull { w ->
                if (policy.freshness(w, now) == QuotaFreshness.expired) return@firstOrNull false
                val rem = w.amounts?.remaining
                rem != null && rem.signum() == 0 && (w.resetsAt?.let { it > now } ?: false)
            }
            return PoolStatus(
                if (limited != null) QuotaStatus.rateLimited else QuotaStatus.available,
                limited?.id, null, freshness,
            )
        }

        // Windows: worst window wins; windows are never averaged.
        var bestStatus: QuotaStatus? = null
        var bestWindow: QuotaWindow? = null
        var bestUtil: QuotaUtilization? = null
        for (w in pool.windows) {
            if (policy.freshness(w, now) == QuotaFreshness.expired) continue
            val u = w.utilization(pool.kind, pool.unlimited)
            val remaining = w.amounts?.remaining
            val s = when {
                u != null -> status(u, thresholds)
                remaining != null -> if (remaining.signum() <= 0) QuotaStatus.exhausted else QuotaStatus.available
                else -> QuotaStatus.unknown
            }
            val current = bestStatus
            if (current == null) {
                bestStatus = s; bestWindow = w; bestUtil = u
            } else {
                val tighter = s > current ||
                    (s == current && (u?.percent ?: MINUS_ONE) > (bestUtil?.percent ?: MINUS_ONE))
                if (tighter) { bestStatus = s; bestWindow = w; bestUtil = u }
            }
        }

        // Non-windowed amounts.
        var amountStatus = QuotaStatus.unknown
        var amountUtil: QuotaUtilization? = null
        val amounts = pool.amounts
        if (amounts != null) {
            when (pool.kind) {
                QuotaPool.Kind.prepaidBalance -> {
                    val remaining = amounts.remaining
                    if (remaining != null) {
                        val unit = pool.unit
                        val low = (unit as? QuotaUnit.Currency)
                            ?.let { thresholds.lowBalance[pool.subjectId]?.get(it.code.uppercase()) }
                        amountStatus = when {
                            remaining.signum() <= 0 -> QuotaStatus.exhausted
                            low != null && remaining < low -> QuotaStatus.low
                            else -> QuotaStatus.available
                        }
                    }
                }
                QuotaPool.Kind.spendCap, QuotaPool.Kind.allowance, QuotaPool.Kind.usageWindow -> {
                    val u = amounts.computedUtilization(pool.kind, pool.unlimited)
                    val remaining = amounts.remaining
                    when {
                        // [T-quota-low-dot] Checked first: an unlimited cap reporting
                        // 0 "remaining" (no cap to subtract from) is not exhausted.
                        pool.unlimited -> amountStatus = QuotaStatus.available
                        u != null -> { amountUtil = u; amountStatus = status(u, thresholds) }
                        remaining != null ->
                            amountStatus = if (remaining.signum() <= 0) QuotaStatus.exhausted else QuotaStatus.available
                    }
                }
                QuotaPool.Kind.rateLimit -> Unit
            }
        }

        var result = PoolStatus(amountStatus, null, amountUtil, freshness)
        val bs = bestStatus
        if (bs != null && bs >= result.status && bs != QuotaStatus.unknown) {
            result = PoolStatus(bs, bestWindow?.id, bestUtil, freshness)
        }
        if (pool.verdict == QuotaPool.Verdict.warning && result.status < QuotaStatus.low) {
            result = result.copy(status = QuotaStatus.low)
        }
        return result
    }
}

// MARK: - Model key matching and effective status

object QuotaModelKey {
    /** Lower-cased slug: "GPT-5.3-Codex-Spark" and "gpt-5.3-codex-spark" match. */
    fun normalize(s: String): String =
        s.trim().lowercase().replace(" ", "-").replace("_", "-")

    /** `*` glob over normalized keys. */
    fun matches(pattern: String, key: String): Boolean {
        val p = normalize(pattern)
        val k = normalize(key)
        val parts = p.split("*")
        if (parts.size <= 1) return p == k
        var rest = k
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty()) continue
            when (i) {
                0 -> {
                    if (!rest.startsWith(part)) return false
                    rest = rest.substring(part.length)
                }
                parts.size - 1 -> return rest.endsWith(part)
                else -> {
                    val idx = rest.indexOf(part)
                    if (idx < 0) return false
                    rest = rest.substring(idx + part.length)
                }
            }
        }
        return true
    }
}

object QuotaModelResolver {
    /** The model's view over every pool that applies to it. */
    fun view(
        instanceId: String,
        modelId: String,
        subjectId: QuotaSubjectID?,
        pools: Map<QuotaPoolKey, QuotaPool>,
        bindings: List<QuotaPoolBinding>,
        manualBindings: List<QuotaPoolBinding> = emptyList(),
        now: Long,
        freshness: QuotaFreshnessPolicy = QuotaFreshnessPolicy(),
        thresholds: QuotaThresholds = QuotaThresholds(),
    ): ModelQuotaView {
        val modelKey = QuotaModelKey.normalize(modelId)
        if (subjectId == null) return ModelQuotaView.empty(instanceId, modelKey)

        val chosen = LinkedHashMap<QuotaPoolKey, QuotaPoolBinding>()
        fun consider(b: QuotaPoolBinding) {
            val pool = pools[b.poolKey] ?: return
            if (pool.subjectId != subjectId) return
            val applies = when (val t = b.target) {
                QuotaPoolBinding.Target.Subject -> true
                is QuotaPoolBinding.Target.Model -> QuotaModelKey.normalize(t.key) == modelKey
                is QuotaPoolBinding.Target.ModelFamily -> QuotaModelKey.matches(t.pattern, modelKey)
                // Products are not models: code-review style pools never bind to a model.
                is QuotaPoolBinding.Target.Product -> false
            }
            if (!applies) return
            // A pool that binds both as shared and model-specific is reported by
            // its most specific binding.
            val existing = chosen[b.poolKey]
            if (existing == null ||
                specificity(b) > specificity(existing) ||
                (specificity(b) == specificity(existing) && b.evidence.rank > existing.evidence.rank)
            ) {
                chosen[b.poolKey] = b
            }
        }
        bindings.forEach(::consider)
        manualBindings.forEach(::consider)

        val bound = chosen.values.mapNotNull { b ->
            val pool = pools[b.poolKey] ?: return@mapNotNull null
            ModelQuotaView.BoundPool(b.poolKey, b, QuotaStatusRules.poolStatus(pool, now, freshness, thresholds))
        }.sortedBy { it.poolKey.rawValue }

        val known = bound.filter { it.status.status != QuotaStatus.unknown }
        val deciding = known.maxWithOrNull(boundComparator)
        return ModelQuotaView(
            instanceId = instanceId,
            modelKey = modelKey,
            subjectId = subjectId,
            bound = bound,
            effective = deciding?.status?.status ?: QuotaStatus.unknown,
            deciding = deciding,
            isIncomplete = known.isNotEmpty() && known.size < bound.size,
            hasModelSpecificPool = bound.any { !it.isShared },
        )
    }

    /** Tighter = worse status, then the higher percent. */
    internal val boundComparator = Comparator<ModelQuotaView.BoundPool> { a, b ->
        if (a.status.status != b.status.status) a.status.status.compareTo(b.status.status)
        else (a.status.utilization?.percent ?: MINUS_ONE).compareTo(b.status.utilization?.percent ?: MINUS_ONE)
    }

    private fun specificity(b: QuotaPoolBinding): Int = when (b.target) {
        QuotaPoolBinding.Target.Subject -> 0
        is QuotaPoolBinding.Target.ModelFamily -> 1
        is QuotaPoolBinding.Target.Product -> 1
        is QuotaPoolBinding.Target.Model -> 2
    }
}

// MARK: - Merging snapshots

object QuotaMerge {
    /**
     * Upsert a snapshot's pools by key (windows merged by id, newer wins) and
     * its bindings by (pool, target) (higher evidence wins).
     *
     * `authoritative` snapshots - a complete active query - also retire pools
     * the SAME adapter produced for this subject earlier but no longer returns
     * (e.g. a currency the account no longer holds).
     */
    fun ingest(
        snapshot: UpstreamQuotaSnapshot,
        authoritative: Boolean,
        pools: MutableMap<QuotaPoolKey, QuotaPool>,
        bindings: MutableList<QuotaPoolBinding>,
    ) {
        if (authoritative) {
            val returned = snapshot.pools.map { it.id }.toSet()
            val retired = pools.values.filter {
                it.subjectId == snapshot.subjectId &&
                    it.provenance.adapterId == snapshot.provenance.adapterId &&
                    it.id !in returned
            }.map { it.id }.toSet()
            retired.forEach { pools.remove(it) }
            if (retired.isNotEmpty()) bindings.removeAll { it.poolKey in retired }
        }

        for (incoming in snapshot.pools) {
            val existing = pools[incoming.id]
            if (existing == null) {
                pools[incoming.id] = incoming
                continue
            }
            var merged = existing
            if (incoming.observedAt >= existing.observedAt) {
                merged = merged.copy(
                    kind = incoming.kind,
                    unit = incoming.unit,
                    vendorLabel = incoming.vendorLabel ?: existing.vendorLabel,
                    amounts = incoming.amounts,
                    verdict = incoming.verdict,
                    unlimited = incoming.unlimited,
                    provenance = incoming.provenance,
                )
            }
            val byId = LinkedHashMap<String, QuotaWindow>()
            existing.windows.forEach { byId.putIfAbsent(it.id, it) }
            for (w in incoming.windows) {
                val old = byId[w.id]
                if (old != null && old.observedAt > w.observedAt) continue
                byId[w.id] = w
            }
            val windows = existing.windows.mapNotNull { byId.remove(it.id) } +
                incoming.windows.mapNotNull { byId.remove(it.id) }
            pools[incoming.id] = merged.copy(
                windows = windows,
                observedAt = maxOf(existing.observedAt, incoming.observedAt),
            )
        }

        for (b in snapshot.bindings) {
            if (b.evidence == QuotaPoolBinding.Evidence.userManualMapping) continue
            val i = bindings.indexOfFirst { it.poolKey == b.poolKey && it.target == b.target }
            if (i >= 0) {
                if (b.evidence.rank > bindings[i].evidence.rank) bindings[i] = b
            } else {
                bindings += b
            }
        }
    }
}

// MARK: - Rate gates

data class QuotaRefreshPolicy(
    /** Normal refresh: at most once per canonical pool set / credential. */
    val normalIntervalMs: Long = 10 * 60_000L,
    /** A user-forced refresh bypasses [normalIntervalMs] but not this debounce. */
    val forcedMinIntervalMs: Long = 30_000L,
    /**
     * [T-quota-attention-refresh] A provider nobody is looking at is refreshed
     * at most this often; [normalIntervalMs] applies only while it is on screen.
     */
    val unattendedIntervalMs: Long = 3_600_000L,
    /**
     * Failure backoff steps (5 min -> 60 min). Starts at 5 min so a failing
     * provider on screen cannot be retried in a tight loop.
     */
    val backoffMs: List<Long> = listOf(300_000L, 600_000L, 1_200_000L, 1_800_000L, 3_600_000L),
    /** Capability probe: at most once per day per provider type + base URL. */
    val capabilityProbeIntervalMs: Long = 24 * 3_600_000L,
) {
    companion object {
        /**
         * A subscription (Claude / ChatGPT OAuth) refreshes on a 5-minute window
         * measured from the last update by EITHER source - a reply's headers or
         * an active query. Every other vendor keeps the 10-minute interval.
         */
        fun forVendor(vendor: QuotaVendor): QuotaRefreshPolicy = when (vendor) {
            QuotaVendor.claudeSubscription, QuotaVendor.codexSubscription ->
                QuotaRefreshPolicy(normalIntervalMs = SUBSCRIPTION_FRESH_WINDOW_MS)
            else -> QuotaRefreshPolicy()
        }

        const val SUBSCRIPTION_FRESH_WINDOW_MS = 5 * 60_000L
    }
}

sealed class QuotaRefreshDecision {
    data object Allowed : QuotaRefreshDecision()

    /** Retry-After from the server - binds forced refreshes too. */
    data class ServerCooldown(val until: Long) : QuotaRefreshDecision()

    /** Normal interval not yet elapsed. */
    data class TooSoon(val until: Long) : QuotaRefreshDecision()

    /** Failure backoff not yet elapsed. */
    data class Backoff(val until: Long) : QuotaRefreshDecision()

    /** Forced refresh within the debounce. */
    data class ForcedDebounce(val until: Long) : QuotaRefreshDecision()

    /** Credential rejected; automatic refresh stops until the key changes or the user forces one. */
    data object CredentialRejected : QuotaRefreshDecision()

    val isAllowed: Boolean get() = this == Allowed
}

object QuotaRefreshGate {
    /**
     * `attended` = the provider is on screen right now: the normal interval
     * applies. Otherwise the unattended hour does too, counted from the last
     * update by any source.
     */
    fun decide(
        record: QuotaRefreshRecord?,
        now: Long,
        forced: Boolean,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
        attended: Boolean = true,
    ): QuotaRefreshDecision {
        if (record == null) return QuotaRefreshDecision.Allowed
        record.serverCooldownUntil?.let { if (it > now) return QuotaRefreshDecision.ServerCooldown(it) }
        if (forced) {
            // [T-quota-manual-refresh] Debounced only against the user's own
            // previous manual refresh; an automatic request never blocks the button.
            val lastForced = record.lastForcedAt ?: return QuotaRefreshDecision.Allowed
            val until = lastForced + policy.forcedMinIntervalMs
            return if (until > now) QuotaRefreshDecision.ForcedDebounce(until) else QuotaRefreshDecision.Allowed
        }
        val kind = record.error?.kind
        if (kind == QuotaError.Kind.unauthorized || kind == QuotaError.Kind.forbidden) {
            return QuotaRefreshDecision.CredentialRejected
        }
        record.nextAllowedAt?.let { next ->
            if (next > now) {
                return if (record.error != null) QuotaRefreshDecision.Backoff(next) else QuotaRefreshDecision.TooSoon(next)
            }
        }
        if (!attended) {
            val last = maxOf(record.succeededAt ?: Long.MIN_VALUE, record.attemptedAt)
            val until = last + policy.unattendedIntervalMs
            if (until > now) return QuotaRefreshDecision.TooSoon(until)
        }
        return QuotaRefreshDecision.Allowed
    }

    fun recordSuccess(
        record: QuotaRefreshRecord?,
        now: Long,
        forced: Boolean,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
    ): QuotaRefreshRecord = (record ?: QuotaRefreshRecord(attemptedAt = now)).copy(
        attemptedAt = now,
        succeededAt = now,
        error = null,
        consecutiveFailures = 0,
        serverCooldownUntil = null,
        nextAllowedAt = now + policy.normalIntervalMs,
        lastForcedAt = if (forced) now else record?.lastForcedAt,
    )

    /**
     * [T-quota-claude-subscription] Fresh data from a passive observation
     * (response headers) counts as a refresh for the scheduler. A server
     * cooldown, the forced debounce and a longer pending backoff are kept.
     */
    fun recordObservation(
        record: QuotaRefreshRecord?,
        now: Long,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
    ): QuotaRefreshRecord {
        val r = record ?: QuotaRefreshRecord(attemptedAt = Long.MIN_VALUE)
        return r.copy(
            succeededAt = now,
            error = null,
            consecutiveFailures = 0,
            nextAllowedAt = maxOf(r.nextAllowedAt ?: Long.MIN_VALUE, now + policy.normalIntervalMs),
        )
    }

    fun recordFailure(
        record: QuotaRefreshRecord?,
        error: QuotaError,
        now: Long,
        forced: Boolean,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
    ): QuotaRefreshRecord {
        val base = record ?: QuotaRefreshRecord(attemptedAt = now)
        val failures = base.consecutiveFailures + 1
        val step = policy.backoffMs[minOf(failures - 1, policy.backoffMs.size - 1)]
        var next = now + step
        var cooldown = base.serverCooldownUntil
        error.retryAfter?.let {
            cooldown = it
            next = maxOf(next, it)
        }
        // Never retry a failure sooner than a success would have been.
        if (error.kind == QuotaError.Kind.notFound || error.kind == QuotaError.Kind.unsupportedResponse) {
            next = maxOf(next, now + policy.normalIntervalMs)
        }
        return base.copy(
            attemptedAt = now,
            error = error,
            consecutiveFailures = failures,
            serverCooldownUntil = cooldown,
            nextAllowedAt = next,
            lastForcedAt = if (forced) now else base.lastForcedAt,
        )
    }
}

object QuotaCapabilityGate {
    /** Whether the capability for `record.key` may be (re-)probed now. */
    fun shouldProbe(
        record: QuotaCapabilityRecord?,
        now: Long,
        userRequested: Boolean,
        policy: QuotaRefreshPolicy = QuotaRefreshPolicy(),
    ): Boolean {
        if (record == null) return true
        // [T-quota-manual-refresh] A manual refresh always re-checks a paused
        // endpoint; repeated taps are debounced by the refresh gate.
        if (userRequested) return true
        return now - record.probedAt >= policy.capabilityProbeIntervalMs
    }
}

// MARK: - Headline pool

object QuotaHeadline {
    /**
     * The pool that best represents a subject in one short line: the worst
     * status first; when everything is fine, the busiest window, then a prepaid
     * balance, then a sized allowance. Rate limits never headline.
     *
     * [ISS-035] One exception to "worst first": an emptied money balance does
     * not headline while the same account still holds money in another
     * currency (see [withoutSpentCurrencyBalances]).
     */
    fun pick(
        pools: List<QuotaPool>,
        now: Long,
        freshness: QuotaFreshnessPolicy = QuotaFreshnessPolicy(),
        thresholds: QuotaThresholds = QuotaThresholds(),
    ): Pair<QuotaPool, PoolStatus>? {
        val all = pools.filter { it.kind != QuotaPool.Kind.rateLimit }
            .map { it to QuotaStatusRules.poolStatus(it, now, freshness, thresholds) }
            .filter { it.second.status != QuotaStatus.unknown }
        val rated = withoutSpentCurrencyBalances(all, pool = { it.first }, status = { it.second.status })
        if (rated.isEmpty()) return null
        fun rank(p: QuotaPool): Int = when (p.kind) {
            QuotaPool.Kind.usageWindow -> 3
            QuotaPool.Kind.prepaidBalance -> 2
            QuotaPool.Kind.spendCap, QuotaPool.Kind.allowance -> 1
            QuotaPool.Kind.rateLimit -> 0
        }
        return rated.maxWithOrNull { a, b ->
            when {
                a.second.status != b.second.status -> a.second.status.compareTo(b.second.status)
                (a.second.utilization?.percent ?: MINUS_ONE).compareTo(b.second.utilization?.percent ?: MINUS_ONE) != 0 ->
                    (a.second.utilization?.percent ?: MINUS_ONE).compareTo(b.second.utilization?.percent ?: MINUS_ONE)
                rank(a.first) != rank(b.first) -> rank(a.first).compareTo(rank(b.first))
                // Lower key wins the tie (iOS: `a.id > b.id` means a sorts below).
                else -> b.first.id.rawValue.compareTo(a.first.id.rawValue)
            }
        }
    }

    /** A prepaid balance held as money ([QuotaUnit.Currency]), not credits or relay units. */
    fun isCurrencyBalance(pool: QuotaPool): Boolean =
        pool.kind == QuotaPool.Kind.prepaidBalance && pool.unit is QuotaUnit.Currency

    /**
     * [ISS-035] Removes the emptied money balances from a one-line choice when
     * another money balance of the same set still has funds (available or low).
     *
     * Why: DeepSeek keeps one balance per currency, so an account with
     * CN¥7.62 and US$0.00 has an exhausted USD pool next to an available CNY
     * pool. Under "worst first", US$0.00 headlined and read as "no money
     * left", while the account-level dot ([QuotaLevelRules.level], which judges
     * money balances together) correctly said otherwise.
     *
     * Scope, kept deliberately narrow (same as iOS):
     * - only prepaid balances in a currency; credits, spend caps, allowances
     *   and windows keep "worst first", so an exhausted window still headlines;
     * - a balance exhausted because the vendor rejected the account
     *   (verdict rejected) is never dropped: it is a refusal, not a zero amount;
     * - when every money balance is exhausted, nothing is dropped.
     * Among several funded currencies the caller's usual ordering decides (no
     * exchange rate is applied here); the detail sheet lists them all.
     */
    fun <T> withoutSpentCurrencyBalances(
        items: List<T>,
        pool: (T) -> QuotaPool?,
        status: (T) -> QuotaStatus,
    ): List<T> {
        fun spent(item: T): Boolean {
            val p = pool(item) ?: return false
            return isCurrencyBalance(p) && status(item) == QuotaStatus.exhausted &&
                p.verdict != QuotaPool.Verdict.rejected
        }
        val funded = items.any { item ->
            val p = pool(item)
            p != null && isCurrencyBalance(p) &&
                (status(item) == QuotaStatus.available || status(item) == QuotaStatus.low)
        }
        return if (funded) items.filterNot(::spent) else items
    }
}
