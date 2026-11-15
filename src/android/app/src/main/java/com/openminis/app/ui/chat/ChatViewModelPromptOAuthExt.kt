package com.openminis.app.ui.chat

import com.openminis.app.provider.LLMProvider

/**
 * [T-oauth-prefix-offset] Anthropic OAuth requests require the Claude Code
 * identifier prefix in front of the system prompt. The stable-prefix length
 * published by [ChatViewModel.buildSystemPromptWithSections] is measured on
 * the UN-prefixed base, so every caller that wraps must shift the published
 * offset by the exact bytes the wrapper adds (`prefix + "\n\n"`) — otherwise
 * the cache_control breakpoint lands a few bytes into the dynamic tail and
 * the cached prefix is rewritten (never read back) on every OAuth turn.
 *
 * Single authority for all five request-side paths (send / retry / resume /
 * rerun-tail / queue-drain): the wrap and the shift live together so they can
 * no longer drift apart. Already-prefixed bases pass through unchanged,
 * matching the previous per-site guards.
 */
internal fun ChatViewModel.wrapSystemPromptForOAuth(
    provider: LLMProvider,
    baseSystemPrompt: String?,
): String? {
    val isOAuth = (provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true
    if (!isOAuth) return baseSystemPrompt
    val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
    if (baseSystemPrompt?.startsWith(prefix) == true) return baseSystemPrompt
    systemPromptStablePrefixLen += prefix.length + 2
    return "$prefix\n\n${baseSystemPrompt ?: ""}"
}
