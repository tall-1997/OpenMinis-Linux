package com.openminis.app.provider.thinking

import com.openminis.app.data.model.BailianEndpoint

/**
 * [T-thinking-vendor-shape-scope] Which endpoints can actually READ a
 * vendor-private thinking shape?
 *
 * Extracted from [ThinkingRuleResolver] to keep that file under its
 * architecture ceiling. Pure data + one URI parse: no state, no I/O.
 *
 * The rule these functions exist to express, stated once for every vendor:
 *
 * a PRIVATE shape may only go to its owner host, otherwise the PORTABLE
 * shape (plain OpenAI `reasoning_effort`) is used instead.
 *
 * Adding a vendor is one line of data in [owningHostOf], never a new branch
 * at a call site.
 */

/**
 * Can the endpoint hosting [format] read it? `null` when the shape is
 * PORTABLE.
 *
 * `null` = portable: the shape makes no vendor assumption (plain OpenAI
 * `reasoning_effort`, or "send nothing"), so it is correct on any endpoint.
 *
 * A predicate = vendor-private: only the vendor's own endpoint parses it. A
 * relay carrying the same model id is still an OpenAI-compatible proxy — it
 * ignores unknown fields and answers without reasoning.
 *
 * [baseUrl] is threaded through because ownership is not always a single
 * literal host: Bailian serves workspaces from per-tenant hosts, and the repo
 * already owns one predicate for "is this Bailian", [BailianEndpoint.matches].
 * Re-listing domains here is how the previous version lost
 * `*.maas.aliyuncs.com` and silently downgraded official endpoints.
 *
 * Scope note: this covers the OpenAI-compatible body path. The native
 * Anthropic / Gemini builders resolve their own hosts and do not come here.
 */
internal fun owningHostOf(
    format: ThinkingWireFormat,
    baseUrl: String?,
): ((String) -> Boolean)? = when (format) {
    is ThinkingWireFormat.DeepSeekSibling -> { h -> h == "api.deepseek.com" }
    is ThinkingWireFormat.AnthropicThinking -> { h -> h == "api.anthropic.com" }
    // Bailian: workspaces get their own host (`x.maas.aliyuncs.com`), so reuse
    // the shared predicate instead of enumerating domains.
    is ThinkingWireFormat.QwenDual -> { h ->
        BailianEndpoint.matches(baseUrl) ||
            h.endsWith("dashscope.aliyuncs.com") ||
            h.endsWith("dashscope-intl.aliyuncs.com")
    }
    is ThinkingWireFormat.GeminiBudget,
    is ThinkingWireFormat.GeminiThinkingLevel,
    -> { h -> h.endsWith("generativelanguage.googleapis.com") }
    // Portable, or the shape was configured BY the endpoint's own rule
    // (CustomPath / *Toggle): rewriting those by host would override the user's
    // own configuration, so they are never host-restricted.
    else -> null
}

/**
 * Lower-cased host of [raw], without scheme, port or path.
 *
 * `susu.wiki/deepseek/v1` yields `susu.wiki`, so a relay whose PATH mentions a
 * vendor is no longer mistaken for that vendor's host — which is exactly what
 * `contains("deepseek")` did, and why the private shape kept reaching relays.
 *
 * Returns null for blank or unparseable input; the caller then keeps the
 * declared shape rather than guessing.
 */
internal fun hostOf(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    // No `return` inside the lambda: the parse must fall through to the same
    // null/normalise path whether it succeeded or threw.
    val parsed = runCatching {
        java.net.URI(if ("://" in trimmed) trimmed else "https://$trimmed").host
    }
    val host = parsed.getOrNull()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
    if (host == null) {
        // [T-thinking-vendor-shape-scope] Instrumented on purpose: an unparseable
        // endpoint silently keeping the vendor shape is how a relay ends up with
        // a contract it cannot read, and there was no trace of the decision.
        com.openminis.app.logging.AppLogger.warning(
            "Thinking",
            "[rules] endpoint \"$trimmed\" did not yield a host " +
                "(${parsed.exceptionOrNull()?.javaClass?.simpleName ?: "no-host"}); " +
                "keeping the declared thinking shape",
        )
    }
    return host
}
