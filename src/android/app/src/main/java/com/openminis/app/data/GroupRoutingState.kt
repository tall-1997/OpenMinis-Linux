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

    /**
     * [T-routing-strategy-x-fix] Single source of truth for the smart
     * lane's complexity signal. A long prompt, attached media or a
     * tool-heavy turn all count as complex; the UI must not re-derive this
     * with its own inline threshold (that duplicated [SMART_COMPLEX_CHARS]
     * once and silently drifted).
     */
    fun isComplexRequest(
        messageChars: Int,
        attachmentCount: Int = 0,
        toolUseCount: Int = 0,
    ): Boolean = messageChars >= SMART_COMPLEX_CHARS || attachmentCount > 0 || toolUseCount > 0

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
        servingInstance.clear()
    }

    /**
     * [T-routing-strategy-x-fix] Drop every per-group routing state for a
     * deleted group (round-robin cursor, quota counter, serving-instance
     * bookmark). Without this the maps leaked stale entries forever and a
     * later group reusing the id could inherit a dead cursor or quota.
     */
    fun clearGroupState(groupId: String) {
        roundRobinCursors.remove(groupId)
        quotaRemaining.remove(groupId)
        servingInstance.remove(groupId)
    }

    /**
     * [T-routing-strategy-x-fix] Trim per-entry state to the entries that
     * still exist in some group. Cooldown windows and least-used counters
     * keyed by entryId otherwise survive member removal (and even group
     * deletion), silently warming a stale cold-start for a new member that
     * reuses the id.
     */
    fun pruneEntryStates(activeEntryIds: Set<String>) {
        cooldownUntil.keys.retainAll(activeEntryIds)
        servingCounts.keys.retainAll(activeEntryIds)
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

    /** Record that [entryId] served a pick (leastUsed signal). */
    fun noteServed(entryId: String) {
        servingCounts[entryId] = (servingCounts[entryId] ?: 0) + 1
    }

    /**
     * [T-credential-pool-x] Record which provider INSTANCE serves the group
     * right now — the credential-cooldown analogue of [noteServed].  The
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
        val instanceId = groupId?.let { servingInstance[it] } ?: return
        repository.markCredentialCooled(instanceId, error)
    }

    /**
     * [T-routing-strategy-x-fix] Entry + credential cooldown in one step —
     * the single call path from the agent loop's 429/容量 error branch.
     * [entryId] is the member that actually produced the error and is
     * passed in explicitly: the previous design re-derived it from a
     * "serving entry" bookmark, which concurrent turns could overwrite
     * between the pick and the error — cooling the wrong member.
     */
    fun markRoutingCooldownFor(
        entryId: String?,
        groupId: String?,
        error: LLMError,
        repository: ProviderRepository,
    ) {
        markServingCooldownFor(entryId, error)
        markCredentialCooldownFor(groupId, error, repository)
    }

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
        val active = cooledActive(members, nowMs)
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
            RoutingStrategy.smart -> {
                // All-cooled pool degrades (see cooledActive): availability
                // outranks the budget lane, so the soonest-to-recover member
                // serves rather than the budget slot (possibly the latest
                // to recover).
                val allCooled = active.all { (cooldownUntil[it.id] ?: 0L) > nowMs }
                Pick(
                    if (complexRequest || allCooled) active.first() else active.last(),
                    when {
                        allCooled -> "smart: all members cooling — soonest to recover"
                        complexRequest -> "smart: complex request → primary member"
                        else -> "smart: light request → budget member"
                    },
                )
            }
        }
    }

    /**
     * [T-routing-strategy-x-fix] Ordered fail-over chain for capability
     * lanes (vision / compact fallback / voice input). Unlike [pick], which
     * decides one serving member per call, this produces the whole ordered
     * candidate list a fail-over caller walks. State effects keep [pick]'s
     * contract: the quota window is pre-deducted once per chain build (a
     * failed send is not refunded), the round-robin cursor advances once,
     * cooldowns are honored by every strategy and an all-cooled pool
     * degrades back to the original order. Every strategy returns the full
     * reordered pool — callers can keep failing over to the end.
     */
    fun pickChain(
        group: ModelGroup,
        members: List<ModelEntry>,
        sessionKey: String,
        nowMs: Long = System.currentTimeMillis(),
        usageCounts: Map<String, Int>? = null,
        complexRequest: Boolean = false,
    ): List<ModelEntry> {
        if (members.isEmpty()) return emptyList()
        val active = cooledActive(members, nowMs)
        return when (group.strategy) {
            RoutingStrategy.fallback -> active
            RoutingStrategy.loadBalance -> {
                val offset = Math.floorMod(sessionKey.hashCode(), active.size)
                active.drop(offset) + active.take(offset)
            }
            RoutingStrategy.roundRobin -> {
                val cursor = roundRobinCursors.getOrDefault(group.id, 0)
                roundRobinCursors[group.id] = cursor + 1
                val offset = Math.floorMod(cursor, active.size)
                active.drop(offset) + active.take(offset)
            }
            RoutingStrategy.weighted -> {
                val weights = active.map { m -> (group.memberWeights[m.id] ?: 1).coerceAtLeast(1) }
                // Descending weights with stable tie-break: the heavy member
                // is tried first, equal weights keep the original order.
                active.mapIndexed { i, m -> Triple(i, m, weights[i]) }
                    .sortedWith(
                        compareByDescending<Triple<Int, ModelEntry, Int>> { it.third }
                            .thenBy { it.first },
                    )
                    .map { it.second }
            }
            RoutingStrategy.leastUsed -> {
                val counts = usageCounts ?: servingCounts
                active.sortedBy { counts[it.id] ?: 0 }
            }
            RoutingStrategy.quotaWindow -> {
                val remaining = syncedQuota(group, nowMs)
                if (remaining > 0) {
                    quotaRemaining[group.id] = remaining - 1
                    active
                } else {
                    // Backup leads the fail-over; the primary is the tail of
                    // last resort (mirrors pick's getOrNull(1) semantics).
                    active.drop(1) + active.take(1)
                }
            }
            RoutingStrategy.smart -> {
                val allCooled = active.all { (cooldownUntil[it.id] ?: 0L) > nowMs }
                if (complexRequest || allCooled) active else active.reversed()
            }
        }
    }

    /** Cooldown gate shared by [pick] and [pickChain]. */
    private fun cooledActive(members: List<ModelEntry>, nowMs: Long): List<ModelEntry> {
        val usable = members.filter { (cooldownUntil[it.id] ?: 0L) <= nowMs }
        if (usable.isNotEmpty()) return usable
        // All-cooled pool degrades to soonest-to-recover order instead of
        // stalling — quotaWindow's primary slot and smart's strong slot stay
        // pointed at the member that comes back first, so a cooled member is
        // never chosen merely because its original slot happened to be first.
        return members.sortedBy { cooldownUntil[it.id] ?: 0L }
    }

    /**
     * Running quota counter with window-reset: first access seeds from the
     * persisted snapshot, refills when the window passes, and stays cached
     * in between. The group config; once nowMs passes quotaWindowResetAt
     * the counter refills to quotaWindowTotal. The group config stays
     * authoritative — editing it in settings outlives this cache because a
     * group object rebuilt from config with a changed remaining value only
     * takes effect after a reset or when the process state is dropped. To
     * make an edit take effect immediately the settings screen syncs the
     * counter via [syncQuotaWindow].
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
     * [T-routing-strategy-x-fix] Live remaining-sends view for the settings
     * UI (quota-window editor). Seeds lazily from the persisted snapshot the
     * same way [pick] does, and refills on a passed reset — the editor shows
     * the same number the router will actually serve next.
     */
    fun currentQuotaRemaining(group: ModelGroup): Int =
        syncedQuota(group, System.currentTimeMillis())

    /**
     * [T-routing-strategy-x] Park the serving group member after a 429 or a
     * permanent capacity rejection so subsequent turns route around the
     * exhausted channel. The same-provider retry and group fallback still
     * handle the current turn; the cooldown only shapes future picks.
     * Honours Retry-After when the provider reported one.
     */
    fun markServingCooldownFor(entryId: String?, error: LLMError) {
        if (entryId == null) return
        val retryAfterMs = (error as? LLMError.RateLimited)
            ?.retryAfterSeconds
            ?.takeIf { it > 0 }
            ?.let { it * 1_000L }
        markCooldown(entryId, System.currentTimeMillis() + (retryAfterMs ?: DEFAULT_COOLDOWN_MS))
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
