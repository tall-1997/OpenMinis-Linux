// [port-upstream-1.16] Upgraded to OpenMinis/OpenMinis upstream 1a71df0f
// (OpenAIModelsApi.reportedLimits) semantics: key order context_length first,
// camelCase spellings, OpenRouter-style top_provider limits, and numeric-string
// acceptance. The extra max_model_len key this tree already read (vLLM) is kept.
package com.openminis.app.provider

import org.json.JSONObject

/**
 * 从 /v1/models 的单个模型对象里读上下文长度与最大输出长度。
 *
 * [OpenMinis#438] A gateway that reports its own limits is the authority on
 * them; models.dev enrichment only fills what it left out
 * (ModelParamPriority / limitsReportedByEndpoint carry that contract).
 *
 * 各家字段名不统一，按出现频率依次尝试：
 *  - 上下文：context_length、context_window、contextWindow、
 *    max_context_length、max_model_len，嵌套的 limit.context，
 *    以及 OpenRouter 风格的 top_provider.context_length
 *  - 输出：max_output_tokens、maxOutputTokens、max_completion_tokens、
 *    max_tokens，嵌套的 limit.output 与 top_provider.max_completion_tokens
 *
 * 数字与数字字符串都接受；0、负数、非数字、布尔值视为「没返回」。
 * 读不到返回 null，调用方按「端点没给」处理（目录 → 兜底）。
 */
object EndpointModelLimits {

    fun read(obj: JSONObject): Pair<Int?, Int?> {
        val limit = obj.optJSONObject("limit")
        val top = obj.optJSONObject("top_provider")
        val context = listOf("context_length", "context_window", "contextWindow", "max_context_length", "max_model_len")
            .firstNotNullOfOrNull { positiveInt(obj.opt(it)) }
            ?: limit?.let { positiveInt(it.opt("context")) }
            ?: top?.let { positiveInt(it.opt("context_length")) }
        val output = listOf("max_output_tokens", "maxOutputTokens", "max_completion_tokens", "max_tokens")
            .firstNotNullOfOrNull { positiveInt(obj.opt(it)) }
            ?: limit?.let { positiveInt(it.opt("output")) }
            ?: top?.let { positiveInt(it.opt("max_completion_tokens")) }
        return context to output
    }

    private fun positiveInt(raw: Any?): Int? {
        val value: Long? = when (raw) {
            is Boolean -> null
            is Number -> raw.toDouble().takeIf { it.isFinite() && it >= 1 && it < Int.MAX_VALUE.toDouble() }?.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
        return value?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }
}
