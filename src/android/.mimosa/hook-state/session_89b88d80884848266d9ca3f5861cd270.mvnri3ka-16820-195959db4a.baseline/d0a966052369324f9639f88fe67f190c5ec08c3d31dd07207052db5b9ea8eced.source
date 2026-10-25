package com.openminis.app.ui.chat

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Collections
import java.util.LinkedHashMap

/**
 * StateFlow boundary for UI state that may be assembled with mutable lists.
 * Every published value is copied before it reaches collectors, so a caller
 * can keep mutating its working list without changing an already-published
 * snapshot or invalidating a consumer's subList view.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class SnapshotMutableStateFlow<T> private constructor(
    private val snapshot: (T) -> T,
    private val delegate: MutableStateFlow<T>,
) : MutableStateFlow<T> by delegate {
    constructor(initialValue: T, snapshot: (T) -> T) : this(
        snapshot,
        MutableStateFlow(snapshot(initialValue)),
    )

    override var value: T
        get() = delegate.value
        set(value) {
            delegate.value = snapshot(value)
        }

    override suspend fun emit(value: T) {
        delegate.emit(snapshot(value))
    }

    override fun tryEmit(value: T): Boolean = delegate.tryEmit(snapshot(value))

    override fun compareAndSet(expect: T, update: T): Boolean =
        delegate.compareAndSet(expect, snapshot(update))
}

private fun <T> immutableList(values: Collection<T>): List<T> {
    if (values.isEmpty()) return emptyList()
    return Collections.unmodifiableList(ArrayList(values))
}

internal fun snapshotAssistantBlocks(values: Collection<AssistantBlock>): List<AssistantBlock> =
    immutableList(values)

internal fun snapshotChatMessage(message: ChatMessage): ChatMessage = message.copy(
    imageUris = immutableList(message.imageUris),
    attachmentNames = immutableList(message.attachmentNames),
    attachmentUris = immutableList(message.attachmentUris),
    toolBlocks = snapshotAssistantBlocks(message.toolBlocks),
    sourceDbIds = immutableList(message.sourceDbIds),
)

internal fun snapshotChatMessages(values: Collection<ChatMessage>): List<ChatMessage> {
    if (values.isEmpty()) return emptyList()
    val copied = ArrayList<ChatMessage>(values.size)
    values.forEach { copied += snapshotChatMessage(it) }
    return Collections.unmodifiableList(copied)
}

internal fun snapshotStreamingDelta(delta: StreamingDelta): StreamingDelta = delta.copy(
    toolBlocks = snapshotAssistantBlocks(delta.toolBlocks),
)

internal fun snapshotStreamingDeltas(
    values: Map<String, StreamingDelta>,
): Map<String, StreamingDelta> {
    if (values.isEmpty()) return emptyMap()
    val copied = LinkedHashMap<String, StreamingDelta>(values.size)
    values.forEach { (id, delta) -> copied[id] = snapshotStreamingDelta(delta) }
    return Collections.unmodifiableMap(copied)
}
