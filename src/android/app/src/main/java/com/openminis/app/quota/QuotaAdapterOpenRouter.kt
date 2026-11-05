package com.openminis.app.quota

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// [T-provider-quota] OpenRouter key-limit + credits adapter. Port of the
// OpenRouter section of iOS QuotaAdapters.swift.
// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (quota/QuotaAdapters.kt); one file per vendor.

/**
 * Two documented endpoints, fetched in parallel, failing independently:
 * - `GET /api/v1/key` -> `data.limit` (null = no cap), `usage`, `limit_remaining`,
 *   `limit_reset` -> a spendCap pool, only when `limit` is set.
 * - `GET /api/v1/credits` -> `data.total_credits`, `total_usage` -> a
 *   prepaidBalance pool, remaining = total_credits - total_usage.
 */
object OpenRouterQuotaAdapter : ProviderQuotaAdapter {
    override val adapterId = "openrouter.key+credits"
    override val vendor = QuotaVendor.openRouter
    const val KEY_ENDPOINT = "openrouter.ai/api/v1/key"
    const val CREDITS_ENDPOINT = "openrouter.ai/api/v1/credits"

    override fun capability(d: QuotaInstanceDescriptor) = QuotaCapability.activeQuery()

    override suspend fun fetch(ctx: QuotaFetchContext): QuotaFetchOutcome = coroutineScope {
        val k = async { run(ctx, KEY_ENDPOINT) }
        val c = async { run(ctx, CREDITS_ENDPOINT) }
        combine(k.await(), c.await(), ctx.subjectId, adapterId, ctx.now)
    }

    private suspend fun run(ctx: QuotaFetchContext, endpoint: String): Result<QuotaHttpResponse> = try {
        val r = ctx.transport.get("https://$endpoint", ctx.credential)
        QuotaHttp.error(r, endpoint, ctx.now)?.let { Result.failure(it) } ?: Result.success(r)
    } catch (e: QuotaFetchError) {
        Result.failure(e)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(QuotaFetchError(QuotaError.Kind.network, "$endpoint: ${e.javaClass.simpleName}"))
    }

    /** Pure; exposed for tests. A failure must carry a [QuotaFetchError]. */
    fun combine(
        key: Result<QuotaHttpResponse>,
        credits: Result<QuotaHttpResponse>,
        subjectId: QuotaSubjectID,
        adapterId: String = "openrouter.key+credits",
        now: Long,
    ): QuotaFetchOutcome {
        val pools = mutableListOf<QuotaPool>()
        val errors = mutableListOf<QuotaFetchError>()
        var offset: Long? = null
        // A /key answer with limit == null is a complete answer that yields no pool.
        var keyAnswered = false
        var creditsAnswered = false

        key.fold(
            onSuccess = { r ->
                offset = QuotaHttp.clockOffset(r, now)
                try {
                    parseKey(r.body, subjectId, adapterId, now)?.let { pools += it }
                    keyAnswered = true
                } catch (e: QuotaFetchError) { errors += e }
            },
            onFailure = { errors += it.asFetchError() },
        )
        credits.fold(
            onSuccess = { r ->
                offset = offset ?: QuotaHttp.clockOffset(r, now)
                try {
                    pools += parseCredits(r.body, subjectId, adapterId, now)
                    creditsAnswered = true
                } catch (e: QuotaFetchError) { errors += e }
            },
            onFailure = { errors += it.asFetchError() },
        )

        if (!keyAnswered && !creditsAnswered) {
            // Both failed: surface the most actionable error (auth first).
            val order = listOf(
                QuotaError.Kind.unauthorized, QuotaError.Kind.forbidden, QuotaError.Kind.rateLimited,
                QuotaError.Kind.server, QuotaError.Kind.notFound,
            )
            throw order.firstNotNullOfOrNull { k -> errors.firstOrNull { it.kind == k } } ?: errors[0]
        }
        val prov = officialProvenance(adapterId, "openrouter.ai/api/v1/{key,credits}")
        val snap = UpstreamQuotaSnapshot(
            subjectId = subjectId, provenance = prov, capturedAt = now, serverClockOffset = offset,
            pools = pools, bindings = pools.map { accountWideBinding(it.id) },
        )
        return QuotaFetchOutcome(snap, isComplete = keyAnswered && creditsAnswered, partialErrors = errors)
    }

    private fun Throwable.asFetchError(): QuotaFetchError =
        this as? QuotaFetchError ?: QuotaFetchError(QuotaError.Kind.network, javaClass.simpleName)

    fun parseKey(data: ByteArray, subjectId: QuotaSubjectID, adapterId: String = "openrouter.key+credits", now: Long): QuotaPool? {
        val obj = QuotaHttp.jsonObject(data, KEY_ENDPOINT)
        val d = obj.obj("data")
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "missing data $KEY_ENDPOINT")
        // limit == null (or absent) -> the key has no spend cap: no pool, never "100% left".
        val limit = d.dec("limit") ?: return null
        val remaining = d.dec("limit_remaining")
        // With a periodic reset (`limit_reset` set) `usage` is lifetime spend and
        // does not line up with the cap; only trust it for a lifetime cap.
        val periodic = d.str("limit_reset")?.isNotEmpty() ?: false
        val used = if (periodic || remaining != null) null else d.dec("usage")
        return QuotaPool(
            id = QuotaPoolKey.of(subjectId, "key-limit"), subjectId = subjectId,
            kind = QuotaPool.Kind.spendCap, unit = QuotaUnit.Currency("USD"), vendorLabel = "Key limit",
            amounts = QuotaAmounts(total = limit, used = used, remaining = remaining),
            provenance = officialProvenance(adapterId, KEY_ENDPOINT), observedAt = now,
        )
    }

    fun parseCredits(data: ByteArray, subjectId: QuotaSubjectID, adapterId: String = "openrouter.key+credits", now: Long): QuotaPool {
        val obj = QuotaHttp.jsonObject(data, CREDITS_ENDPOINT)
        val d = obj.obj("data")
        val total = d?.dec("total_credits")
        val usage = d?.dec("total_usage")
        if (total == null || usage == null) {
            throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "missing data.total_credits")
        }
        // A prepaid balance: no total/used, so no percent is ever derived from it.
        return QuotaPool(
            id = QuotaPoolKey.of(subjectId, "account-credits"), subjectId = subjectId,
            kind = QuotaPool.Kind.prepaidBalance, unit = QuotaUnit.Currency("USD"), vendorLabel = "Credits",
            amounts = QuotaAmounts(remaining = total.subtract(usage)),
            provenance = officialProvenance(adapterId, CREDITS_ENDPOINT), observedAt = now,
        )
    }
}
