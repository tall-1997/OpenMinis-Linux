package com.openminis.app.ui.chat

import android.util.Log

/**
 * [T-oauth-refresh-dedupe] 发送前 OAuth 令牌刷新。
 *
 * 此前在 sendMessage 与 resumeQueueAfterCancel 里各有一份逐字重复的
 * ~25 行刷新块（漂移风险：两处已差出一行日志）。收口到一处：刷新成功时
 * 重建 provider 并写回 [ChatViewModel.currentProvider]，失败静默降级
 * （沿用旧 token，warning 日志）——与两处原行为一致。
 */
internal suspend fun ChatViewModel.refreshOAuthProviderIfNeeded(
    provider: com.openminis.app.provider.LLMProvider,
): com.openminis.app.provider.LLMProvider {
    if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth != true) {
        return provider
    }
    try {
        val activeEntryId = _activeEntryId.value
        val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
        val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
        if (instance != null) {
            val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            val freshToken = manager?.validAccessToken()
            if (freshToken != null) {
                val storedKey = providerRepository.loadApiKey(instance.id)
                if (freshToken != storedKey) {
                    providerRepository.saveApiKey(instance.id, freshToken)
                    // Recreate provider with fresh token
                    val refreshed = com.openminis.app.provider.ProviderFactory.create(
                        instance, freshToken, currentModel ?: provider.model, context
                    )
                    currentProvider = refreshed
                    Log.i(ChatViewModel.TAG, "OAuth token refreshed before send")
                    return refreshed
                }
            }
        }
    } catch (e: Exception) {
        Log.w(ChatViewModel.TAG, "OAuth token refresh failed: ${e.message}")
    }
    return provider
}
