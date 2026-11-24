package com.openminis.app.ui.chat

import com.openminis.app.data.isValidContextUsageAnchor
import com.openminis.app.data.model.ContextUsageAnchor
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.sameContextUsageModel
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

private const val CONTEXT_USAGE_ANCHOR = "contextUsageAnchor"
// v1 did not bind the effective serving route; never infer it from today's provider settings.
private const val CONTEXT_USAGE_ANCHOR_VERSION = 2

internal fun turnContextUsageAnchor(
    usage: LLMUsage?,
    modelSnapshot: ModelAttributionSnapshot?,
    anchor: ContextUsageAnchor?,
): ContextUsageAnchor? = anchor?.takeIf {
    usage != null && it.isValidContextUsageAnchor() &&
        (modelSnapshot == null || it.modelSnapshot.sameContextUsageModel(modelSnapshot))
}

internal fun buildTurnTokenUsageJson(
    usage: LLMUsage?,
    streamDurationMs: Long? = null,
    thinkingUsage: TurnThinkingUsage? = null,
    contextUsageAnchor: ContextUsageAnchor? = null,
): String? {
    // Never create a billing row just for an anchor; keep the old ledger unchanged.
    if (usage == null) return null
    return JSONObject().apply {
        put("inputTokens", usage.inputTokens)
        put("outputTokens", usage.outputTokens)
        put("cacheCreationTokens", usage.cacheCreationInputTokens ?: 0)
        put("cacheReadTokens", usage.cacheReadInputTokens ?: 0)
        put("latestContextTokens", usage.latestContextTokens)
 // [T-thinking-tokens-only] Count only; a provider that also streams
 // reasoning text gets its body from the thinking block instead.
 usage.reasoningTokens?.let { put("reasoningTokens", it) }
        streamDurationMs?.let { put("streamDurationMs", it) }
        thinkingUsage?.let {
            put("thinkingLevelRequested", it.requested)
            it.effective?.let { level -> put("thinkingLevelEffective", level) }
        }
        contextUsageAnchor?.takeIf { it.isValidContextUsageAnchor() }?.let { anchor ->
            put(CONTEXT_USAGE_ANCHOR, JSONObject().apply {
                put("version", CONTEXT_USAGE_ANCHOR_VERSION)
                put("modelId", anchor.modelSnapshot.modelId)
                put("providerType", anchor.modelSnapshot.providerTypeRaw)
                put("providerInstanceId", anchor.modelSnapshot.providerInstanceId)
                put("contextRouteId", anchor.modelSnapshot.contextRouteId)
                put("markerId", anchor.markerId ?: JSONObject.NULL)
                put("prefixLength", anchor.prefixLength)
                put("prefixFingerprint", anchor.prefixFingerprint)
                put("inputTokens", anchor.inputTokens)
            })
        }
    }.toString()
}

internal fun readContextUsageAnchor(
    raw: String?,
    rowModel: ModelAttributionSnapshot,
): ContextUsageAnchor? {
    if (raw == null) return null
    val json = try {
        val source = JSONTokener(raw)
        val root = source.nextValue() as? JSONObject ?: return null
        if (source.nextClean() != '\u0000' || source.more()) return null
        root.optJSONObject(CONTEXT_USAGE_ANCHOR)
    } catch (_: JSONException) {
        null
    } ?: return null
    if (json.strictInt("version") != CONTEXT_USAGE_ANCHOR_VERSION ||
        json.opt("modelId") != rowModel.modelId || json.opt("providerType") != rowModel.providerTypeRaw ||
        json.opt("providerInstanceId") != rowModel.providerInstanceId || !json.has("markerId")
    ) return null
    val markerId = when (val marker = json.opt("markerId")) {
        JSONObject.NULL -> null
        is String -> marker
        else -> return null
    }
    val routeId = json.opt("contextRouteId") as? String ?: return null
    if (rowModel.contextRouteId != null && rowModel.contextRouteId != routeId) return null
    return ContextUsageAnchor(
        modelSnapshot = rowModel.copy(contextRouteId = routeId),
        markerId = markerId,
        prefixLength = json.strictInt("prefixLength") ?: return null,
        prefixFingerprint = json.opt("prefixFingerprint") as? String ?: return null,
        inputTokens = json.strictInt("inputTokens") ?: return null,
    ).takeIf { it.isValidContextUsageAnchor() }
}

/** Avoid optInt's coercion of strings, fractions, and overflowing values. */
private fun JSONObject.strictInt(key: String): Int? = when (val value = opt(key)) {
    is Int -> value
    is Long -> value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    else -> null
}
