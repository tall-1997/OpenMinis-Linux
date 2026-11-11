package com.openminis.app.ui.chat

import com.openminis.app.provider.OpenCodeSessionHeader
import com.openminis.app.provider.ProviderHeaderStore

/**
 * [T-opencode-session-header] Chat-side wiring of the OpenCode Go header
 * (GH#327/#304). The provider instance is LRU-cached and shared across
 * conversations, so a per-conversation id cannot live in its constructor —
 * it rides per-call via `LLMProvider.streamMessage(extraHeaders=)`.
 *
 * [OpenCodeSessionHeader.headersFor] no-ops for every non-OpenCode base URL
 * and for draft placeholder ids (`__new__…`); the persisted id
 * ([ChatViewModel.realSessionId]) keeps cross-turn prompt-cache hits alive.
 * Extracted from the agent loop so the loop body stays under its ceiling.
 *
 * [GH#221] 一次请求实际要带的额外请求头 = 用户为该实例配置的自定义头 +
 * OpenCode 会话头。这个文件现在是“请求头”的唯一通道，主循环、群聊、supervisor、
 * 子代理、压缩都走它，补上了旧版只有主循环携带会话头的接线缺口。
 *
 * 用户头在 ProviderFactory 构造 provider 时也会注入（覆盖聊天 + /models），
 * 这里再合并一次是因为：
 *  - OpenCode 的会话 id 是“每次对话”的数据，只有请求时才知道；
 *  - 同一通道（per-call `extraHeaders`）便于其它调用点统一取头，不必各自
 *    知道 store 的存在。
 *
 * 合并顺序：用户头在前，OpenCode 头在后 —— `x-opencode-session` 是上游路由
 * 的硬要求，用户即使配了同名头也不能把它顶掉。
 */
internal fun ChatViewModel.providerExtraHeaders(instanceId: String?): Map<String, String> {
    val id = instanceId ?: return emptyMap()
    val instance = providerRepository.instance(id) ?: return emptyMap()
    val user = ProviderHeaderStore.headersFor(instance.id)
    val opencode = OpenCodeSessionHeader.headersFor(
        instance.effectiveBaseURL,
        realSessionId.ifEmpty { sessionId },
    )
    return if (user.isEmpty()) opencode else user + opencode
}

/** 当前会话主模型的额外请求头。 */
internal fun ChatViewModel.requestExtraHeaders(): Map<String, String> {
    val entryId = _activeEntryId.value ?: return emptyMap()
    val entry = providerRepository.config.value.modelEntries
        .find { it.id == entryId } ?: return emptyMap()
    return providerExtraHeaders(entry.providerInstanceId)
}

/** [GH#221] 当前主模型所属 provider 实例 id（压缩等旁路请求复用同一条通道）。 */
internal fun ChatViewModel.currentProviderInstanceId(): String? {
    val entryId = _activeEntryId.value ?: return null
    return providerRepository.config.value.modelEntries
        .find { it.id == entryId }?.providerInstanceId
}
