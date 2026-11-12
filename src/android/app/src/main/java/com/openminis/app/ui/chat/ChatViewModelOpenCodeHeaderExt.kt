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
 *
 * [GH#221] 一次请求实际要带的额外请求头 = 用户为该实例配置的自定义头 +
 * OpenCode 会话头。这个文件现在是"请求头"的唯一通道，主循环、群聊、supervisor、
 * 子代理、压缩都走它，补上了旧版只有主循环携带会话头的接线缺口。
 *
 * 用户头在 ProviderFactory 构造 provider 时也会注入（覆盖聊天 + /models），
 * 这里再合并一次是因为：
 *  - OpenCode 的会话 id 是"每次对话"的数据，只有请求时才知道；
 *  - 同一通道（per-call `extraHeaders`）便于其它调用点统一取头，不必各自
 *    知道 store 的存在。
 *
 * [GH#347] 自定义 OpenAI 兼容端点要显式 opt-in 才会收到同一会话头：把 base
 * URL 指向 OpenCode 兼容中转（或自建网关）的用户会命中
 * `400 missing x-opencode-session` 路由规则，但“任意第三方 relay”默认不该收到
 * 稳定的每对话标识符。开关存在 ProviderHeaderStore（见 Provider detail 的
 * OpenCode session header 一项），默认关闭；OpenCode 官方端点（`*.opencode.ai`）
 * 仍由 [OpenCodeSessionHeader.headersFor] 按 host 自动发送，不受开关影响。
 *
 * 合并顺序：用户头在前，OpenCode 头在后 —— `x-opencode-session` 是上游路由
 * 的硬要求，用户即使配了同名头也不能把它顶掉。
 *
 * [F2 GH#347] 官方 host 兜底已抽到
 * [OpenCodeSessionHeader.sessionHeaders]，`model_use` 与本文件共用；不要再在
 * 调用点手写 `customEndpointHeadersFor(...).ifEmpty { headersFor(...) }`，否则
 * 两条路径会再次漂移（model_use 缺兜底就是 400 的根因）。
 */
internal fun ChatViewModel.providerExtraHeaders(instanceId: String?): Map<String, String> {
    val id = instanceId ?: return emptyMap()
    val instance = providerRepository.instance(id) ?: return emptyMap()
    val user = ProviderHeaderStore.headersFor(instance.id)
    val persistedSessionId = realSessionId.ifEmpty { sessionId }
    // [F2 GH#347] One shared helper for both lanes: custom endpoint (opt-in) with
    // the official `*.opencode.ai` host as fallback. Keeping this call and
    // ModelUseOffloadHandler's call on the SAME function is the whole point —
    // the chat path had the fallback and model_use did not, which reproduced 400.
    val opencode = OpenCodeSessionHeader.sessionHeaders(
        instance.effectiveBaseURL,
        persistedSessionId,
        ProviderHeaderStore.openCodeSessionEnabled(instance.id),
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
