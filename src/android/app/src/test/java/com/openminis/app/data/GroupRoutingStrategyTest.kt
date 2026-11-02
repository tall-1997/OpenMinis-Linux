package com.openminis.app.data

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [T-routing-strategy-x] Pins the five new routing strategies added beside
 * fallback/loadBalance: roundRobin, weighted, leastUsed, quotaWindow, smart.
 *
 * The pure decision rule lives in `GroupRoutingState.pick` — no ViewModel, no
 * repository, no Room. State that must survive calls (round-robin cursor,
 * cooldowns, quota counters) is carried by the singleton; the per-call math
 * is deterministic under the injected now/random/usage inputs, which is what
 * these tests pin.
 *
 * Back-compat contract under test: with no weights / no quota config / no
 * cooldowns, fallback and loadBalance behave byte-identically to the pre-x
 * implementation (first member / hash of session key).
 */
class GroupRoutingStrategyTest {

    private fun entry(id: String) = ModelEntry(
        providerInstanceId = "inst/$id",
        baseModel = com.openminis.app.data.model.LLMModel(
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
        weights: Map<String, Int> = emptyMap(),
        quotaRemaining: Int? = null,
        quotaTotal: Int? = null,
        quotaPeriodMs: Long? = null,
        quotaResetAt: Long? = null,
    ) = ModelGroup(
        name = "g",
        memberEntryIds = members.toMutableList(),
        strategy = strategy,
        memberWeights = weights,
        quotaWindowRemaining = quotaRemaining,
        quotaWindowTotal = quotaTotal,
        quotaWindowPeriodMs = quotaPeriodMs,
        quotaWindowResetAt = quotaResetAt,
    )

    private fun members(vararg ids: String) = ids.map { entry(it) }

    private fun pick(
        group: ModelGroup,
        pool: List<ModelEntry>,
        preferred: String? = null,
        sessionKey: String = "",
        nowMs: Long = 1_000L,
        usage: Map<String, Int> = emptyMap(),
        random01: Double = 0.0,
        complex: Boolean = false,
    ): GroupRoutingState.Pick? = GroupRoutingState.pick(
        group = group,
        members = pool,
        preferredEntryId = preferred,
        sessionKey = sessionKey,
        nowMs = nowMs,
        usageCounts = usage,
        random01 = random01,
        complexRequest = complex,
    )

    // -- Back-compat: fallback / loadBalance unchanged ----------------------

    @Test
    fun `fallback without config picks first member exactly as before`() {
        val g = group("a", "b", "c")
        assertEquals("a", pick(g, members("a", "b", "c"))!!.entry.id)
    }

    @Test
    fun `loadBalance without config hashes session key exactly as before`() {
        val g = group("a", "b", "c", strategy = RoutingStrategy.loadBalance)
        val pool = members("a", "b", "c")
        val key = "session-42"
        val expected = pool[Math.floorMod(key.hashCode(), pool.size)].id
        assertEquals(expected, pick(g, pool, sessionKey = key)!!.entry.id)
    }

    @Test
    fun `preferred entry is honored for fallback and loadBalance as before`() {
        val g = group("a", "b", "c")
        assertEquals("b", pick(g, members("a", "b", "c"), preferred = "b")!!.entry.id)
        val lb = group("a", "b", "c", strategy = RoutingStrategy.loadBalance)
        assertEquals("c", pick(lb, members("a", "b", "c"), preferred = "c", sessionKey = "s")!!.entry.id)
    }

    // -- roundRobin ----------------------------------------------------------

    @Test
    fun `roundRobin advances one member per call and wraps around`() {
        val g = group("a", "b", "c", strategy = RoutingStrategy.roundRobin)
        val pool = members("a", "b", "c")
        assertEquals("a", pick(g, pool)!!.entry.id)
        assertEquals("b", pick(g, pool)!!.entry.id)
        assertEquals("c", pick(g, pool)!!.entry.id)
        assertEquals("a", pick(g, pool)!!.entry.id)
    }

    @Test
    fun `roundRobin skips a cooled-down member`() {
        GroupRoutingState.resetForTest()
        GroupRoutingState.markCooldown("b", 5_000L)
        val g = group("a", "b", "c", strategy = RoutingStrategy.roundRobin)
        val pool = members("a", "b", "c")
        assertEquals("a", pick(g, pool, nowMs = 1_000L)!!.entry.id)
        assertEquals("c", pick(g, pool, nowMs = 1_000L)!!.entry.id)
        assertEquals("a", pick(g, pool, nowMs = 1_000L)!!.entry.id)
        GroupRoutingState.resetForTest()
    }

    @Test
    fun `roundRobin ignores preferred entry so rotation is not defeated`() {
        val g = group("a", "b", strategy = RoutingStrategy.roundRobin)
        val pool = members("a", "b")
        assertEquals("a", pick(g, pool, preferred = "b")!!.entry.id)
        assertEquals("b", pick(g, pool, preferred = "b")!!.entry.id)
    }

    // -- weighted ------------------------------------------------------------

    @Test
    fun `weighted uses roulette wheel over declared weights`() {
        val g = group("a", "b", strategy = RoutingStrategy.weighted, weights = mapOf("a" to 3, "b" to 1))
        val pool = members("a", "b")
        // total = 4: [0, 0.75) -> a, [0.75, 1) -> b
        assertEquals("a", pick(g, pool, random01 = 0.5)!!.entry.id)
        assertEquals("b", pick(g, pool, random01 = 0.8)!!.entry.id)
        assertEquals("a", pick(g, pool, random01 = 0.0)!!.entry.id)
        assertEquals("b", pick(g, pool, random01 = 0.99)!!.entry.id)
    }

    @Test
    fun `weighted members without a declared weight default to 1`() {
        val g = group("a", "b", strategy = RoutingStrategy.weighted, weights = mapOf("a" to 3))
        val pool = members("a", "b")
        // total = 4, same wheel as above
        assertEquals("a", pick(g, pool, random01 = 0.5)!!.entry.id)
        assertEquals("b", pick(g, pool, random01 = 0.8)!!.entry.id)
    }

    @Test
    fun `weighted with no weights at all degrades to uniform`() {
        val g = group("a", "b", strategy = RoutingStrategy.weighted)
        val pool = members("a", "b")
        assertEquals("a", pick(g, pool, random01 = 0.4)!!.entry.id)
        assertEquals("b", pick(g, pool, random01 = 0.6)!!.entry.id)
    }

    // -- leastUsed -----------------------------------------------------------

    @Test
    fun `leastUsed picks the member with the smallest usage count`() {
        val g = group("a", "b", "c", strategy = RoutingStrategy.leastUsed)
        val pool = members("a", "b", "c")
        assertEquals("b", pick(g, pool, usage = mapOf("a" to 5, "b" to 2, "c" to 9))!!.entry.id)
    }

    @Test
    fun `leastUsed treats members without usage records as zero`() {
        val g = group("a", "b", strategy = RoutingStrategy.leastUsed)
        val pool = members("a", "b")
        // a has 3 uses, b has no record -> 0 -> b wins
        assertEquals("b", pick(g, pool, usage = mapOf("a" to 3))!!.entry.id)
    }

    @Test
    fun `leastUsed ties resolve to declaration order`() {
        val g = group("a", "b", "c", strategy = RoutingStrategy.leastUsed)
        val pool = members("a", "b", "c")
        assertEquals("a", pick(g, pool, usage = mapOf("a" to 1, "b" to 1, "c" to 1))!!.entry.id)
    }

    // -- quotaWindow ---------------------------------------------------------

    @Test
    fun `quotaWindow serves normally while quota remains and pre-deducts`() {
        GroupRoutingState.resetForTest()
        val g = group(
            "a", "b", strategy = RoutingStrategy.quotaWindow,
            quotaRemaining = 2, quotaTotal = 2,
            quotaPeriodMs = 60_000L, quotaResetAt = 61_000L,
        )
        val pool = members("a", "b")
        val first = pick(g, pool, nowMs = 1_000L)!!
        assertEquals("a", first.entry.id)
        val second = pick(g, pool, nowMs = 1_000L)!!
        assertEquals("a", second.entry.id)
        // Remaining is now 0: the window skips the primary channel and serves
        // the next member in declaration order instead of stalling the group.
        val third = pick(g, pool, nowMs = 1_000L)!!
        assertEquals("b", third.entry.id)
        GroupRoutingState.resetForTest()
    }

    @Test
    fun `quotaWindow resets when the window period has elapsed`() {
        GroupRoutingState.resetForTest()
        val g = group(
            "a", "b", strategy = RoutingStrategy.quotaWindow,
            quotaRemaining = 0, quotaTotal = 2,
            quotaPeriodMs = 60_000L, quotaResetAt = 60_000L,
        )
        val pool = members("a", "b")
        // Before reset: primary is skipped.
        assertEquals("b", pick(g, pool, nowMs = 59_000L)!!.entry.id)
        // After reset: counter refills and primary serves again.
        assertEquals("a", pick(g, pool, nowMs = 61_000L)!!.entry.id)
        GroupRoutingState.resetForTest()
    }

    // -- smart ---------------------------------------------------------------

    @Test
    fun `smart routes light requests to the last member`() {
        val g = group("strong", "cheap", strategy = RoutingStrategy.smart)
        assertEquals("cheap", pick(g, members("strong", "cheap"), complex = false)!!.entry.id)
    }

    @Test
    fun `smart routes complex requests to the first member`() {
        val g = group("strong", "cheap", strategy = RoutingStrategy.smart)
        assertEquals("strong", pick(g, members("strong", "cheap"), complex = true)!!.entry.id)
    }

    // -- cooldown common path ------------------------------------------------

    @Test
    fun `cooldown filters the pool for every strategy`() {
        GroupRoutingState.resetForTest()
        GroupRoutingState.markCooldown("a", 5_000L)
        val g = group("a", "b", strategy = RoutingStrategy.fallback)
        val pool = members("a", "b")
        assertEquals("b", pick(g, pool, nowMs = 1_000L)!!.entry.id)
        // After the cooldown expires the member serves again.
        assertEquals("a", pick(g, pool, nowMs = 6_000L)!!.entry.id)
        GroupRoutingState.resetForTest()
    }

    @Test
    fun `fully cooled-down pool falls back to the original members`() {
        GroupRoutingState.resetForTest()
        GroupRoutingState.markCooldown("a", 5_000L)
        GroupRoutingState.markCooldown("b", 5_000L)
        val g = group("a", "b", strategy = RoutingStrategy.fallback)
        val pool = members("a", "b")
        val hit = pick(g, pool, nowMs = 1_000L)
        assertNotNull(hit)
        GroupRoutingState.resetForTest()
    }
}
