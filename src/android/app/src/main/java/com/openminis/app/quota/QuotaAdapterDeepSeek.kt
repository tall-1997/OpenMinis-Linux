package com.openminis.app.quota

// [T-provider-quota] DeepSeek balance adapter. Port of the DeepSeek section of
// iOS QuotaAdapters.swift.
// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (quota/QuotaAdapters.kt); one file per vendor so adding a provider means
// adding one file plus a QuotaAdapterRegistry branch.

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
