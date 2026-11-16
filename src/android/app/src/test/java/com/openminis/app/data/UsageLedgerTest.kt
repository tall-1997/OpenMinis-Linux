package com.openminis.app.data

import com.openminis.app.data.db.UsageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port-fidelity tests for the double-bucket usage ledger (pi
 * durable/src/harness/usage.ts:7-73). Covers: two-bucket aggregation
 * correctness, empty input, optional-counter participation once seen, orphan
 * key degradation, malformed-row skipping, legacy key fallbacks, and the
 * persisted-parts tool extractor.
 */
class UsageLedgerTest {

    private fun record(
        tokenUsage: String?,
        providerType: String? = "anthropic",
        modelId: String? = "claude-x",
        sessionId: String = "s1",
    ) = UsageRecord(
        modelId = modelId,
        modelDisplayName = null,
        providerType = providerType,
        providerInstanceId = null,
        hasSnapshot = modelId != null,
        tokenUsage = tokenUsage ?: "",
        createdAt = 1_000L,
        sessionId = sessionId,
    )

    private fun usageJson(
        input: Long = 0,
        output: Long = 0,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
        extra: String = "",
    ) = """{"inputTokens":$input,"outputTokens":$output,"cacheCreationTokens":$cacheWrite,""" +
        """"cacheReadTokens":$cacheRead,"latestContextTokens":${input + cacheRead}$extra}"""

    // ── models bucket ───────────────────────────────────────────────────────

    @Test
    fun `aggregates per provider-model bucket`() {
        val result = aggregateModels(
            listOf(
                record(usageJson(input = 100, output = 10, cacheRead = 50, cacheWrite = 20)),
                record(usageJson(input = 30, output = 5)),
                record(usageJson(input = 7), providerType = "openAI", modelId = "gpt-y"),
            ),
        )
        assertEquals(0, result.skippedRows)
        assertEquals(2, result.state.models.size)
        val anthropic = result.state.models.getValue("anthropic/claude-x")
        assertEquals(130L, anthropic.inputTokens)
        assertEquals(15L, anthropic.outputTokens)
        assertEquals(50L, anthropic.cacheReadTokens)
        assertEquals(20L, anthropic.cacheWriteTokens)
        assertEquals(2L, anthropic.calls)
        val openai = result.state.models.getValue("openAI/gpt-y")
        assertEquals(7L, openai.inputTokens)
        assertEquals(1L, openai.calls)
    }

    @Test
    fun `empty input yields empty state`() {
        val result = aggregateModels(emptyList())
        assertTrue(result.state.isEmpty)
        assertEquals(0, result.skippedRows)
    }

    @Test
    fun `orphan rows degrade to unknown bucket instead of dropping tokens`() {
        // GH#168: session row gone -> providerType/modelId both NULL, tokens still billed.
        val result = aggregateModels(listOf(record(usageJson(input = 42), providerType = null, modelId = null)))
        assertEquals(42L, result.state.models.getValue("unknown/unknown").inputTokens)
    }

    @Test
    fun `malformed and blank token_usage rows are skipped not crashed`() {
        val result = aggregateModels(
            listOf(record(null), record(""), record("not-json {"), record(usageJson(input = 9))),
        )
        // Only the one well-formed row (input=9) folds; the three malformed
        // ones are skipped, not zeroed into the bucket.
        assertEquals(9L, result.state.models.getValue("anthropic/claude-x").inputTokens)
        assertEquals(3, result.skippedRows)
    }

    @Test
    fun `legacy key fallbacks are honored`() {
        val legacy = """{"inputTokens":1,"outputTokens":2,""" +
            """"cacheCreationInputTokens":3,"cacheReadInputTokens":4,"latestContextTokens":7}"""
        val result = aggregateModels(listOf(record(legacy)))
        val entry = result.state.models.getValue("anthropic/claude-x")
        assertEquals(3L, entry.cacheWriteTokens)
        assertEquals(4L, entry.cacheReadTokens)
    }

    // ── optional counters (pi usage.ts:49-50) ──────────────────────────────

    @Test
    fun `optional counter joins once either side carries it`() {
        val withReasoning = usageJson(input = 1, extra = ""","reasoningTokens":5""")
        val withoutReasoning = usageJson(input = 2)
        val result = aggregateModels(listOf(record(withReasoning), record(withoutReasoning)))
        // One row reported reasoning: the total must carry it (5), not drop or default it.
        assertEquals(5L, result.state.models.getValue("anthropic/claude-x").reasoningTokens)
    }

    @Test
    fun `optional counter absent on every row stays null`() {
        val result = aggregateModels(listOf(record(usageJson(input = 1)), record(usageJson(input = 2))))
        assertNull(result.state.models.getValue("anthropic/claude-x").reasoningTokens)
        assertNull(result.state.models.getValue("anthropic/claude-x").cacheWrite1hTokens)
    }

    @Test
    fun `optional counter present on both sides sums`() {
        val a = usageJson(input = 1, extra = ""","reasoningTokens":3,"cacheWrite1h":7""")
        val b = usageJson(input = 1, extra = ""","reasoningTokens":4,"cacheWrite1h":8""")
        val entry = aggregateModels(listOf(record(a), record(b))).state.models.getValue("anthropic/claude-x")
        assertEquals(7L, entry.reasoningTokens)
        assertEquals(15L, entry.cacheWrite1hTokens)
    }

    @Test
    fun `written zero of optional counter still participates`() {
        val zero = usageJson(input = 1, extra = ""","reasoningTokens":0""")
        val entry = aggregateModels(listOf(record(zero))).state.models.getValue("anthropic/claude-x")
        assertEquals(0L, entry.reasoningTokens)
    }

    @Test
    fun `per-row snapshots are never summed into the ledger`() {
        // latestContextTokens is last-value semantics; folding it would fabricate a total.
        val result = aggregateModels(listOf(record(usageJson(input = 1)), record(usageJson(input = 2))))
        // No ModelUsage field exists to hold it — the assertion is the shape itself plus core sums.
        val entry = result.state.models.getValue("anthropic/claude-x")
        assertEquals(3L, entry.inputTokens)
        assertEquals(2L, entry.calls)
    }

    // ── tools bucket ────────────────────────────────────────────────────────

    @Test
    fun `foldToolUse accumulates per tool name`() {
        val state = UsageState()
        foldToolUse(state, "file_read")
        foldToolUse(state, "shell_execute")
        foldToolUse(state, "file_read")
        assertEquals(2L, state.tools.getValue("file_read").calls)
        assertEquals(1L, state.tools.getValue("shell_execute").calls)
    }

    @Test
    fun `foldToolUse ignores blank names and non-positive counts`() {
        val state = UsageState()
        foldToolUse(state, "  ")
        foldToolUse(state, "file_read", 0)
        assertTrue(state.tools.isEmpty())
    }

    @Test
    fun `pi key-poisoning names are plain map keys in Kotlin`() {
        // pi guards Object.hasOwn/defineProperty because a tool named
        // "toString"/"__proto__" would corrupt a plain-JSON bucket. Kotlin
        // maps have no prototype chain — the same names just fold.
        val state = UsageState()
        foldToolUse(state, "toString")
        foldToolUse(state, "__proto__")
        assertEquals(1L, state.tools.getValue("toString").calls)
        assertEquals(1L, state.tools.getValue("__proto__").calls)
    }

    @Test
    fun `extractToolUseNames reads persisted toolUse blocks`() {
        val parts =
            """[{"type":"text","value":"doing work"},""" +
                """{"type":"toolUse","value":{"name":"file_read","input":"{}"}},""" +
                """{"type":"toolUse","value":{"name":"shell_execute"}},""" +
                """{"type":"toolResult","value":{"name":"file_read","toolUseId":"t1"}}]"""
        assertEquals(listOf("file_read", "shell_execute"), extractToolUseNames(parts))
    }

    @Test
    fun `extractToolUseNames skips blank names and malformed json`() {
        assertEquals(emptyList<String>(), extractToolUseNames("[{\"type\":\"toolUse\",\"value\":{\"name\":\"\"}}]"))
        assertEquals(emptyList<String>(), extractToolUseNames("not json"))
        assertEquals(emptyList<String>(), extractToolUseNames(""))
    }

    @Test
    fun `extractor feeds the tools bucket end to end`() {
        val state = UsageState()
        for (name in extractToolUseNames("""[{"type":"toolUse","value":{"name":"file_edit"}}]""")) {
            foldToolUse(state, name)
        }
        assertEquals(1L, state.tools.getValue("file_edit").calls)
    }

    // ── merge (pi addUsageState) ────────────────────────────────────────────

    @Test
    fun `mergeUsageState folds both buckets`() {
        val sum = UsageState()
        foldToolUse(sum, "web_search", 3)
        val other = aggregateModels(listOf(record(usageJson(input = 10, output = 2)))).state
        foldToolUse(other, "web_search", 2)
        foldToolUse(other, "file_read", 1)
        val otherModels = UsageState(models = LinkedHashMap(other.models), tools = LinkedHashMap(other.tools))

        mergeUsageState(sum, otherModels)
        assertEquals(10L, sum.models.getValue("anthropic/claude-x").inputTokens)
        assertEquals(5L, sum.tools.getValue("web_search").calls)
        assertEquals(1L, sum.tools.getValue("file_read").calls)
    }

    // ── key building ────────────────────────────────────────────────────────

    @Test
    fun `modelBucketKey handles partial nulls`() {
        assertEquals("anthropic/claude-x", modelBucketKey("anthropic", "claude-x"))
        assertEquals("unknown/claude-x", modelBucketKey(null, "claude-x"))
        assertEquals("anthropic/unknown", modelBucketKey("anthropic", null))
        assertEquals("unknown/unknown", modelBucketKey("", " "))
    }
}
