package com.openminis.app.quota

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// [T-provider-quota] Kimi Code membership usage adapter. Port of the Kimi Code
// section of iOS QuotaAdapters.swift.
// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (quota/QuotaAdapters.kt); one file per vendor.

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
 * remaining count is skipped - the CLI shows such a row as "0 used", Minis
 * shows nothing rather than an invented number.
 *
 * The request carries Minis's own User-Agent and nothing else: Kimi Code's
 * terms forbid spoofing the client identity. (Android sets it explicitly; the
 * shared OkHttp transport would otherwise send `okhttp/<version>`.)
 */
object KimiCodeQuotaAdapter : ProviderQuotaAdapter {
    override val adapterId = "kimicode.usages"
    override val vendor = QuotaVendor.kimiCode
    val hosts = listOf("api.kimi.com", "api.kimi.ai")

    /** Minis's own UA (`Minis/<version> (Android <release>; <model>)`). Replaceable in tests. */
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
