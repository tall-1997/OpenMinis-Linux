package com.openminis.app.data

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-routing-strategy-x-fix] Pins the cooldown / state-cleanup semantics
 * that closed three routing bugs in one sweep:
 *
 * 1. The smart lane re-derived its complexity signal inline (duplicated
 *    SMART_COMPLEX_CHARS, drifted silently). [GroupRoutingState.isComplexRequest]
 *    is now the single source of truth: long prompt, attached media or a
 *    tool-heavy turn all count as complex.
 * 2. Cooldowns were indexed by "the member that served last" — a bookmark
 *    concurrent turns could overwrite between pick and error, cooling the
 *    wrong member. [GroupRoutingState.markServingCooldownFor] now takes the
 *    entryId explicitly, and the quota/smart lanes pick from the cooled-
 *    down pool so a cooled primary slot no longer force-selects a parked
 *    member.
 * 3. Per-group and per-entry routing state leaked forever: deleted groups
 *    left stale cursors/quota counters, and member removal left stale
 *    cooldown windows that silently warmed a cold start for a new member
 *    reusing the id. [GroupRoutingState.clearGroupState] and
 *    [GroupRoutingState.pruneEntryStates] close both leaks.
 *
 * Mirrored 1:1 by scripts/verify_cooldown_cleanup.py so the semantics can
 * be checked with python3 locally (no JVM needed).
 */
class GroupRoutingStateFixTest {

    private fun entry(id: String) = ModelEntry(
        providerInstanceId = "inst/$id",
        baseModel = LLMModel(
            id = id,
            displayName = id,
            provider = "test",
            contextWindow = 8192,
            maxOutputTokens = 4096,
        ),
        overrides = com.openminis.app.data.model.ModelOverrides(),
        isCustom = false,
        isHidden = false,
        uuid = id,
    )

    private fun group(
        vararg members: String,
        strategy: RoutingStrategy = RoutingStrategy.fallback,
        quotaRemaining: Int? = null,
        quotaTotal: Int? = null,
        quotaPeriodMs: Long? = null,
        quotaResetAt: Long? = null,
    ) = ModelGroup(
        name = "g",
        memberEntryIds = members.toMutableList(),
        strategy = strategy,
        quotaWindowRemaining = quotaRemaining,
        quotaWindowTotal = quotaTotal,
        quotaWindowPeriodMs = quotaPeriodMs,
        quotaWindowResetAt = quotaResetAt,
    )

    private fun members(vararg ids: String) = ids.map { entry(it) }

    private fun pick(
        group: ModelGroup,
        pool: List<ModelEntry>,
        nowMs: Long,
        complex: Boolean = false,
    ): GroupRoutingState.Pick? = GroupRoutingState.pick(
        group = group,
        members = pool,
        preferredEntryId = null,
        sessionKey = "",
        nowMs = nowMs,
        usageCounts = emptyMap(),
        random01 = 0.0,
        complexRequest = complex,
    )

    // -- 1. complex signal: single source of truth ----------------------------

    @Test
    fun `complex signal flips exactly at the character threshold`() {
        assertEquals(false, GroupRoutingState.isComplexRequest(GroupRoutingState.SMART_COMPLEX_CHARS - 1))
        assertEquals(true, GroupRoutingState.isComplexRequest(GroupRoutingState.SMART_COMPLEX_CHARS))
    }

    @Test
    fun `attached media counts as complex regardless of length`() {
        assertEquals(true, GroupRoutingState.isComplexRequest(3, attachmentCount = 1))
    }

    @Test
    fun `a tool-heavy turn counts as complex regardless of length`() {
        assertEquals(true, GroupRoutingState.isComplexRequest(3, toolUseCount = 1))
    }

    @Test
    fun `a short plain-text turn is light`() {
        assertEquals(false, GroupRoutingState.isComplexRequest(3))
    }

    // -- 2. cooldown indexed by the explicit entryId --------------------------

    @Test
    fun `serving cooldown parks exactly the entryId passed in`() {
        GroupRoutingState.resetForTest()
        val g = group("a", "b")
        val pool = members("a", "b")
        // Even though "a" serves first, the error names "b" explicitly —
        // the cooldown must land on "b", not on "the member that served".
        GroupRoutingState.markServingCooldownFor("b", LLMError.RateLimited(retryAfterSeconds = 30))
        assertEquals("a", pick(g, pool, nowMs = System.currentTimeMillis())!!.entry.id)
        GroupRoutingState.resetForTest()
    }

    @Test
    fun `serving cooldown without Retry-After uses the default window`() {
        GroupRoutingState.resetForTest()
        val g = group("a", "b")
        val pool = members("a", "b")
        GroupRoutingState.markServingCooldownFor("a", LLMError.RateLimited())
        assertEquals("b", pick(g, pool, nowMs = System.currentTimeMillis())!!.entry.id)
        GroupRoutingState.resetForTest()
    }

    @Test
    fun `serving cooldown with a null entryId is a no-op`() {
        GroupRoutingState.resetForTest()
        val g = group("a", "b")
        val pool = members("a", "b")
        GroupRoutingState.markServingCooldownFor(null, LLMError.RateLimited(retryAfterSeconds = 30))
        assertEquals("a", pick(g, pool, nowMs = System.currentTimeMillis())!!.entry.id)
        GroupRoutingState.resetForTest()
    }

    // -- 3. cooled-down lanes: quota/smart never force-select a parked member --

    @Test
    fun `quotaWindow with a cooled primary slot serves the active member`() {
        GroupRoutingState.resetForTest()
        val g = group("a", "b", strategy = RoutingStrategy.quotaWindow, quotaRemaining = 1)
        val pool = members("a", "b")
        // "a" is the primary (quota) slot but it is parked: the pick must
        // route around it instead of force-selecting a cooled member.
        GroupRoutingState.markCooldown("a", System.currentTimeMillis() + 60_000L)
        assertEquals("b", pick(g, pool, nowMs = System.currentTimeMillis())!!.entry.id)
        GroupRoutingState.resetForTest()
    }

    @Test
    fun `smart with every member cooled serves the soonest to recover`() {
        GroupRoutingState.resetForTest()
        val g = group("a", "b", strategy = RoutingStrategy.smart)
        val pool = members("a", "b")
        val now = System.currentTimeMillis()
        // "b" recovers sooner (now + 3s) than "a" (now + 9s): availability
        // outranks the budget lane, so "b" serves first.
        GroupRoutingState.markCooldown("a", now + 9_000L)
        GroupRoutingState.markCooldown("b", now + 3_000L)
        assertEquals("b", pick(g, pool, nowMs = now)!!.entry.id)
        GroupRoutingState.resetForTest()
    }

    @Test
    fun `smart with a partially cooled pool still honors the budget lane`() {
        GroupRoutingState.resetForTest()
        val g = group("strong", "cheap", strategy = RoutingStrategy.smart)
        val pool = members("strong", "cheap")
        // Only the budget member is parked: a light request serves the
        // budget lane as designed; the strong member is not force-picked.
        GroupRoutingState.markCooldown("cheap", System.currentTimeMillis() + 60_000L)
        assertEquals("strong", pick(g, pool, nowMs = System.currentTimeMillis())!!.entry.id)
        GroupRoutingState.resetForTest()
    }

    // -- 4. state cleanup: deleted groups and removed members -----------------

    @Test
    fun `clearGroupState resets the round-robin cursor for a recreated group`() {
        GroupRoutingState.resetForTest()
        val g = group("a", "b", strategy = RoutingStrategy.roundRobin)
        val pool = members("a", "b")
        assertEquals("a", pick(g, pool, nowMs = 1_000L)!!.entry.id)
        assertEquals("b", pick(g, pool, nowMs = 1_000L)!!.entry.id)
        // Group deleted + recreated with the same id: the stale cursor must
        // not leak into the new group's rotation.
        GroupRoutingState.clearGroupState(g.id)
        assertEquals("a", pick(g, pool, nowMs = 1_000L)!!.entry.id)
        GroupRoutingState.resetForTest()
    }

    @Test
    fun `pruneEntryStates drops a stale cooldown for a reusing entry id`() {
        GroupRoutingState.resetForTest()
        GroupRoutingState.markCooldown("a", System.currentTimeMillis() + 60_000L)
        // "a" was removed from every group, then a new member reuses the id:
        // the stale cooldown window must not warm a cold start.
        GroupRoutingState.pruneEntryStates(setOf())
        val g = group("a", "b")
        val pool = members("a", "b")
        assertEquals("a", pick(g, pool, nowMs = System.currentTimeMillis())!!.entry.id)
        GroupRoutingState.resetForTest()
    }

    @Test
    fun `pruneEntryStates keeps cooldowns of members that still exist`() {
        GroupRoutingState.resetForTest()
        GroupRoutingState.markCooldown("a", System.currentTimeMillis() + 60_000L)
        GroupRoutingState.pruneEntryStates(setOf("a"))
        val g = group("a", "b")
        val pool = members("a", "b")
        assertEquals("b", pick(g, pool, nowMs = System.currentTimeMillis())!!.entry.id)
        GroupRoutingState.resetForTest()
    }
}
