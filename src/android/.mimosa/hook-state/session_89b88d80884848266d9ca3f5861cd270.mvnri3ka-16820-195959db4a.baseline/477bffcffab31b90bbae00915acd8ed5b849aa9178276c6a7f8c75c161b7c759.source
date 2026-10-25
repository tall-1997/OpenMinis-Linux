package com.openminis.app.provider

import org.json.JSONObject

/**
 * 从 /v1/models 的单个模型对象里读上下文长度与最大输出长度。
 *
 * 各家字段名不统一，按出现频率依次尝试：
 *  - 上下文：context_window、context_length、max_context_length、
 *    max_model_len，以及嵌套的 limit.context
 *  - 输出：max_output_tokens、max_completion_tokens、max_tokens，
 *    top_provider.max_completion_tokens，以及嵌套的 limit.output
 *
 * 读不到或非正数返回 null，调用方按「端点没给」处理（目录 → 兜底）。
 */
object EndpointModelLimits {

    fun read(obj: JSONObject): Pair<Int?, Int?> {
        val limit = obj.optJSONObject("limit")
        val topProvider = obj.optJSONObject("top_provider")
        val context = firstPositive(
            obj, "context_window", "context_length", "max_context_length", "max_model_len",
        ) ?: limit?.let { firstPositive(it, "context") }
        val output = firstPositive(
            obj, "max_output_tokens", "max_completion_tokens", "max_tokens",
        ) ?: topProvider?.let { firstPositive(it, "max_completion_tokens") }
            ?: limit?.let { firstPositive(it, "output") }
        return context to output
    }

    private fun firstPositive(obj: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (!obj.has(key)) continue
            val value = obj.optInt(key, 0)
            if (value > 0) return value
        }
        return null
    }
}
