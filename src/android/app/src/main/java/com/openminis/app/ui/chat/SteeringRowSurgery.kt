package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.update

/**
 * [T-steering-withdraw-hint] A steering offer materialises TWO transcript
 * rows: the queued placeholder bubble and the ephemeral "queued" system-info
 * hint. Both are derived from the entry id, so removing either form is a pure
 * list operation — and it must remove BOTH. Removing only the bubble left the
 * hint announcing a queued message that no longer existed, which is the
 * reported "删除排队消息后排队提示还在".
 *
 * [T-queued-prompt-teardown] Every path that abandons a queued prompt has to
 * go through here. The steering entry does NOT live in `_promptQueue`, so the
 * old per-caller `_promptQueue.filterNot { ... }` snippets silently missed it:
 * the entry stayed queued, and the next run's turn-0 drain persisted the text
 * the user had just deleted ("删了又回来").
 */
internal fun steeringRowIds(promptId: String): Set<String> {
 val entry = SteeringEntry(promptId, "")
 return setOf(entry.placeholderMessageId, entry.sysinfoMessageId)
}

/** The transcript with every row [promptId] owns removed. */
internal fun withoutSteeringRows(
 messages: List<ChatMessage>,
 promptId: String,
): List<ChatMessage> {
 val ids = steeringRowIds(promptId)
 return messages.filterNot { it.id in ids }
}

/** Drop a queued prompt entirely: its queue entry, its steering entry, and both rows. */
internal fun ChatViewModel.discardQueuedPrompt(promptId: String) {
 _promptQueue.update { queue -> queue.filterNot { it.id == promptId } }
 if (promptId.startsWith("steer_")) steeringState().queue.remove(promptId)
 _messages.value = withoutSteeringRows(_messages.value, promptId)
 .filterNot { it.queuedPromptId == promptId }
}

/**
 * Drop the queue bookkeeping and the "queued" hint row but KEEP the
 * placeholder bubble — the retry path re-sends that text, so the bubble stays
 * and only stops claiming to be queued.
 */
internal fun ChatViewModel.detachQueuedPrompt(promptId: String) {
 _promptQueue.update { queue -> queue.filterNot { it.id == promptId } }
 if (promptId.startsWith("steer_")) steeringState().queue.remove(promptId)
 val hintId = SteeringEntry(promptId, "").sysinfoMessageId
 _messages.value = _messages.value.filterNot { it.id == hintId }
}

/**
 * Abandon every queued prompt, steering included. Used by the teardown paths
 * (clear-chat, group-chat close, resume-that-found-no-provider) that used to
 * clear only `_promptQueue` and leave the steering hint rows behind.
 */
internal fun ChatViewModel.discardAllQueuedPrompts() {
 val steeringIds = steeringState().queue.drainAll().map { it.id }
 val promptIds = _promptQueue.value.map { it.id }
 _promptQueue.update { emptyList() }
 var messages = _messages.value
 for (pid in promptIds + steeringIds) messages = withoutSteeringRows(messages, pid)
 _messages.value = messages.filterNot { it.isQueued }
}
