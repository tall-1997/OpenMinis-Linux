package com.openminis.app.provider.anthropic

import com.openminis.app.data.model.LLMUsage
import org.json.JSONObject

/**
 * [T-anthropic-provider-ceiling] Extracted from AnthropicProvider when the
 * architecture ceiling left no room. Pure helpers, no state.
 */

/** Parse an Anthropic `usage` block into [LLMUsage] (context = full input). */
internal fun parseAnthropicUsage(json: JSONObject): LLMUsage {
    val inputTokens = json.optInt("input_tokens", 0)
    return LLMUsage(
        inputTokens = inputTokens,
        outputTokens = json.optInt("output_tokens", 0),
        cacheCreationInputTokens = json.optInt("cache_creation_input_tokens").takeIf { it > 0 },
        cacheReadInputTokens = json.optInt("cache_read_input_tokens").takeIf { it > 0 },
        latestContextTokens = inputTokens,
    )
}
