package com.openminis.app.quota

import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal

// [T-provider-quota] Claude Pro / Max subscription usage adapter (OAuth):
// active usage query + passive response headers. Port of the Claude
// subscription section of iOS QuotaAdapters.swift.
// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (quota/QuotaAdapters.kt); one file per vendor.

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
