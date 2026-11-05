package com.openminis.app.quota

// [T-provider-quota] Moonshot balance adapter. Port of the Moonshot section of
// iOS QuotaAdapters.swift.
// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (quota/QuotaAdapters.kt); one file per vendor.

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
