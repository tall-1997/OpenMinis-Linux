package com.openminis.app.data

import com.openminis.app.data.db.UsageRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * Session-spend ledger with pi's double-bucket shape (pi durable/src/harness/usage.ts:7-73):
 * a `models` bucket keyed by `provider/modelId` and a `tools` bucket keyed by tool name.
 *
 * Port notes (vs. pi):
 *  - pi sums five core counters per bucket entry and folds OPTIONAL counters
 *    (`cacheWrite1h`, `reasoning`) in only once either side reports them
 *    (usage.ts:43-56). Our persisted `token_usage` JSON
 *    (ChatViewModelTurnPersistExt.persistAssistantTurn) always writes
 *    input/output/cacheCreation/cacheRead, so those are core here; the optional
 *    slots below light up when a row actually carries the key — the debug
 *    export path (ChatDebugMethods) already writes `reasoningTokens`, and
 *    `cacheWrite1hTokens` is reserved for Anthropic 1h-TTL writes. Presence is
 *    detected with [JSONObject.has], NOT `optLong` defaults: a written 0 must
 *    sum, an absent key must stay absent.
 *  - pi guards its plain-JSON buckets against `__proto__`/`toString` key
 *    poisoning (usage.ts:63-70, Object.hasOwn + defineProperty). Kotlin maps
 *    are real hash tables with no prototype chain, so a tool literally named
 *    "toString" or "__proto__" is just a map key here — no guard needed.
 *  - `latestContextTokens`, `streamDurationMs` and the thinkingLevel* keys in
 *    our rows are deliberately NOT summed: they are per-row snapshots (last
 *    value semantics), not cumulative counters.
 *
 * Pure JVM; no Android, no Room at runtime. Callers feed it the rows they
 * already hold (Usage page window, session load), so the ledger adds no query
 * of its own.
 */
data class UsageState(
    /** Key: [modelBucketKey] — `"providerType/modelId"`, orphans fall to `"unknown/unknown"`. */
    val models: LinkedHashMap<String, ModelUsage> = LinkedHashMap(),
    /** Key: tool name as written into the `toolUse` block. */
    val tools: LinkedHashMap<String, ToolUsage> = LinkedHashMap(),
) {
    val isEmpty: Boolean get() = models.isEmpty() && tools.isEmpty()
}

/**
 * Cumulative token spend for one `provider/modelId` bucket. [calls] counts
 * persisted assistant turns folded into the entry (pi's ledger derives row
 * count implicitly from its doc history; ours needs an explicit counter).
 */
data class ModelUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val calls: Long = 0,
    /** Optional counter: summed only when a row carries it (null = never seen). */
    val reasoningTokens: Long? = null,
    /** Optional counter: Anthropic 1h-TTL cache writes; null = never seen. */
    val cacheWrite1hTokens: Long? = null,
) {
    /**
     * pi addUsage (usage.ts:43-56): core counters always sum; optional
     * counters join once EITHER side has seen them (`(a ?? 0) + (b ?? 0)` is
     * only computed when at least one side is non-null).
     */
    operator fun plus(other: ModelUsage): ModelUsage = ModelUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
        calls = calls + other.calls,
        reasoningTokens = when {
            reasoningTokens == null -> other.reasoningTokens
            else -> reasoningTokens + (other.reasoningTokens ?: 0)
        },
        cacheWrite1hTokens = when {
            cacheWrite1hTokens == null -> other.cacheWrite1hTokens
            else -> cacheWrite1hTokens + (other.cacheWrite1hTokens ?: 0)
        },
    )
}

/**
 * Tools bucket entry. pi's tools bucket carries a full Usage ("their usage
 * has no model identity", usage.ts:11-12); our persisted tool blocks carry no
 * token usage at all, so the only honest counter is the call count.
 */
data class ToolUsage(
    val calls: Long = 0,
) {
    operator fun plus(other: ToolUsage): ToolUsage = ToolUsage(calls = calls + other.calls)
}

/** Aggregate result: the folded state plus how many rows could not be parsed. */
data class UsageLedgerResult(
    val state: UsageState,
    /** Rows whose `token_usage` was blank/malformed and were skipped (never crashed on). */
    val skippedRows: Int,
)

// ── Keys ────────────────────────────────────────────────────────────────────

/** Bucket key for rows without a model snapshot — tokens were really billed (GH#168). */
private const val UNKNOWN = "unknown"

/**
 * pi keys the models bucket by `provider/modelId` (usage.ts:9). Our rows carry
 * the `ProviderType` rawValue (`openAI`) and the model id; NULL on either side
 * means the row predates the write-time snapshot or its session row is gone —
 * those tokens are still billed, so they land under `"unknown/…"` instead of
 * being dropped (same policy as the Usage page, UsageStatsScreen GH#168).
 *
 * Deliberately NOT part of the key: `providerInstanceId`. pi has no instance
 * concept and the task fixes the key shape to provider/modelId; the Usage page
 * splits instances, this ledger does not.
 */
fun modelBucketKey(providerType: String?, modelId: String?): String =
    "${providerType?.takeIf { it.isNotBlank() } ?: UNKNOWN}/${modelId?.takeIf { it.isNotBlank() } ?: UNKNOWN}"

// ── Folding ─────────────────────────────────────────────────────────────────

/**
 * Fold one persisted usage row into [state]'s models bucket. Returns false and
 * leaves [state] untouched when `record.tokenUsage` is blank or malformed —
 * same skip-not-crash contract as UsageStatsScreen's aggregation.
 *
 * Reads the write-side keys of the chat turn-persist path (persistAssistantTurn)
 * with the Usage page's legacy fallbacks (old rows wrote
 * `cacheCreationInputTokens`/`cacheReadInputTokens` — UsageStatsScreen.kt:202).
 */
fun foldUsageRecord(state: UsageState, record: UsageRecord): Boolean {
    val usage = parseUsageJson(record.tokenUsage) ?: return false
    val key = modelBucketKey(record.providerType, record.modelId)
    val addend = ModelUsage(
        inputTokens = usage.optLong("inputTokens", 0),
        outputTokens = usage.optLong("outputTokens", 0),
        cacheReadTokens = usage.optLong("cacheReadTokens", usage.optLong("cacheReadInputTokens", 0)),
        cacheWriteTokens = usage.optLong("cacheCreationTokens", usage.optLong("cacheCreationInputTokens", 0)),
        calls = 1,
        // Optional counters participate only when the key exists — a written
        // 0 still counts; an absent key stays absent (pi usage.ts:49-50).
        reasoningTokens = if (usage.has("reasoningTokens")) usage.optLong("reasoningTokens", 0) else null,
        cacheWrite1hTokens = if (usage.has("cacheWrite1h")) usage.optLong("cacheWrite1h", 0) else null,
    )
    state.models[key] = (state.models[key] ?: ModelUsage()) + addend
    return true
}

/**
 * Fold [count] calls of [toolName] into the tools bucket. Callers that already
 * hold parsed blocks (ui `ChatMessage.toolBlocks`) fold directly per tool; no
 * re-parsing needed.
 */
fun foldToolUse(state: UsageState, toolName: String, count: Long = 1) {
    if (toolName.isBlank() || count <= 0) return
    state.tools[toolName] = (state.tools[toolName] ?: ToolUsage()) + ToolUsage(calls = count)
}

/**
 * Aggregate a window of persisted usage rows into the models bucket.
 * Windowed rows come from `UsageStatsDao.usageRecordsBetween` (bounded by the
 * caller's range) — this function performs no query of its own.
 */
fun aggregateModels(records: List<UsageRecord>): UsageLedgerResult {
    val state = UsageState()
    var skipped = 0
    for (record in records) {
        if (!foldUsageRecord(state, record)) skipped++
    }
    return UsageLedgerResult(state, skipped)
}

/**
 * pi addUsageState (usage.ts:59-73): merge [other] into [sum] bucket by
 * bucket. Keys are map entries in Kotlin — no prototype hazard to guard, and
 * an entry named "__proto__" folds like any other key.
 */
fun mergeUsageState(sum: UsageState, other: UsageState) {
    for ((key, usage) in other.models) {
        sum.models[key] = (sum.models[key] ?: ModelUsage()) + usage
    }
    for ((name, usage) in other.tools) {
        sum.tools[name] = (sum.tools[name] ?: ToolUsage()) + usage
    }
}

// ── Tools bucket from persisted parts ───────────────────────────────────────

/**
 * Extract tool names from one persisted `parts_json` row (the on-disk block
 * shape written by the persist path: an array whose assistant rows carry
 * `{"type":"toolUse","value":{"name":"…"}}` and user rows carry
 * `{"type":"toolResult","value":{"name":…,"toolUseId":…}}`).
 *
 * Counts `toolUse` blocks only: one toolUse = one model-issued call, so the
 * tools bucket stays a per-tool CALL count (task 1 wording); toolResult blocks
 * mirror the same call set and would double-count. Blank names (parse
 * failures persisted as blocks) are skipped. Empty list on malformed JSON.
 *
 * Cost note (why this is a helper, not a query): full-history tools rebuild
 * from Room would have to pull `parts_json` for every message (bodies up to
 * MBs, partially offloaded to BodyStore). The intended wiring folds from
 * blocks ALREADY in memory (ui ChatMessage.toolBlocks via foldToolUse) or from
 * rows the caller has in hand; this extractor serves the latter.
 */
fun extractToolUseNames(partsJson: String): List<String> {
    if (partsJson.isBlank()) return emptyList()
    return try {
        val array = JSONArray(partsJson)
        val names = ArrayList<String>()
        for (i in 0 until array.length()) {
            val block = array.optJSONObject(i) ?: continue
            if (block.optString("type") != "toolUse") continue
            val name = block.optJSONObject("value")?.optString("name").orEmpty()
            if (name.isNotBlank()) names.add(name)
        }
        names
    } catch (_: Exception) {
        emptyList()
    }
}

// ── Internals ───────────────────────────────────────────────────────────────

private fun parseUsageJson(tokenUsage: String?): JSONObject? {
    if (tokenUsage.isNullOrBlank()) return null
    return try {
        JSONObject(tokenUsage)
    } catch (_: Exception) {
        null
    }
}
