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
     * outbound body. `apiKey = "public"` + the Zen basePath substring triggers
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
        // apiKey="public" + basePath containing "opencode.ai/zen" triggers isZenFree
        val basePath = server.url("/opencode.ai/zen/v1").toString().trimEnd('/')
        val provider = OpenAIProvider(apiKey = "public", model = model, basePath = basePath)
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
    fun `off → 1024`() {
        // REWRITTEN [T-zen-free-thinking-budget-ladder]: the old expectation
        // of 2048 was wrong because OFF and LOW BOTH emitted 2048 — picking
        // OFF instead of LOW changed nothing on the wire. OFF now sits one
        // step BELOW LOW: it exists to leave room for the answer only.
        val body = capture(model("big-pickle"), ThinkingLevel.OFF, 4096)
        assertEquals(1024, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `low → 2048 and strictly above off`() {
        // REWRITTEN: the raw 2048 is unchanged, but as a bare number it was
        // vacuous — it happened to equal OFF's. Pin the RELATIONSHIP that was
        // actually missing: LOW must buy reasoning room above OFF.
        val low = budgetFor(level = ThinkingLevel.LOW)
        val off = budgetFor(level = ThinkingLevel.OFF)
        assertEquals(2048, low)
        assertTrue("LOW ($low) must exceed OFF ($off)", low > off)
    }

    @Test
    fun `medium → 8192`() {
        val body = capture(model("big-pickle"), ThinkingLevel.MEDIUM, 4096)
        assertEquals(8192, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `high → 16384`() {
        val body = capture(model("big-pickle"), ThinkingLevel.HIGH, 4096)
        assertEquals(16384, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `xhigh → 32768 base rung, strictly above high`() {
        // REWRITTEN [T-zen-free-thinking-budget-ladder]: XHIGH used to fall
        // through to `null` and emit the model's FULL capacity, identical to
        // MAX and ULTRA. It is now a 32K rung — exactly one doubling step
        // above HIGH — so the 65536-capacity model below pays out a real
        // 32K instead of clipping to the ceiling on the very first jump.
        val xhigh = budgetFor(level = ThinkingLevel.XHIGH, maxOutputTokens = 65536)
        val high = budgetFor(level = ThinkingLevel.HIGH, maxOutputTokens = 65536)
        assertEquals(32768, xhigh)
        assertTrue("XHIGH ($xhigh) must exceed HIGH ($high)", xhigh > high)
    }

    @Test
    fun `max → 49152, strictly between xhigh and ultra`() {
        // REWRITTEN: MAX emitted the full 65536 capacity, the same number as
        // XHIGH and ULTRA — the rung did nothing. It is now a 49152 rung, a
        // ×1.5 step above XHIGH rather than a second power of two, so the
        // reasoning ceiling keeps widening but never simply IS the capacity.
        val max = budgetFor(level = ThinkingLevel.MAX, maxOutputTokens = 65536)
        val xhigh = budgetFor(level = ThinkingLevel.XHIGH, maxOutputTokens = 65536)
        val ultra = budgetFor(level = ThinkingLevel.ULTRA, maxOutputTokens = 65536)
        assertEquals(49152, max)
        assertTrue("MAX ($max) must exceed XHIGH ($xhigh)", max > xhigh)
        assertTrue("MAX ($max) must stay below ULTRA ($ultra)", max < ultra)
    }

    @Test
    fun `ultra → 73728, approaches capacity without reaching it`() {
        // REWRITTEN: ULTRA used to be the full capacity, identical to MAX —
        // a rung that cannot be told apart from the one below it. It is now
        // a 73728 rung on a 131072-capacity model: the widest budget the
        // ladder offers, deliberately short of the ceiling so the top rung
        // is a real step rather than a second name for "capacity".
        val ultra = budgetFor(level = ThinkingLevel.ULTRA, maxOutputTokens = 131072)
        val max = budgetFor(level = ThinkingLevel.MAX, maxOutputTokens = 131072)
        assertEquals(73728, ultra)
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
        // And nothing may exceed what the model declared it can emit.
        budgets.forEach { (lvl, v) ->
            assertTrue("$lvl=$v must not exceed capacity 131072", v <= 131072)
        }
    }

    @Test
    fun `must-think ladder stays strictly increasing after doubling`() {
        // [T-zen-free-thinking-budget-ladder] The must-think ×2 applies
        // uniformly to every rung, so it cannot introduce a collapse by
        // itself — but it is the one transform that can push a rung past
        // capacity and clip it, so monotonicity is asserted here too rather
        // than assumed. Capacity is 131072 here, far above every doubled
        // rung, so nothing clips and all seven must be distinct; the
        // clip-on-a-small-model case is pinned separately below.
        val budgets = ascendingRungs.map { level ->
            level to budgetFor(id = "mimo-v2.6-flash-free", level = level, maxOutputTokens = 131072)
        }
        budgets.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "must-think ladder must strictly increase: ${a.first}=${a.second} then ${b.first}=${b.second}",
                b.second > a.second,
            )
        }
    }

    // -- must-think model (mimo-v2.6): doubled rungs

    @Test
    fun `mimo-v2p6 off → 2048 doubled`() {
        // REWRITTEN [T-zen-free-thinking-budget-ladder]: the old 4096 came
        // from OFF's 2048 base ×2, which is exactly the base OFF used to
        // SHARE with LOW — the rung was not distinguishable from the one
        // below it. OFF's base is now 1024, so the doubled rung is 2048 and
        // still strictly under MEDIUM's 16384. The doubling itself (the
        // measured part) is unchanged.
        val body = capture(
            model("mimo-v2.6-flash-free", maxOutputTokens = 32768),
            ThinkingLevel.OFF,
            4096,
        )
        assertEquals(2048, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `mimo-v2p6 medium → 16384 doubled`() {
        val body = capture(
            model("mimo-v2.6-flash-free", maxOutputTokens = 32768),
            ThinkingLevel.MEDIUM,
            4096,
        )
        assertEquals(16384, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `mimo-v2p6 high capped at capacity`() {
        val body = capture(
            model("mimo-v2.6-flash-free", maxOutputTokens = 32768),
            ThinkingLevel.HIGH,
            4096,
        )
        assertEquals(32768, body.optInt("max_completion_tokens"))
    }

    // -- must-think model (mimo-v2.5): doubled rungs

    @Test
    fun `mimo-v2p5 off → 2048 doubled`() {
        // REWRITTEN: same reason as the mimo-v2.6 OFF test above — the old
        // 4096 was inherited from OFF's old shared 2048 base. Base is now
        // 1024, doubled to 2048; the must-think ×2 is preserved.
        val body = capture(
            model("mimo-v2.5-free", maxOutputTokens = 32768),
            ThinkingLevel.OFF,
            4096,
        )
        assertEquals(2048, body.optInt("max_completion_tokens"))
    }

    @Test
    fun `mimo-v2p5 medium → 16384 doubled`() {
        val body = capture(
            model("mimo-v2.5-free", maxOutputTokens = 32768),
            ThinkingLevel.MEDIUM,
            4096,
        )
        assertEquals(16384, body.optInt("max_completion_tokens"))
    }

    // -- budget clipped to model capacity

    @Test
    fun `budget clipped to low model capacity`() {
        val body = capture(
            model("big-pickle", maxOutputTokens = 1500),
            ThinkingLevel.MEDIUM,
            8192,
        )
        assertEquals(1500, body.optInt("max_completion_tokens"))
    }

    // -- minimum floor

    @Test
    fun `floor never below 512`() {
        val body = capture(
            model("big-pickle", maxOutputTokens = 200),
            ThinkingLevel.OFF,
            200,
        )
        assertEquals(512, body.optInt("max_completion_tokens"))
    }

    // -- regression: paid Zen provider must NOT take the budget path

    @Test
    fun `paid provider keeps reasoning_effort path`() {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok)) }
        // apiKey is a real key, not "public" → isZenFree = false
        val basePath = server.url("/opencode.ai/zen/v1").toString().trimEnd('/')
        val provider = OpenAIProvider(apiKey = "sk-real-key", model = model("big-pickle"), basePath = basePath)
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
            val basePath = server.url("/opencode.ai/zen/v1").toString().trimEnd('/')
            val provider = OpenAIProvider(apiKey = "public", model = model("big-pickle"), basePath = basePath)
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