package com.openminis.app.quota

import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal

// [T-provider-quota] ChatGPT / Codex subscription usage adapter (OAuth):
// active usage query + passive response headers. Port of the Codex
// subscription section of iOS QuotaAdapters.swift.
// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (quota/QuotaAdapters.kt); one file per vendor.

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
