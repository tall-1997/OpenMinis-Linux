package com.openminis.app.quota

// [port-upstream-1.16] New in this port (not upstream): the programmatic,
// agent-facing read API over [QuotaStore]. Reserved for the minis-config
// quota domain: an agent (or the config tooling) reads structured quota facts
// from here instead of scraping UI state. Read-only, no I/O, no credentials,
// no localized text — every value is a number, an enum name or an ISO-8601
// timestamp so downstream consumers stay locale-independent.

import java.math.BigDecimal

/**
 * Structured quota snapshot for programmatic consumers (minis-config, agents).
 * See [QuotaAgentApi.snapshot].
 */
data class QuotaAgentSnapshot(
    /** The provider instance this snapshot was asked for. */
    val instanceId: String,
    /**
     * Why there are no pools, when there are none:
     * unsupported / loading / empty / failed / hidden.
     */
    val status: Status,
    /** Machine-readable failure detail ("HTTP 401 …"); null when not failed. */
    val errorDetail: String? = null,
    /** The quota subject (vendor + hashed account) resolved for this instance. */
    val vendor: String? = null,
    val subjectId: String? = null,
    /** Masked key label ("sk-…a1b2") or "OAuth" — never the credential. */
    val accountLabel: String? = null,
    /** Worst level over the account's pools: normal / low / depleted. */
    val level: String? = null,
    /** One entry per canonical pool of the account, store display order. */
    val pools: List<Pool> = emptyList(),
    /** Epoch ms when the snapshot was taken. */
    val at: Long,
) {
    enum class Status { unsupported, loading, empty, failed, hidden, available }

    data class Pool(
        /** Canonical pool key "<vendor>:<accountKey>:<family>[:<qualifier>]". */
        val key: String,
        /** Vendor's own name for the pool, when it has one. */
        val vendorLabel: String?,
        /** prepaidBalance / spendCap / allowance / usageWindow / rateLimit. */
        val kind: String,
        /** Currency ISO code, "credits", "requests", "tokens", "percentOnly", … */
        val unit: String,
        val total: BigDecimal?,
        val used: BigDecimal?,
        val remaining: BigDecimal?,
        /** null = no reliable percent (prepaid balances never have one). */
        val usedPercent: BigDecimal?,
        /** unknown / available / low / rateLimited / exhausted. */
        val status: String,
        /** fresh / stale / expired. */
        val freshness: String,
        /** Epoch ms of the newest observation behind this pool. */
        val observedAt: Long,
    )
}

/**
 * Programmatic read API over the quota store — the "额度状态进 minis-config"
 * seam. UI code reads [QuotaStore.state] / [QuotaState]; agents and config
 * tooling read this. Never blocks: it renders the current cached state, and a
 * caller wanting fresh data triggers `QuotaStore.refresh` first.
 */
object QuotaAgentApi {
    /**
     * A structured snapshot of the instance's account quota, with an optional
     * model view: when [modelId] is set, [QuotaAgentSnapshot.level] reflects
     * the pools that actually apply to that model (its own pool first), like
     * the model picker's hint rules.
     */
    fun snapshot(instanceId: String, modelId: String? = null, now: Long = System.currentTimeMillis()): QuotaAgentSnapshot =
        buildSnapshot(QuotaStore.state.value, instanceId, modelId, now)

    private fun buildSnapshot(
        st: QuotaState,
        instanceId: String,
        modelId: String?,
        now: Long,
    ): QuotaAgentSnapshot {
        val instanceSubjectId = st.instanceSubjects[instanceId]
        val subject = instanceSubjectId?.let { st.subjects[it] }

        fun poolsOf(sid: QuotaSubjectID): List<Pair<QuotaPool, PoolStatus>> =
            st.poolsOf(sid).map { it to st.poolStatus(it, now) }

        /** [QuotaLevelRules.level] judges (status, pool) pairs, worst wins. */
        fun levelOf(pairs: List<Pair<QuotaPool, PoolStatus>>): QuotaLevel =
            QuotaLevelRules.level(pairs.map { it.second to it.first })

        // Resolve display without the UI layer: replicate the cheap part of
        // QuotaState.display's decision tree on the instance id alone.
        val sid = instanceSubjectId
        if (sid == null) {
            return QuotaAgentSnapshot(
                instanceId = instanceId, status = QuotaAgentSnapshot.Status.loading,
                vendor = null, subjectId = null, accountLabel = subject?.accountLabel, at = now,
            )
        }
        val pools = poolsOf(sid)
        if (pools.isEmpty()) {
            val failed = subject?.lastRefresh?.error
            return QuotaAgentSnapshot(
                instanceId = instanceId,
                status = when {
                    failed != null -> QuotaAgentSnapshot.Status.failed
                    st.isRefreshing(instanceId) || subject?.lastRefresh == null -> QuotaAgentSnapshot.Status.loading
                    else -> QuotaAgentSnapshot.Status.empty
                },
                errorDetail = failed?.let { "${it.kind.name} ${it.detail}" },
                vendor = sid.vendor.name,
                subjectId = sid.toString(),
                accountLabel = subject?.accountLabel,
                at = now,
            )
        }
        val status = when {
            subject?.state is QuotaSubject.State.Failed -> QuotaAgentSnapshot.Status.failed
            else -> QuotaAgentSnapshot.Status.available
        }
        return QuotaAgentSnapshot(
            instanceId = instanceId,
            status = status,
            errorDetail = subject?.lastRefresh?.error?.let { "${it.kind.name} ${it.detail}" },
            vendor = sid.vendor.name,
            subjectId = sid.toString(),
            accountLabel = subject?.accountLabel,
            level = levelOf(pools).name,
            pools = pools.map { (pool, ps) ->
                QuotaAgentSnapshot.Pool(
                    key = pool.id.rawValue,
                    vendorLabel = pool.vendorLabel,
                    kind = pool.kind.name,
                    unit = when (val u = pool.unit) {
                        is QuotaUnit.Currency -> "currency:${u.code}"
                        is QuotaUnit.Credits -> "credits"
                        QuotaUnit.Requests -> "requests"
                        is QuotaUnit.Tokens -> "tokens:${u.kind.name}"
                        QuotaUnit.PercentOnly -> "percentOnly"
                        is QuotaUnit.RateLimit -> "rateLimit:${u.metric.name}"
                        is QuotaUnit.Custom -> "custom:${u.name}"
                    },
                    total = pool.amounts?.total,
                    used = pool.amounts?.used,
                    remaining = pool.amounts?.remaining,
                    usedPercent = ps.utilization?.percent,
                    status = ps.status.name,
                    freshness = ps.freshness.name,
                    observedAt = pool.observedAt,
                )
            },
            at = now,
        ).let { snap ->
            if (modelId == null) snap else {
                // The model's own view decides the level when one is given.
                val view = st.modelView(instanceId, modelId, now)
                snap.copy(level = QuotaLevelRules.level(view, st.pools).name)
            }
        }
    }
}
