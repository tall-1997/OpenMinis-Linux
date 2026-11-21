package com.openminis.app.ui.chat

import com.openminis.app.auth.ClaudeOAuthManager
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.anthropic.AnthropicProvider

/**
 * Reading sessions and estimating context must not require optional OAuth build
 * metadata.
 *
 * [T-oauth-prefix-single-authority] This is the single place that knows the
 * prefix shape. The string-based `wrapSystemPromptForOAuth` that used to sit
 * here was removed: dispatch now carries the boundary on
 * `projection.prompt.stablePrefixLen`, and a second, un-called wrapper would
 * have invited a third divergent offset rule.
 */
internal fun oauthIdentifierPrefix(
    provider: LLMProvider,
    identifierProvider: () -> String? = ClaudeOAuthManager::identifierPromptOrNull,
): String? {
    if ((provider as? AnthropicProvider)?.isOAuth != true) return null
    return identifierProvider()?.takeIf { it.isNotBlank() }?.let { "$it\n\n" }
}
