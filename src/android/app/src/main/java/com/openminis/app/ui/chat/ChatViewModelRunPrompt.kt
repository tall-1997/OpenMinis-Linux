package com.openminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rebuild the memory-delivery prompt after a queued user turn becomes the latest user message. */
internal suspend fun ChatViewModel.refreshRunPromptForInjectedTurn(
    current: MemoryDeliveryPrompt?,
    expectedSessionId: String? = null,
): MemoryDeliveryPrompt? = rebuildOwnedRunPrompt(current, expectedSessionId ?: activeSessionId, { activeSessionId }) {
    // The rebuilt prompt carries its own prefix and memory delivery metadata.
    // Do not publish VM-wide prompt diagnostics from an in-flight rebuild.
    buildMemoryDeliveryPrompt(publish = false)
}

internal suspend fun rebuildOwnedRunPrompt(
    current: MemoryDeliveryPrompt?,
    expectedSessionId: String,
    sessionId: () -> String,
    build: suspend () -> MemoryDeliveryPrompt,
): MemoryDeliveryPrompt? {
    if (sessionId() != expectedSessionId) return current
    val rebuilt = try {
        withContext(Dispatchers.IO) {
            if (sessionId() == expectedSessionId) build() else null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    return if (sessionId() == expectedSessionId) rebuilt ?: current else current
}
