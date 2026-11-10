package com.openminis.app.provider.anthropic

// [T-llm-error-classification] 细分 provider 错误，不再笼统地归 ProviderError。
// "max_tokens too large" 是输出参数超限（max_tokens 请求过大），
// 不是上下文超长，保持 ProviderError。
internal fun anthropicContextLimit(statusCode: Int, lower: String): Boolean =
    !lower.contains("max_tokens too large") && (
        lower.contains("context_length_exceeded") || lower.contains("maximum context length") ||
            lower.contains("too many tokens") || lower.contains("reduce the length") ||
            (statusCode == 400 && (lower.contains("token") && (lower.contains("max") || lower.contains("limit") || lower.contains("exceed"))))
    )

internal fun anthropicContentFiltered(statusCode: Int, lower: String): Boolean =
    lower.contains("content_filter") || lower.contains("content_policy") ||
        lower.contains("safety") || lower.contains("inappropriate") ||
        statusCode == 422 && lower.contains("content")
