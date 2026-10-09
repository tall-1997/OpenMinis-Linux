package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-zen-free-thinking-budget] End-to-end tests for the Zen free lane
 * thinking budget. Uses the real `buildRequestBody` → sendMessageClamped
 * path through MockWebServer, the same way ThinkingRulesRegressionTest
 * exercises `injectThinkingParams`.
 *
 * CONTRACT (from the desktop reference implementation, opencode2dsh):
 * the Zen free lane's gateway ignores every effort field and enforces
 * ONLY max_completion_tokens. So a thinking level maps to a real
 * generation budget that replaces the caller's maxTokens. A model whose
 * thinking cannot be switched off has its rungs doubled.
 *
 * REGRESSION: a user's paid Zen provider (apiKey != "public") must NOT
 * receive this budget treatment — it keeps the normal reasoning_effort
 * path through injectThinkingParams.
 *
 * [T-thinking-ladder-shared] The ladder is no longer seven absolute constants
 * in OpenAIRequestBodies; it is [ThinkingLadder], fractions of the model's own
 * output capacity. The expectations below are therefore a function of
 * `maxOutputTokens`, not fixed literals — and on the four REAL Zen free models
 * whose capacity sits near the old top rungs (big-pickle / mimo-v2.5 /
 * mimo-v2.6 at 32000, ling-3.1 at 32768) the old table sent byte-identical
 * numbers for XHIGH, MAX and ULTRA. Those four are pinned explicitly below:
 * they are the regression this change exists to remove.
 */
class ZenFreeThinkingBudgetTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun model(
        id: String,
        supportsReasoning: Boolean? = true,
        maxOutputTokens: Int? = 32768,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = "OpenCode Zen",
        contextWindow = 128000,
        maxOutputTokens = maxOutputTokens,
        supportsReasoning = supportsReasoning,
    )

    private fun plainHistory(): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "hi"),
    )

    /**
     * Drive the real provider on the Zen free path and return the serialized
     * outbound body. `apiKey = "public"` + the forced Zen host trigger
     * `isZenFree` on the production OpenAIProvider, so `buildRequestBody`
     * takes the budget branch instead of `injectThinkingParams`.
     */
    private fun capture(
        model: LLMModel,
        level: ThinkingLevel,
        maxTokens: Int = 4096,
    ): JSONObject {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(ok),
            )
        }
        // [T-zen-host-anchor] isZenHost anchors on the URL HOST, and a
        // MockWebServer base path can never carry it, so the Zen host decision
        // is forced on the provider instead of the old path-substring trick.
        val basePath = server.url("/v1").toString().trimEnd('/')
        val provider = OpenAIProvider(apiKey = "public", model = model, basePath = basePath)
        provider.zenHostOverride = true
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = plainHistory(),
                    systemPrompt = null,
                    maxTokens = maxTokens,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = level,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    // -- non-reasoning model: budget path not taken

    @Test
    fun `non-reasoning model keeps caller maxTokens`() {
        val body = capture(
            model = model("big-pickle", supportsReasoning = false),
            level = ThinkingLevel.MEDIUM,
            maxTokens = 4096,
        )
        // isZenFree is true, but supportsReasoning is false →
        // zenBudgetTokens returns null → body keeps the original 4096
        assertEquals(4096, body.optInt("max_completion_tokens"))
        // Normal reasoning_effort MUST NOT appear on the free-lane body
        assertEquals(false, body.has("reasoning_effort"))
    }

    /**
     * Capture only the numeric `max_completion_tokens` the free lane puts on
     * the wire for one (model, level) pair, so the ladder tests read as a
     * table instead of eight copies of the MockWebServer boilerplate.
     */
    private fun budgetFor(
        id: String = "big-pickle",
        level: ThinkingLevel,
        maxOutputTokens: Int? = 131072,
    ): Int = capture(model(id, maxOutputTokens = maxOutputTokens), level, 4096)
        .optInt("max_completion_tokens")

    /** Every rung, ascending — the order the picker offers them in. */
    private val ascendingRungs = listOf(
        ThinkingLevel.OFF,
        ThinkingLevel.LOW,
        ThinkingLevel.MEDIUM,
        ThinkingLevel.HIGH,
        ThinkingLevel.XHIGH,
        ThinkingLevel.MAX,
        ThinkingLevel.ULTRA,
    )

    // -- reasoning model: budget replaces caller maxTokens

    @Test
    fun `off is the smallest rung and leaves room for the answer`() {
        // REWRITTEN [T-thinking-ladder-shared]: 1024 was a hardcoded constant,
        // so on a 32768-capacity model it was 1/32 of the ceiling — pure
        // luck. OFF is now capacity/32 by construction: 1024 on a 32768
        // model, 1000 on a 32000 one. What is asserted here is the RELATION,
        // because that is the part that must hold everywhere.
        val off = budgetFor(level = ThinkingLevel.OFF, maxOutputTokens = 32768)
        val low = budgetFor(level = ThinkingLevel.LOW, maxOutputTokens = 32768)
        assertEquals(1024, off)
        assertTrue("OFF ($off) must be below LOW ($low)", off < low)
    }

    @Test
    fun `low sits one step above off`() {
        // REWRITTEN: the raw 2048 survives only on a 32768-capacity model,
        // where it now happens to be exactly capacity/16. Previously LOW
        // equalled OFF, so picking LOW over OFF changed nothing on the wire;
        // the ladder makes the two distinct on every capacity >= 16384.
        val low = budgetFor(level = ThinkingLevel.LOW, maxOutputTokens = 32768)
        val off = budgetFor(level = ThinkingLevel.OFF, maxOutputTokens = 32768)
        assertEquals(2048, low)
        assertTrue("LOW ($low) must exceed OFF ($off)", low > off)
    }

    @Test
    fun `medium is a quarter-strength rung, not an absolute 8192`() {
        // REWRITTEN [T-thinking-ladder-shared]: 8192 was constant, so on a
        // 32000-capacity model it was 26% of the ceiling while on a 524288
        // one it was 1.6% — the SAME menu entry meant wildly different things
        // per model. MEDIUM is now capacity/8 everywhere: 4096 at 32768.
        val body = capture(model("big-pickle"), ThinkingLevel.MEDIUM, 4096)
        assertEquals(4096, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `high is a quarter of capacity`() {
        // REWRITTEN: 16384 was an absolute rung. On a 32768-capacity model it
        // was HALF the ceiling and on a 16384 one it was clipped away
        // entirely. HIGH is now capacity/4: 8192 at 32768.
        val body = capture(model("big-pickle"), ThinkingLevel.HIGH, 4096)
        assertEquals(8192, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `xhigh is 7-16ths of capacity and strictly above high`() {
        // REWRITTEN [T-thinking-ladder-shared]: XHIGH used to be 32768 or, if
        // that exceeded capacity, the model's FULL capacity — identical to
        // MAX and ULTRA, so the rung did nothing at all. It is now 7/16 of
        // the ceiling, which is below capacity on every model we ship.
        val xhigh = budgetFor(level = ThinkingLevel.XHIGH, maxOutputTokens = 65536)
        val high = budgetFor(level = ThinkingLevel.HIGH, maxOutputTokens = 65536)
        assertEquals(28672, xhigh)
        assertTrue("XHIGH ($xhigh) must exceed HIGH ($high)", xhigh > high)
    }

    @Test
    fun `max is 11-16ths of capacity, strictly between xhigh and ultra`() {
        // REWRITTEN: MAX emitted the full 65536 capacity, the same number as
        // XHIGH and ULTRA. It is now 11/16 of the ceiling (45056 at 65536) —
        // the widest-but-one rung, still strictly under the ceiling.
        val max = budgetFor(level = ThinkingLevel.MAX, maxOutputTokens = 65536)
        val xhigh = budgetFor(level = ThinkingLevel.XHIGH, maxOutputTokens = 65536)
        val ultra = budgetFor(level = ThinkingLevel.ULTRA, maxOutputTokens = 65536)
        assertEquals(45056, max)
        assertTrue("MAX ($max) must exceed XHIGH ($xhigh)", max > xhigh)
        assertTrue("MAX ($max) must stay below ULTRA ($ultra)", max < ultra)
    }

    @Test
    fun `ultra approaches capacity without reaching it`() {
        // REWRITTEN [T-thinking-ladder-shared]: ULTRA was the bare capacity,
        // a rung indistinguishable from MAX by construction. It is now 15/16
        // of the ceiling (122880 on a 131072 model), so the top rung is a
        // real step and never simply IS the capacity.
        val ultra = budgetFor(level = ThinkingLevel.ULTRA, maxOutputTokens = 131072)
        val max = budgetFor(level = ThinkingLevel.MAX, maxOutputTokens = 131072)
        assertEquals(122880, ultra)
        assertTrue("ULTRA ($ultra) must exceed MAX ($max)", ultra > max)
        assertTrue("ULTRA ($ultra) must stay below capacity (131072)", ultra < 131072)
    }

    // -- general invariant: the whole ladder is strictly increasing

    @Test
    fun `ladder is strictly increasing across all seven levels`() {
        // [T-zen-free-thinking-budget-ladder] The invariant the per-rung
        // tests above each check one slice of. Capacity 131072 is large
        // enough that no rung clips, so any collapse anywhere in the table
        // shows up as a repeated number here.
        val budgets = ascendingRungs.map { level ->
            level to budgetFor(level = level, maxOutputTokens = 131072)
        }
        val rendered = budgets.joinToString(" < ") { (lvl, v) -> "$lvl=$v" }
        budgets.zipWithNext().forEach { (lower, higher) ->
            assertTrue(
                "ladder must strictly increase: ${lower.first}=${lower.second} " +
                    "then ${higher.first}=${higher.second} (full: $rendered)",
                higher.second > lower.second,
            )
        }
        // And nothing may reach what the model declared it can emit — with a
        // proportional ladder this holds everywhere, which is what makes the
        // ceilings below (the old top rungs) impossible to hit again.
        budgets.forEach { (lvl, v) ->
            assertTrue("$lvl=$v must stay strictly below capacity 131072", v < 131072)
        }
    }

    /**
     * [T-zen-free-thinking-budget-ladder] THE REGRESSION THIS CHANGE EXISTS
     * FOR. big-pickle, mimo-v2.5, mimo-v2.6 (capacity 32000) and ling-3.1
     * (32768) are real models in the device's own Zen free catalogue, and
     * under the old absolute table XHIGH/MAX/ULTRA all clipped to the same
     * number on every one of them — three of the four top menu entries sent
     * byte-identical requests.
     */
    @Test
    fun `real zen free catalogue models keep every top rung distinct`() {
        for (id in listOf("big-pickle", "mimo-v2.5-free", "mimo-v2.6-flash-free")) {
            for (capacity in listOf(32000, 32768)) {
                val budgets = ascendingRungs.map { level ->
                    budgetFor(id = id, level = level, maxOutputTokens = capacity)
                }
                assertEquals(
                    "$id at capacity $capacity collapsed rungs: $budgets",
                    ascendingRungs.size,
                    budgets.toSet().size,
                )
                budgets.zipWithNext().forEach { (a, b) ->
                    assertTrue(
                        "$id at capacity $capacity must strictly increase: $a then $b (all: $budgets)",
                        b > a,
                    )
                }
            }
        }
    }

    /**
     * [T-zen-free-thinking-budget-ladder] The concrete numbers the picker now
     * shows, so a later edit to the shared ladder cannot silently change what
     * a Zen user gets at these two capacities.
     */
    @Test
    fun `zen free ladder is proportional to capacity`() {
        assertEquals(
            listOf(1024, 2048, 4096, 8192, 14336, 22528, 30720),
            ascendingRungs.map { budgetFor(id = "ling-3.1-flash-free", level = it, maxOutputTokens = 32768) },
        )
        assertEquals(
            listOf(1000, 2000, 4000, 8000, 14000, 22000, 30000),
            ascendingRungs.map { budgetFor(id = "big-pickle", level = it, maxOutputTokens = 32000) },
        )
    }

    @Test
    fun `must-think ladder stays strictly increasing after doubling`() {
        // [T-thinking-ladder-shared] The must-think transform divides the
        // ladder's FRACTIONS rather than multiplying the resulting budgets:
        // `budget * 2` would push the top rungs past the ceiling, where
        // `minOf(..., capacity)` clamps them back to one number — the exact
        // collapse this change removes, landing on exactly the mimo models the
        // transform exists for. Asserted at the real mimo capacity (32768),
        // where the old `budget * 2` DID clip XHIGH/MAX/ULTRA together.
        val budgets = ascendingRungs.map { level ->
            level to budgetFor(id = "mimo-v2.6-flash-free", level = level, maxOutputTokens = 32768)
        }
        val rendered = budgets.joinToString(" < ") { (lvl, v) -> "$lvl=$v" }
        budgets.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "must-think ladder must strictly increase: ${a.first}=${a.second} then " +
                    "${b.first}=${b.second} (full: $rendered)",
                b.second > a.second,
            )
        }
        budgets.forEach { (lvl, v) ->
            assertTrue("$lvl=$v must stay below capacity 32768", v < 32768)
        }
    }

    // -- must-think model (mimo-v2.6): ladder rescaled down by one step

    /**
     * [T-thinking-ladder-shared] `multiplier = 2` DIVIDES the fractions
     * (1/64 .. 30/64) instead of doubling the budgets (1/32 .. 60/32, which
     * clips). On a 32768-capacity model the whole must-think ladder is
     * therefore 512 / 1024 / 2048 / 4096 / 7168 / 11264 / 15360 — still seven
     * distinct numbers, all strictly under the ceiling.
     */
    @Test
    fun `mimo-v2p6 ladder is the rescaled proportional ladder`() {
        val actual = ascendingRungs.map {
            budgetFor(id = "mimo-v2.6-flash-free", level = it, maxOutputTokens = 32768)
        }
        assertEquals(
            listOf(512, 1024, 2048, 4096, 7168, 11264, 15360),
            actual,
        )
    }

    @Test
    fun `mimo-v2p6 off → 512`() {
        // REWRITTEN [T-thinking-ladder-shared]: the old expectation of 2048
        // came from OFF's absolute 1024 base ×2, a constant that happened to
        // be 1/32 of this model's ceiling and 1/64 of the 65536 one. OFF is
        // now the shared ladder's floor on this capacity, doubled fractions
        // included. What matters and is asserted below: it is still the
        // SMALLEST rung, and clearly below MEDIUM's.
        val off = budgetFor(id = "mimo-v2.6-flash-free", level = ThinkingLevel.OFF, maxOutputTokens = 32768)
        val medium = budgetFor(id = "mimo-v2.6-flash-free", level = ThinkingLevel.MEDIUM, maxOutputTokens = 32768)
        assertEquals(512, off)
        assertTrue("OFF ($off) must stay well below MEDIUM ($medium)", off < medium)
    }

    @Test
    fun `mimo-v2p6 medium → 2048`() {
        // REWRITTEN: was 16384 (absolute 8192 ×2), i.e. HALF the ceiling at
        // MEDIUM — barely a rung. Now capacity/16 = 2048, with room above it.
        val body = capture(
            model("mimo-v2.6-flash-free", maxOutputTokens = 32768),
            ThinkingLevel.MEDIUM,
            4096,
        )
        assertEquals(2048, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `mimo-v2p6 high stays under capacity instead of clipping to it`() {
        // REWRITTEN [T-thinking-ladder-shared]: the old expectation was
        // 32768 — the model's FULL capacity, reached at HIGH and then
        // repeated at XHIGH, MAX and ULTRA. Four menu entries, one number.
        // HIGH is now capacity/8 = 4096, and the top rung is 15360.
        val body = capture(
            model("mimo-v2.6-flash-free", maxOutputTokens = 32768),
            ThinkingLevel.HIGH,
            4096,
        )
        assertEquals(4096, body.optInt("max_completion_tokens"))
    }

    // -- must-think model (mimo-v2.5): same ladder as mimo-v2.6

    @Test
    fun `mimo-v2p5 off → 512`() {
        // REWRITTEN: same reason as the mimo-v2.6 OFF test above — the old
        // 2048/4096 came from an absolute base. Both mimo models are must-
        // think, so they now share one ladder off one capacity.
        val off = budgetFor(id = "mimo-v2.5-free", level = ThinkingLevel.OFF, maxOutputTokens = 32768)
        val medium = budgetFor(id = "mimo-v2.5-free", level = ThinkingLevel.MEDIUM, maxOutputTokens = 32768)
        assertEquals(512, off)
        assertTrue("OFF ($off) must stay well below MEDIUM ($medium)", off < medium)
    }

    @Test
    fun `mimo-v2p5 medium → 2048`() {
        val body = capture(
            model("mimo-v2.5-free", maxOutputTokens = 32768),
            ThinkingLevel.MEDIUM,
            4096,
        )
        assertEquals(2048, body.optInt("max_completion_tokens"))
    }

    // -- budget clipped to model capacity

    @Test
    fun `budget clipped to low model capacity`() {
        // REWRITTEN [T-thinking-ladder-shared]: the old 1500 was
        // `minOf(ceiling, capacity)` — the budget EQUALLED the ceiling, which
        // means the model has no room to emit the answer at all. The shared
        // ladder emits 512 (its floor) instead: strictly below capacity and
        // honest about the fact that a 1500-token ceiling cannot host a real
        // reasoning ladder. ULTRA still widens with capacity.
        val medium = capture(
            model("big-pickle", maxOutputTokens = 1500),
            ThinkingLevel.MEDIUM,
            8192,
        ).optInt("max_completion_tokens")
        val ultra = capture(
            model("big-pickle", maxOutputTokens = 1500),
            ThinkingLevel.ULTRA,
            8192,
        ).optInt("max_completion_tokens")
        assertEquals(512, medium)
        assertTrue(
            "a 1500-capacity model must still widen toward the ceiling: ULTRA=$ultra",
            ultra > medium && ultra < 1500,
        )
    }

    // -- minimum floor

    @Test
    fun `never emits zero on a tiny capacity`() {
        // REWRITTEN [T-thinking-ladder-shared]: the old expectation of 512
        // came from `maxOf(512, widened)`, which emitted a budget LARGER than
        // the model's own capacity — a 200-token model was asked for 512
        // tokens. The shared ladder degrades to `min(FLOOR, capacity - 1)`, so
        // the lane never asks for more than the model can produce and never
        // asks for 0 (which Gemini/DashScope reject outright).
        val off = capture(
            model("big-pickle", maxOutputTokens = 200),
            ThinkingLevel.OFF,
            200,
        ).optInt("max_completion_tokens")
        assertEquals(199, off)
        assertTrue("a 200-capacity model must still be under its ceiling", off < 200)
        // A capacity that CAN host the floor gets the floor, not capacity-1.
        assertEquals(
            512,
            capture(model("big-pickle", maxOutputTokens = 3000), ThinkingLevel.OFF, 3000)
                .optInt("max_completion_tokens"),
        )
    }

    // -- regression: paid Zen provider must NOT take the budget path

    @Test
    fun `paid provider keeps reasoning_effort path`() {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok)) }
        // [T-zen-host-anchor] Zen host forced + a real (non-"public") key:
        // isZenFree stays false because the key alone disqualifies it — the
        // exact paid-Zen-provider regression under test.
        val basePath = server.url("/v1").toString().trimEnd('/')
        val provider = OpenAIProvider(apiKey = "sk-real-key", model = model("big-pickle"), basePath = basePath)
        provider.zenHostOverride = true
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = plainHistory(),
                    systemPrompt = null,
                    maxTokens = 4096,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = ThinkingLevel.MEDIUM,
                )
            }
        }
        val body = JSONObject(server.takeRequest().body.readUtf8())
        // Paid provider: the caller's maxTokens stays unchanged
        assertEquals(4096, body.optInt("max_completion_tokens"))
        // And reasoning_effort IS present (normal ThinkingRuleResolver path)
        assertEquals("medium", body.optString("reasoning_effort"))
    }

    // -- regression: non-Zen basePath must NOT take the budget path

    @Test
    fun `non-zen basepath keeps normal path`() {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok)) }
        // apiKey="public" but basePath is api.openai.com → isZenFree = false
        val provider = OpenAIProvider(apiKey = "public", model = model("big-pickle"), basePath = server.url("/v1").toString().trimEnd('/'))
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = plainHistory(),
                    systemPrompt = null,
                    maxTokens = 4096,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = ThinkingLevel.MEDIUM,
                )
            }
        }
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(4096, body.optInt("max_completion_tokens"))
        assertEquals("medium", body.optString("reasoning_effort"))
    }

    // -- reasoning_effort must not appear on the free-lane body

    @Test
    fun `free lane body contains no reasoning_effort`() {
        for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA)) {
            val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
            repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok)) }
            // [T-zen-host-anchor] Zen host forced (MockWebServer base path can
            // never carry it) + apiKey="public" → isZenFree = true.
            val basePath = server.url("/v1").toString().trimEnd('/')
            val provider = OpenAIProvider(apiKey = "public", model = model("big-pickle"), basePath = basePath)
            provider.zenHostOverride = true
            runCatching {
                runBlocking {
                    provider.sendMessageClamped(
                        messages = plainHistory(),
                        systemPrompt = null,
                        maxTokens = 4096,
                        temperature = null,
                        imageParts = emptyList(),
                        tools = emptyList(),
                        thinkingLevel = level,
                    )
                }
            }
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(
                "reasoning_effort must NOT appear on the Zen free lane body at level=$level",
                false,
                body.has("reasoning_effort"),
            )
        }
    }
}