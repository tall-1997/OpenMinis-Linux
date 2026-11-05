package com.openminis.app.quota

// [T-provider-quota] The store's published, immutable state and the display
// views the UI reads. Port of iOS QuotaStore's state half.
// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (quota/QuotaStore.kt), split out of QuotaStore.kt so each file stays under
// the CI 800-line ratchet.

/** What the detail section shows for one instance. */
sealed class QuotaInstanceDisplay {
    /** Hidden: disabled instance or no credential (the credential section already asks for one). */
    data object Hidden : QuotaInstanceDisplay()
    data class Unsupported(val reason: QuotaSubject.UnsupportedReason) : QuotaInstanceDisplay()
    data object Loading : QuotaInstanceDisplay()

    /** Queried; upstream reported no pool for this credential. */
    data class Empty(val subjectId: QuotaSubjectID) : QuotaInstanceDisplay()

    /** Queried and failed, nothing cached to show. */
    data class Failed(val error: QuotaError) : QuotaInstanceDisplay()
    data class Pools(val subjectId: QuotaSubjectID) : QuotaInstanceDisplay()
}

sealed class QuotaRefreshResult {
    data object Refreshed : QuotaRefreshResult()
    data class Failed(val error: QuotaError) : QuotaRefreshResult()
    data class Blocked(val decision: QuotaRefreshDecision) : QuotaRefreshResult()
    data object Unsupported : QuotaRefreshResult()
    data class NotConfigured(val reason: QuotaSubject.NotConfiguredReason) : QuotaRefreshResult()
    data object CapabilityFailed : QuotaRefreshResult()
}

/** Everything the UI reads. Immutable: every change publishes a new value. */
data class QuotaState(
    val subjects: Map<QuotaSubjectID, QuotaSubject> = emptyMap(),
    val pools: Map<QuotaPoolKey, QuotaPool> = emptyMap(),
    val bindings: List<QuotaPoolBinding> = emptyList(),
    val capabilities: Map<QuotaCapabilityKey, QuotaCapabilityRecord> = emptyMap(),
    val summaries: Map<String, QuotaSnapshotSummary> = emptyMap(),
    val instanceSubjects: Map<String, QuotaSubjectID> = emptyMap(),
    /** Not persisted: why the last refresh of an instance did not reach a subject. */
    val instanceNotes: Map<String, QuotaSubject.NotConfiguredReason> = emptyMap(),
    /** Instances whose subject is being fetched right now. */
    val refreshingInstanceIds: Set<String> = emptySet(),
) {
    /** Each canonical pool of a subject exactly once, stable order. */
    fun poolsOf(subject: QuotaSubjectID): List<QuotaPool> =
        pools.values.filter { it.subjectId == subject }
            .sortedWith(compareBy<QuotaPool> { kindOrder(it.kind) }.thenBy { it.id.rawValue })

    fun bindingsOf(subject: QuotaSubjectID): List<QuotaPoolBinding> =
        bindings.filter { pools[it.poolKey]?.subjectId == subject }

    fun isRefreshing(instanceId: String): Boolean = instanceId in refreshingInstanceIds

    /** Other instances resolving to the same account (for the "don't add them up" note). */
    fun sharingInstanceIds(instanceId: String): List<String> {
        val sid = instanceSubjects[instanceId] ?: return emptyList()
        val s = subjects[sid] ?: return emptyList()
        return s.instanceIds.filter { it != instanceId }.sorted()
    }

    fun poolStatus(pool: QuotaPool, now: Long = System.currentTimeMillis()): PoolStatus =
        QuotaStatusRules.poolStatus(pool, now, QuotaStore.freshnessPolicy, QuotaStore.thresholds)

    /**
     * The model's view over every pool that applies to it.
     * [T-quota-no-manual-mapping] Upstream bindings only.
     */
    fun modelView(instanceId: String, modelId: String, now: Long = System.currentTimeMillis()): ModelQuotaView =
        QuotaModelResolver.view(
            instanceId = instanceId, modelId = modelId, subjectId = instanceSubjects[instanceId],
            pools = pools, bindings = bindings, manualBindings = emptyList(), now = now,
            freshness = QuotaStore.freshnessPolicy, thresholds = QuotaStore.thresholds,
        )

    /** [T-quota-tag-level] The account's level over ALL its pools (picker header tag). */
    fun subjectLevel(instanceId: String, now: Long = System.currentTimeMillis()): QuotaLevel {
        val sid = instanceSubjects[instanceId] ?: return QuotaLevel.normal
        return QuotaLevelRules.level(poolsOf(sid).map { poolStatus(it, now) to it })
    }

    /** The pool that best represents the instance's account in one short line. */
    fun headline(instanceId: String, now: Long = System.currentTimeMillis()): Pair<QuotaPool, PoolStatus>? {
        val sid = instanceSubjects[instanceId] ?: return null
        return QuotaHeadline.pick(poolsOf(sid), now, QuotaStore.freshnessPolicy, QuotaStore.thresholds)
    }

    /**
     * [T-quota-relay-probe] What the last probe of this relay found, for the
     * details: "Sub2API · /v1/usage", or why nothing was found. null for
     * non-relays and relays not probed yet.
     */
    data class RelayDetection(val text: String, val found: Boolean, val at: Long)

    fun relayDetection(d: QuotaInstanceDescriptor): RelayDetection? {
        if (QuotaVendorDetector.vendor(d) != QuotaVendor.customRelay) return null
        val rec = capabilities[d.capabilityKey] ?: return null
        return when (rec.status) {
            QuotaCapabilityRecord.Status.supported -> {
                val kind = RelayKind.fromAdapterId(rec.adapterId) ?: return null
                RelayDetection("${kind.displayName} · ${kind.path}", true, rec.probedAt)
            }
            QuotaCapabilityRecord.Status.failed, QuotaCapabilityRecord.Status.unsupported ->
                RelayDetection(rec.detail.orEmpty(), false, rec.probedAt)
        }
    }

    fun display(d: QuotaInstanceDescriptor): QuotaInstanceDisplay {
        val cap = QuotaAdapterRegistry.adapter(d).capability(d)
        if (!cap.active) {
            return QuotaInstanceDisplay.Unsupported(
                cap.unsupportedReason ?: QuotaSubject.UnsupportedReason.providerTypeUnsupported,
            )
        }
        if (!d.isEnabled) return QuotaInstanceDisplay.Hidden
        if (instanceNotes[d.instanceId] != null) return QuotaInstanceDisplay.Hidden
        val sid = instanceSubjects[d.instanceId]
        val rec = capabilities[d.capabilityKey]
        if (rec?.status == QuotaCapabilityRecord.Status.failed && (sid == null || poolsOf(sid).isEmpty())) {
            return QuotaInstanceDisplay.Unsupported(QuotaSubject.UnsupportedReason.capabilityProbeFailed)
        }
        if (sid == null) return QuotaInstanceDisplay.Loading
        val subject = subjects[sid] ?: return QuotaInstanceDisplay.Loading
        if (poolsOf(sid).isNotEmpty()) return QuotaInstanceDisplay.Pools(sid)
        if (isRefreshing(d.instanceId) || subject.lastRefresh == null) return QuotaInstanceDisplay.Loading
        subject.lastRefresh.error?.let { return QuotaInstanceDisplay.Failed(it) }
        return QuotaInstanceDisplay.Empty(sid)
    }

    companion object {
        fun kindOrder(k: QuotaPool.Kind): Int = when (k) {
            QuotaPool.Kind.prepaidBalance -> 0
            QuotaPool.Kind.spendCap -> 1
            QuotaPool.Kind.usageWindow -> 2
            QuotaPool.Kind.allowance -> 3
            QuotaPool.Kind.rateLimit -> 4
        }
    }
}
