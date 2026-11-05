package com.openminis.app.quota

// [T-provider-quota] SiliconFlow balance adapter. Port of the SiliconFlow
// section of iOS QuotaAdapters.swift.
// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (quota/QuotaAdapters.kt); one file per vendor.

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
