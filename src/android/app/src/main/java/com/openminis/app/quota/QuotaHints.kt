// [port-upstream-1.16] Ported verbatim from OpenMinis/OpenMinis upstream 1a71df0f (src/android/app/src/main/java/com/openminis/app/quota/QuotaHints.kt).
package com.openminis.app.quota

import java.math.BigDecimal
import java.util.Locale

// [T-provider-quota P2] What the model picker, the Model Group rows and the
// composer may say about quota. Pure rules, port of iOS
// QuotaPickerHintRules / QuotaComposerHintRules (QuotaPersistence.swift).

// MARK: - Model picker hints

/**
 * Deliberately quiet: nothing unless there is real data AND a warning, never a
 * percent for a prepaid balance, never an "available" / green state.
 */
object QuotaPickerHintRules {
    /**
     * The tightest constraint is a pool bound to THIS model. [detail] is e.g.
     * "5h 92%"; null when no percent exists.
     */
    data class ModelSpecificHint(val status: QuotaStatus, val detail: String?, val isManual: Boolean)

    /** Provider section rows: model-specific warnings only (the header shows the account). */
    fun modelRow(v: ModelQuotaView): ModelSpecificHint? {
        val d = v.deciding ?: return null
        if (!v.effective.isWarning || d.isShared) return null
        return ModelSpecificHint(d.status.status, detail(d.status), d.isManual)
    }

    /**
     * [T-quota-group-member-pill] Model Group member rows (picker group rows
     * and the Model Group settings page) have no provider header, so they show
     * one short figure after the model name, which opens the quota sheet:
     * - the model's OWN pool first, else the account's - the tightest of the kind;
     * - the used percent when a reliable percent exists, else the remaining
     *   amount in its unit ("$1.12", "CN¥36.51");
     * - nothing without real, unexpired data.
     */
    data class MemberPill(
        val text: String,
        val tone: QuotaTone,
        val poolKey: QuotaPoolKey,
        val isModelSpecific: Boolean,
        val isManual: Boolean,
        /** true = [text] is a used percent; false = a remaining amount. */
        val isPercent: Boolean,
        /** [T-quota-tag-level] Yellow / red tag. */
        val level: QuotaLevel = QuotaLevel.normal,
    )

    fun memberPill(
        v: ModelQuotaView,
        pools: Map<QuotaPoolKey, QuotaPool>,
        locale: Locale = Locale.getDefault(),
        strings: QuotaStrings = QuotaStrings.English,
    ): MemberPill? {
        fun usable(b: ModelQuotaView.BoundPool) =
            b.status.status != QuotaStatus.unknown && b.status.freshness != QuotaFreshness.expired &&
                pools[b.poolKey]?.let { it.kind != QuotaPool.Kind.rateLimit } == true
        // [ISS-035] Same exception as the section header: an emptied money
        // balance does not stand in for an account that still holds another.
        val known = QuotaHeadline.withoutSpentCurrencyBalances(
            v.bound.filter(::usable), pool = { pools[it.poolKey] }, status = { it.status.status })
        val chosen = known.filter { !it.isShared }.maxWithOrNull(QuotaModelResolver.boundComparator)
            ?: known.filter { it.isShared }.maxWithOrNull(QuotaModelResolver.boundComparator)
            ?: return null
        val pool = pools[chosen.poolKey] ?: return null
        val status = chosen.status
        val u = status.utilization
        val remaining = pool.amounts?.remaining
        val total = pool.amounts?.total
        val used = pool.amounts?.used
        val (text, isPercent) = when {
            u != null -> QuotaFormat.percentUsedText(u.percent, strings) to true
            remaining != null -> QuotaFormat.amount(remaining, pool.unit, locale, strings) to false
            total != null && used != null -> QuotaFormat.amount(total.subtract(used), pool.unit, locale, strings) to false
            else -> return null
        }
        return MemberPill(
            text = text,
            tone = QuotaToneRules.pillTone(status),
            poolKey = chosen.poolKey,
            isModelSpecific = !chosen.isShared,
            isManual = chosen.isManual,
            isPercent = isPercent,
            level = QuotaLevelRules.level(v, pools),
        )
    }

    /** Section header: the account's short text, only with real, non-expired data. */
    fun headerText(
        pool: QuotaPool,
        status: PoolStatus,
        locale: Locale = Locale.getDefault(),
        strings: QuotaStrings = QuotaStrings.English,
    ): String? {
        if (status.status == QuotaStatus.unknown || status.freshness == QuotaFreshness.expired) return null
        return QuotaFormat.shortText(pool, status, locale, strings)
    }

    private fun detail(s: PoolStatus): String? {
        val u = s.utilization ?: return null
        return s.decidingWindowId?.let { "$it " }.orEmpty() + QuotaFormat.percent(u.percent)
    }
}

// MARK: - Composer placeholder quota segment  [T-quota-composer-hint]

/**
 * The short quota figure appended to the composer's context-usage line
 * ("Context 62% used · 124k / 200k · Balance CN¥36.51"). It follows the
 * Model Group pill's choice exactly ([QuotaPickerHintRules.memberPill]): the
 * model's own pool first, else its account's; nothing without real data.
 */
object QuotaComposerHintRules {
    data class Segment(
        /** "Quota 14% used" / "Balance CN¥36.51" / "$4.20 left". */
        val text: String,
        /** The figure inside [text], tinted when [level] is not normal. */
        val highlight: String,
        val level: QuotaLevel,
    )

    fun segment(
        v: ModelQuotaView,
        pools: Map<QuotaPoolKey, QuotaPool>,
        now: Long = System.currentTimeMillis(),
        freshness: QuotaFreshnessPolicy = QuotaFreshnessPolicy(),
        locale: Locale = Locale.getDefault(),
        strings: QuotaStrings = QuotaStrings.English,
    ): Segment? {
        val pill = QuotaPickerHintRules.memberPill(v, pools, locale, strings) ?: return null
        val pool = pools[pill.poolKey]
        if (pill.isPercent && pool != null) {
            windowsFigure(pool, now, freshness)?.let { windows ->
                // [T-quota-composer-windows] A subscription with a 5h AND a 7d
                // limit shows both, compactly: "Quota 5h 14% / 7d 32% used".
                return Segment("${strings.quota()} ${strings.used(windows)}", windows, pill.level)
            }
        }
        val text = when {
            // "%@ used" already carries the meaning; the prefix says whose.
            pill.isPercent -> "${strings.quota()} ${pill.text}"
            pool?.kind == QuotaPool.Kind.prepaidBalance -> strings.balance(pill.text)
            else -> strings.left(pill.text)
        }
        return Segment(text, pill.text, pill.level)
    }

    /**
     * "5h 14% / 7d 32%": every live window of the pool that has a percent,
     * shortest first. null unless there are at least two - a single window
     * keeps the plain "14% used" wording.
     */
    fun windowsFigure(pool: QuotaPool, now: Long, freshness: QuotaFreshnessPolicy = QuotaFreshnessPolicy()): String? {
        val rated: List<Pair<QuotaWindow, BigDecimal>> = pool.windows.mapNotNull { w ->
            if (freshness.freshness(w, now) == QuotaFreshness.expired) return@mapNotNull null
            val u = w.utilization(pool.kind, pool.unlimited) ?: return@mapNotNull null
            w to u.percent
        }
        if (rated.size < 2) return null
        return rated.sortedWith(
            compareBy<Pair<QuotaWindow, BigDecimal>> { it.first.durationSeconds ?: Long.MAX_VALUE }
                .thenBy { it.first.id },
        ).joinToString(" / ") { "${it.first.id} ${QuotaFormat.percent(it.second)}" }
    }
}
