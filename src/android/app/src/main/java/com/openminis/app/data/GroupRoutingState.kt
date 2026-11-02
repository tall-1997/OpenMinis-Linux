package com.openminis.app.data

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.markCredentialCooled

/**
 * [T-routing-strategy-x] Process-scoped routing state + the pure per-call
 * decision rule for the five strategies added beside fallback/loadBalance.
 *
 * Why an object: the router must remember three kinds of short-lived state
 * across calls (round-robin cursor, rate-limit cooldowns, quota counters).
 * Persisting them to Room would churn the DB on every message and buy
 * nothing — a cursor is meaningless after a process restart, a cooldown
 * expires in seconds/minutes, and the quota window's authoritative copy is
 * the group config itself (this object only caches the running counter).
 *
 * Contracts pinned by GroupRoutingStrategyTest:
 * - fallback / loadBalance with no config behave byte-identically to the
 *   pre-x implementation (first member / hash of session key), and honor
 *   preferredEntryId exactly as before.
 * - The new strategies deliberately IGNORE preferredEntryId: rotation,
 *   weights, usage and quota are scheduling decisions a session binding
 *   would defeat.
 * - A cooled-down member is skipped by every strategy; when the whole pool
 *   is cooled down the original pool serves anyway (a degraded answer beats
 *   no answer).
 * - quotaWindow pre-deducts one send per pick and is not refunded on
 *   failure: a failed send still consumed the upstream channel's quota.
 *   The counter refills to quotaWindowTotal when nowMs passes
 *   quotaWindowResetAt.
 */
object GroupRoutingState {

    /**
     * Request character count at/above which [RoutingStrategy.smart] treats
     * a turn as complex and routes it to the first (strong) member.
     */
    const val SMART_COMPLEX_CHARS = 12_000

    /** Cooldown applied to a member when a 429 carries no usable Retry-After. */
    const val DEFAULT_COOLDOWN_MS = 30_000L

    data class Pick(
        val entry: ModelEntry,
        /** Extra routing context for status reporting; null when silent. */
        val reason: String?,
    )

    // groupId -> round-robin cursor (index of the NEXT pick).
    private val roundRobinCursors = mutableMapOf<String, Int>()
    // entryId -> epoch ms until which every strategy skips this member.
    private val cooldownUntil = mutableMapOf<String, Long>()
    // groupId -> sends left in the current quota window (running counter).
    private val quotaRemaining = mutableMapOf<String, Int>()
    // entryId -> times this process picked the member (leastUsed signal).
    private val servingCounts = mutableMapOf<String, Int>()
    // groupId -> entryId most recently picked by this process, so the
    // error path can cool down "the model that just 429'd" without knowing
    // anything about the provider that produced the error.
    private val servingEntry = mutableMapOf<String, String>()

    // [T-credential-pool-x] groupId -> provider instanceId most recently
    // picked by this process, so the error path can cool the credential
    // slot of "the provider instance that just 429'd" (see
    // GroupRoutingState.markCredentialCooldownFor).
    private val servingInstance = mutableMapOf<String, String>()

    /** Test hook only: drop all process state. */
    fun resetForTest() {
        roundRobinCursors.clear()
        cooldownUntil.clear()
        quotaRemaining.clear()
        servingCounts.clear()
        servingEntry.clear()
        servingInstance.clear()
    }

    /**
     * [T-routing-strategy-x] Rate-limit cooldown: after a 429 (or a
     * permanent capacity rejection) the serving member is parked until
     * [untilMs] so subsequent turns route around it instead of hammering
     * the same exhausted channel.
     */
    fun markCooldown(entryId: String, untilMs: Long) {
        cooldownUntil[entryId] = maxOf(cooldownUntil[entryId] ?: 0L, untilMs)
    }

    /** Record which entry serves the group right now (error-path lookup). */
    fun noteServing(groupId: String, entryId: String) {
        servingEntry[groupId] = entryId
        servingCounts[entryId] = (servingCounts[entryId] ?: 0) + 1
    }

    /**
     * [T-credential-pool-x] Record which provider INSTANCE serves the group
     * right now — the credential-cooldown analogue of [noteServing].  The
     * entry alone cannot identify the pool slot: a group member entry only
     * points at the provider instance, and the credential pick already
     * happened inside it.
     */
    fun noteServingInstance(groupId: String, instanceId: String) {
        servingInstance[groupId] = instanceId
    }

    /**
     * [T-credential-pool-x] Error-path cooldown for the credential the
     * serving instance last picked.  No-op when the group never recorded an
     * instance or the error is not a quota/key signal.
     */
    fun markCredentialCooldownFor(groupId: String?, error: LLMError, repository: ProviderRepository) {
        val instanceId = groupId?.let { servingInstance(it) } ?: return
        repository.markCredentialCooled(instanceId, error)
    }

    /**
     * [T-credential-pool-x] Entry + credential cooldown in one step — the
     * single call path from the agent loop's 429/容量 error branch.
     */
    fun markRoutingCooldownFor(groupId: String?, error: LLMError, repository: ProviderRepository) {
        markServingCooldownFor(groupId, error)
        markCredentialCooldownFor(groupId, error, repository)
    }

    /** The entry this process most recently picked for [groupId], if any. */
    fun servingEntry(groupId: String): String? = servingEntry[groupId]

    /**
     * [T-credential-pool-x] The provider instanceId this process most
     * recently picked for [groupId], if any.
     */
    fun servingInstance(groupId: String): String? = servingInstance[groupId]

    /**
     * Pure selection over [members] (already filtered by capability and
     * credential). [preferredEntryId] is honored only by fallback /
     * loadBalance; [usageCounts] overrides the process-internal leastUsed
     * signal when non-null (deterministic tests); [random01] drives the
     * weighted roulette wheel.
     */
    fun pick(
        group: ModelGroup,
        members: List<ModelEntry>,
        preferredEntryId: String?,
        sessionKey: String,
        nowMs: Long = System.currentTimeMillis(),
        usageCounts: Map<String, Int>? = null,
        random01: Double = kotlin.random.Random.nextDouble(),
        complexRequest: Boolean = false,
    ): Pick? {
        if (members.isEmpty()) return null
        // Cooldown filter shared by every strategy; an all-cooled pool
        // degrades back to the original members rather than stalling.
        val active = members.filter { (cooldownUntil[it.id] ?: 0L) <= nowMs }
            .ifEmpty { members }
        return when (group.strategy) {
            RoutingStrategy.fallback -> {
                val hit = preferredEntryId?.let { pid -> active.firstOrNull { it.id == pid } }
                Pick(hit ?: active.first(), null)
            }
            RoutingStrategy.loadBalance -> {
                val hit = preferredEntryId?.let { pid -> active.firstOrNull { it.id == pid } }
                Pick(hit ?: active[Math.floorMod(sessionKey.hashCode(), active.size)], null)
            }
            RoutingStrategy.roundRobin -> {
                val cursor = roundRobinCursors.getOrDefault(group.id, 0)
                roundRobinCursors[group.id] = cursor + 1
                Pick(active[Math.floorMod(cursor, active.size)], null)
            }
            RoutingStrategy.weighted -> {
                val weights = active.map { m ->
                    (group.memberWeights[m.id] ?: 1).coerceAtLeast(1)
                }
                val total = weights.sum()
                val r = random01.coerceIn(0.0, 1.0) * total
                var acc = 0
                var hit = active.last()
                for ((m, w) in active.zip(weights)) {
                    acc += w
                    if (r < acc) {
                        hit = m
                        break
                    }
                }
                Pick(hit, null)
            }
            RoutingStrategy.leastUsed -> {
                val counts = usageCounts ?: servingCounts
                Pick(active.minByOrNull { counts[it.id] ?: 0 } ?: active.first(), null)
            }
            RoutingStrategy.quotaWindow -> {
                val remaining = syncedQuota(group, nowMs)
                if (remaining > 0) {
                    quotaRemaining[group.id] = remaining - 1
                    Pick(active.first(), null)
                } else {
                    val hit = active.getOrNull(1) ?: active.first()
                    Pick(
                        hit,
                        "group quota exhausted — serving fallback channel until reset",
                    )
                }
            }
            RoutingStrategy.smart -> Pick(
                if (complexRequest) active.first() else active.last(),
                if (complexRequest) {
                    "smart: complex request → primary member"
                } else {
                    "smart: light request → budget member"
                },
            )
        }
    }

    /**
     * Running quota counter with window-reset: first access seeds from the
     * group config; once nowMs passes quotaWindowResetAt the counter refills
     * to quotaWindowTotal. The group config stays authoritative — editing
     * it in settings outlives this cache because a group object rebuilt
     * from config with a changed remaining value only takes effect after a
     * reset or when the process state is dropped. To make an edit take
     * effect immediately the settings screen syncs the counter via
     * [syncQuotaWindow].
     */
    private fun syncedQuota(group: ModelGroup, nowMs: Long): Int {
        val stored = quotaRemaining[group.id]
        if (stored == null) {
            val seed = group.quotaWindowRemaining ?: 0
            quotaRemaining[group.id] = seed
            return seed
        }
        val resetAt = group.quotaWindowResetAt
        if (resetAt != null && group.quotaWindowPeriodMs != null && nowMs >= resetAt) {
            val refill = group.quotaWindowTotal ?: 0
            quotaRemaining[group.id] = refill
            return refill
        }
        return stored
    }

    /** Settings hook: push an edited quota value into the running counter. */
    fun syncQuotaWindow(groupId: String, remaining: Int) {
        quotaRemaining[groupId] = remaining
    }

    /**
     * [T-routing-strategy-x] Park the serving group member after a 429 or a
     * permanent capacity rejection so subsequent turns route around the
     * exhausted channel. The same-provider retry and group fallback still
     * handle the current turn; the cooldown only shapes future picks.
     * Honours Retry-After when the provider reported one.
     */
    fun markServingCooldownFor(groupId: String?, error: LLMError) {
        val serving = groupId?.let { servingEntry(it) } ?: return
        val retryAfterMs = (error as? LLMError.RateLimited)
            ?.retryAfterSeconds
            ?.takeIf { it > 0 }
            ?.let { it * 1_000L }
        markCooldown(serving, System.currentTimeMillis() + (retryAfterMs ?: DEFAULT_COOLDOWN_MS))
    }

    /**
     * [T-routing-strategy-x] Group-level first-token override, or null when
     * the group has none. The group-chat lane passes the result straight
     * into [speakModel] so every speaker shares the same budget.
     */
    fun firstTokenOverrideMs(repository: ProviderRepository, groupId: String?): Long? =
        groupId?.let { repository.group(it)?.firstTokenTimeoutMs?.toLong() }?.takeIf { it > 0 }

    /**
     * [T-routing-strategy-x] First-token budget for a group pick: a
     * group-level firstTokenTimeoutMs override wins, otherwise the
     * per-thinking-level default (90s reasoning / 45s non-reasoning). The
     * main agent loop and the group-chat lane both resolve through here so
     * the two paths always agree.
     */
    fun resolveFirstEventTimeoutMs(
        repository: ProviderRepository,
        groupId: String?,
        reasoningEnabled: Boolean,
    ): Long =
        firstTokenOverrideMs(repository, groupId) ?: if (reasoningEnabled) 90_000L else 45_000L
}
