package com.openminis.app.ui.chat

import com.openminis.app.auth.ClaudeOAuthManager
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.anthropic.AnthropicProvider

/** Wrap and move the published cache boundary together, preserving an unknown boundary. */
internal fun ChatViewModel.wrapSystemPromptForOAuth(
    provider: LLMProvider,
    baseSystemPrompt: String?,
): String? {
    val prefix = oauthIdentifierPrefix(provider) ?: return baseSystemPrompt
    if (baseSystemPrompt?.startsWith(prefix) == true) return baseSystemPrompt
    if (systemPromptStablePrefixLen >= 0) systemPromptStablePrefixLen += prefix.length
    return prefix + baseSystemPrompt.orEmpty()
}

/** Reading sessions and estimating context must not require optional OAuth build metadata. */
internal fun oauthIdentifierPrefix(
    provider: LLMProvider,
    identifierProvider: () -> String? = ClaudeOAuthManager::identifierPromptOrNull,
): String? {
    if ((provider as? AnthropicProvider)?.isOAuth != true) return null
    return identifierProvider()?.takeIf { it.isNotBlank() }?.let { "$it\n\n" }
}
