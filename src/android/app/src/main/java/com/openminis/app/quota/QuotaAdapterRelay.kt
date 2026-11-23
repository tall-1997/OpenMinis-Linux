package com.openminis.app.quota

import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.net.URI

// [T-provider-quota] Relay (Sub2API / New API / OpenAI-compatible) balance
// adapter with its probe. Port of the relay section of iOS QuotaAdapters.swift.
// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (quota/QuotaAdapters.kt); one file per vendor.

/**
 * [T-quota-relay-probe] A custom relay's own balance / key quota.
 *
 * What a relay reports is its LEDGER for this key or account - what the user
 * pays the relay - not the upstream vendor's balance, so every pool is
 * relayAccountLedger / thirdParty and the UI says so.
 *
 * The type is not configured; it is detected by probing the documented
 * endpoints in order, identified by their data structure:
 * 1. Sub2API `GET /v1/usage` - a boolean `isValid` marks it. Modes:
 *    `quota_limited` (quota.remaining / limit / used), wallet (`balance`),
 *    subscription (`remaining`, -1 = unlimited). USD.
 * 2. New API `GET /api/usage/token` - `data.total_granted / total_used /
 *    total_available / unlimited_quota` in the relay's quota units (shown as
 *    units: the per-dollar rate is a per-site setting, never assumed).
 * 3. OpenAI-compatible `GET /v1/dashboard/billing/subscription` (+ `/usage`):
 *    `hard_limit_usd` (>= 1e8 = unlimited), `total_usage` in cents.
 * CLIProxyAPI has no endpoint a client key can read, so it probes as "none found".
 *
 * Requests go only to the relay the instance already sends its chats to, with
 * the same key; the root is the base URL without its trailing `/v1`.
 */
object RelayQuotaAdapter : ProviderQuotaAdapter {
    override val adapterId = "relay.probe"
    override val vendor = QuotaVendor.customRelay
    private val UNLIMITED_SENTINEL_USD = BigDecimal(100_000_000)

    /** [T-relay-probe-once] host|reason pairs already logged. */
    private val reportedHosts = java.util.Collections.synchronizedSet(
        java.util.LinkedHashSet<String>(),
    )

    override fun capability(d: QuotaInstanceDescriptor): QuotaCapability {
        // [T-relay-probe-once] Decide ONCE whether this relay is probeable.
        // `root()` also enforces https, so a plain-http relay used to be
        // re-attempted and re-warned on every didBecomeActive tick (115
        // "skipped" lines in one day of the 2026-11-22 field log).
        val probeable = root(d) != null
        if (d.customHost == null || !probeable) {
            val reason = probeUnsupportedReason(d)
            // [T-relay-probe-once] One breadcrumb per host+reason, ever:
            // capability() runs on every 60s tick and used to log the same
            // line 115 times a day. The UI shows this reason already.
            if (reportedHosts.add(d.customHost.orEmpty() + "|" + reason.name)) {
                com.openminis.app.logging.AppLogger.info(
                    "QuotaAdapterRelay",
                    "relay probe disabled for " + d.customHost + ": " + reason.name,
                )
            }
            return QuotaCapability.unsupported(reason)
        }
        return QuotaCapability.activeQuery(QuotaSource.relayAccountLedger, QuotaConfidence.thirdParty)
    }

    /**
     * [T-relay-probe-once] Why this instance cannot be probed, decided once.
     * A plain-http endpoint is [insecureTransport] (the probe refuses to send
     * a credential in clear text, while chat over it still works); anything
     * else is an unusable base URL.
     */
    private fun probeUnsupportedReason(d: QuotaInstanceDescriptor): QuotaSubject.UnsupportedReason {
        val raw = d.customBaseURL?.trim()
        val scheme = raw?.let { runCatching { URI(it).scheme?.lowercase() }.getOrNull() }
        return if (scheme == "http") QuotaSubject.UnsupportedReason.insecureTransport
            else QuotaSubject.UnsupportedReason.unknownVendor
    }

    /** Base URL without trailing "/" and "/v1" (case kept for the path), as scheme://authority/path. */
    fun root(d: QuotaInstanceDescriptor): String? {
        val raw = d.customBaseURL?.trim()
        if (raw.isNullOrEmpty()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        // [T-quota-relay-https] The probe sends the credential on every endpoint —
            // a plain-http relay leaks it to anything on the path. Chat may still
            // use the user's chosen http URL (their call), but the PROBE opt in
            // to https only.
            if (scheme != "https" || uri.host.isNullOrEmpty()) {
            // [T-relay-probe-once] Reported once by capability(); this runs
            // on every refresh attempt and the reason never changes.
                return null
            }
        var path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.lowercase().endsWith("/v1")) path = path.dropLast(3)
        path = path.trimEnd('/')
        return "$scheme://${uri.rawAuthority}$path"
    }

    override suspend fun fetch(ctx: QuotaFetchContext): QuotaFetchOutcome {
        val root = root(ctx.descriptor)
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "relay has no usable base URL")
        val errors = mutableListOf<QuotaFetchError>()

        // [T-relay-probe-first-success] When no hint, probe sub2api then newAPI
        // in order; the FIRST success returns immediately — no second request to
        // the other family (halves latency for relays that support both; the
        // wallet-preference trade-off is deliberately dropped: whichever family
        // answers first delivers its shape).
        val candidates = if (ctx.relayKindHint != null) {
            listOf(ctx.relayKindHint)
        } else {
            listOf(RelayKind.sub2api, RelayKind.newAPI)
        }
        for (kind in candidates) {
            try {
                val snap = query(kind, root, ctx)
                return QuotaFetchOutcome(snap, isComplete = true, partialErrors = errors, relayKind = kind)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: QuotaFetchError) {
                if (ctx.relayKindHint != null) throw e
                errors += e
            } catch (e: Exception) {
                if (ctx.relayKindHint != null) throw e
                // [T-relay-probe-first-success] Record transport failure and
                // continue; do not abort the entire chain before trying
                // remaining families.
                errors += QuotaFetchError(
                    QuotaError.Kind.badResponse,
                    "transport: ${e.message ?: e.javaClass.simpleName} at ${kind.path}",
                )
            }
        }

        // Both candidates failed — openAIBilling is the last-resort fallback.
        try {
            val openAIBillingSnap = query(RelayKind.openAIBilling, root, ctx)
            return QuotaFetchOutcome(openAIBillingSnap, isComplete = true, partialErrors = errors, relayKind = RelayKind.openAIBilling)
        } catch (e: QuotaFetchError) {
            // [T-relay-probe-first-success] QuotaFetchError (e.g. notFound from a
            // 404) must propagate as-is, not be demoted to badResponse.
            errors += e
        } catch (e: Exception) {
            // [T-relay-probe-first-success] Record transport failure and fall through.
            errors += QuotaFetchError(
                QuotaError.Kind.badResponse,
                "openAIBilling transport failure: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        // [T-relay-probe-classification] Classify the end-of-chain failure
        // honestly. A family that DID answer "not a balance endpoint" (404 or
        // unrecognized probe response) → notFound (parks relay for 24 h).
        // A credential rejection → keep the auth error (refresh gate stops on it).
        // Anything else (pure transport failures) → rethrow the first error,
        // badResponse takes the normal short backoff instead of lying about
        // a missing endpoint.
        val allPaths = (candidates + listOf(RelayKind.openAIBilling)).joinToString(", ") { it.path }
        val first = errors.firstOrNull()
        if (first != null &&
            errors.none { it.kind == QuotaError.Kind.notFound || it.kind == QuotaError.Kind.unsupportedResponse }
        ) {
            throw (errors.firstOrNull {
                it.kind == QuotaError.Kind.unauthorized || it.kind == QuotaError.Kind.forbidden
            } ?: first)
        }
        throw QuotaFetchError(QuotaError.Kind.notFound, "no balance endpoint on this relay (checked $allPaths)")
    }

    private suspend fun query(kind: RelayKind, root: String, ctx: QuotaFetchContext): UpstreamQuotaSnapshot {
        suspend fun get(path: String): QuotaHttpResponse {
            val r = ctx.transport.get(root + path, ctx.credential, ctx.extraHeaders)
            QuotaHttp.error(r, path, ctx.now)?.let { throw it }
            return r
        }
        val host = runCatching { URI(root).host }.getOrNull().orEmpty()
        return when (kind) {
            RelayKind.sub2api -> parseSub2API(get(kind.path).body, ctx.subjectId, host, ctx.now)
            RelayKind.newAPI -> parseNewAPI(get(kind.path).body, ctx.subjectId, host, ctx.now)
            RelayKind.openAIBilling -> {
                val sub = get(kind.path).body
                val usage = try {
                    get("/v1/dashboard/billing/usage").body
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                parseOpenAIBilling(sub, usage, ctx.subjectId, host, ctx.now)
            }
        }
    }

    private fun provenance(kind: RelayKind, host: String) =
        QuotaProvenance(QuotaSource.relayAccountLedger, QuotaConfidence.thirdParty, kind.adapterId, kind.path, host)

    private fun snapshot(pool: QuotaPool, kind: RelayKind, subjectId: QuotaSubjectID, host: String, now: Long) =
        UpstreamQuotaSnapshot(
            subjectId = subjectId, provenance = provenance(kind, host), capturedAt = now, pools = listOf(pool),
            bindings = listOf(QuotaPoolBinding(pool.id, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.assumedSubjectWide)),
        )

    private fun obj(data: ByteArray, kind: RelayKind): JsonObject =
        runCatching { QuotaHttp.json.parseToJsonElement(data.decodeToString()) as? JsonObject }.getOrNull()
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "not JSON at ${kind.path}")

    // Sub2API

    fun parseSub2API(data: ByteArray, subjectId: QuotaSubjectID, relayHost: String, now: Long): UpstreamQuotaSnapshot {
        val o = obj(data, RelayKind.sub2api)
        val isValid = o.bool("isValid")
            ?: throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "no isValid: not Sub2API")
        val prov = provenance(RelayKind.sub2api, relayHost)
        val keyUsed = o.obj("usage")?.obj("total")?.dec("actual_cost")
        val key = QuotaPoolKey.of(subjectId, "relay")
        var pool = QuotaPool(
            id = key, subjectId = subjectId, kind = QuotaPool.Kind.prepaidBalance, unit = QuotaUnit.Currency("USD"),
            vendorLabel = "Sub2API", verdict = if (!isValid) QuotaPool.Verdict.rejected else null,
            provenance = prov, observedAt = now,
        )
        when {
            o.str("mode") == "quota_limited" -> {
                val q = o.obj("quota")
                val limit = q?.dec("limit")
                val remaining = q?.dec("remaining")?.max(BigDecimal.ZERO)
                val used = if (o["quota"] != null) q?.dec("used") else keyUsed
                pool = pool.copy(
                    kind = if (limit != null) QuotaPool.Kind.spendCap else QuotaPool.Kind.prepaidBalance,
                    vendorLabel = "Sub2API key quota",
                    amounts = QuotaAmounts(total = limit, used = used, remaining = remaining),
                )
            }
            o.containsKey("balance") -> pool = pool.copy(
                vendorLabel = "Sub2API wallet", amounts = QuotaAmounts(remaining = o.dec("balance")),
            )
            else -> {
                val remaining = o.dec("remaining")
                pool = if (remaining != null && remaining.signum() < 0) {
                    pool.copy(vendorLabel = "Sub2API subscription", unlimited = true, amounts = keyUsed?.let { QuotaAmounts(used = it) })
                } else {
                    pool.copy(vendorLabel = "Sub2API subscription", amounts = QuotaAmounts(used = keyUsed, remaining = remaining))
                }
            }
        }
        if (pool.amounts?.remaining == null && pool.amounts?.total == null && !pool.unlimited) {
            throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "Sub2API usage without amounts")
        }
        return snapshot(pool, RelayKind.sub2api, subjectId, relayHost, now)
    }

    // New API

    fun parseNewAPI(data: ByteArray, subjectId: QuotaSubjectID, relayHost: String, now: Long): UpstreamQuotaSnapshot {
        val o = obj(data, RelayKind.newAPI)
        val d = o.obj("data")
        // [T-relay-newapi-user-quota] Newer New API deployments answer
        // `/api/usage/token` with BOTH token-level totals AND the account
        // wallet (`data.user_quota.total_available`, in the relay's quota
        // units). Tokens are frequently created unlimited with zero totals,
        // so the token level reads "unlimited / 0" while the user's REAL
        // balance lives one level down — the field report's "查询不到对应
        // 余额". Prefer the account wallet when present; older deployments
        // without user_quota keep the token-level shape verbatim.
        // Different deployments use different field names; try common aliases.
        val uqObj = listOf("user_quota", "wallet", "balance", "account")
            .mapNotNull { d?.obj(it) }
            .firstOrNull()
        if (d == null || (d["total_granted"] == null && d["total_available"] == null && uqObj == null)) {
            throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "no data.total_granted: not New API")
        }
        val key = QuotaPoolKey.of(subjectId, "relay")
        val nameLabel = d.str("name")?.let { "New API · $it" } ?: "New API token quota"
        val uqAvailable = listOf("total_available", "available")
            .mapNotNull { uqObj?.dec(it) }
            .firstOrNull()
        val uqUsed = listOf("total_used", "used", "spent", "consumed")
            .mapNotNull { uqObj?.dec(it) }
            .firstOrNull()
        val pool = if (uqObj != null && (uqAvailable != null || uqUsed != null)) {
            // [T-relay-wallet-negative-sentinel] Mirror the legacy sentinel:
            // a negative total_available means "unlimited", not an overdrawn
            // wallet that renders "used up". Zero stays a genuinely empty
            // wallet.
            val walletUnlimited = uqAvailable?.let { it.signum() < 0 } ?: false
            QuotaPool(
                id = key, subjectId = subjectId, kind = QuotaPool.Kind.allowance, unit = QuotaUnit.Custom("quota"),
                vendorLabel = "$nameLabel · account",
                amounts = if (walletUnlimited) null else QuotaAmounts(used = uqUsed, remaining = uqAvailable),
                unlimited = walletUnlimited, provenance = provenance(RelayKind.newAPI, relayHost), observedAt = now,
            )
        } else {
            val granted = d.dec("total_granted")
            val used = d.dec("total_used")
            val available = d.dec("total_available")
            val unlimited = d.bool("unlimited_quota") == true || (granted?.let { it.signum() < 0 } ?: false)
            QuotaPool(
                id = key, subjectId = subjectId, kind = QuotaPool.Kind.allowance, unit = QuotaUnit.Custom("quota"),
                vendorLabel = nameLabel,
                amounts = if (unlimited) QuotaAmounts(used = used) else QuotaAmounts(total = granted, used = used, remaining = available),
                unlimited = unlimited, provenance = provenance(RelayKind.newAPI, relayHost), observedAt = now,
            )
        }
        // [T-relay-newapi-empty-amounts] A wallet or token level whose field
        // names we do not know parses to amounts with no number at all. That
        // must not become a successful empty pool (it locks the relay kind
        // hint for a day and can overwrite good prior data on merge) - report
        // it like the Sub2API guard does, so the probe chain moves on to the
        // next family. Like every throw here, a hinted fetch rethrows it via
        // the probe's catch site.
        if (pool.amounts?.total == null && pool.amounts?.used == null && pool.amounts?.remaining == null &&
            !pool.unlimited
        ) {
            throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "New API usage without amounts")
        }
        return snapshot(pool, RelayKind.newAPI, subjectId, relayHost, now)
    }

    // OpenAI-compatible billing

    fun parseOpenAIBilling(
        subscription: ByteArray,
        usage: ByteArray?,
        subjectId: QuotaSubjectID,
        relayHost: String,
        now: Long,
    ): UpstreamQuotaSnapshot {
        fun unwrap(o: JsonObject): JsonObject = o.obj("data") ?: o
        val sub = unwrap(obj(subscription, RelayKind.openAIBilling))
        val use = usage?.let { runCatching { QuotaHttp.json.parseToJsonElement(it.decodeToString()) as? JsonObject }.getOrNull() }
            ?.let(::unwrap) ?: JsonObject(emptyMap())
        val key = QuotaPoolKey.of(subjectId, "relay")
        var pool = QuotaPool(
            id = key, subjectId = subjectId, kind = QuotaPool.Kind.spendCap, unit = QuotaUnit.Currency("USD"),
            vendorLabel = "Relay billing", provenance = provenance(RelayKind.openAIBilling, relayHost), observedAt = now,
        )
        val usedUSD = use.dec("total_usage")?.divide(BigDecimal(100))?.canonical() ?: use.dec("used_usd")
        val balance = sub.dec("balance")
        val hard = sub.dec("hard_limit_usd")
        pool = when {
            balance != null -> pool.copy(kind = QuotaPool.Kind.prepaidBalance, amounts = QuotaAmounts(used = usedUSD, remaining = balance))
            hard != null -> if (hard >= UNLIMITED_SENTINEL_USD) {
                pool.copy(unlimited = true, amounts = usedUSD?.let { QuotaAmounts(used = it) })
            } else {
                pool.copy(amounts = QuotaAmounts(
                    total = hard, used = usedUSD, remaining = usedUSD?.let { hard.subtract(it).max(BigDecimal.ZERO).canonical() },
                ))
            }
            else -> throw QuotaFetchError(QuotaError.Kind.unsupportedResponse, "no hard_limit_usd / balance: not a billing API")
        }
        return snapshot(pool, RelayKind.openAIBilling, subjectId, relayHost, now)
    }
}
