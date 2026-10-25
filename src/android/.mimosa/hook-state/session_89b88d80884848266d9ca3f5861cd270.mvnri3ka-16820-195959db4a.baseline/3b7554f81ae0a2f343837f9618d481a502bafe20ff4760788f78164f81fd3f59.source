package com.openminis.app.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * In-memory projection of SecurityGate denials / user rejections for the chat
 * status strip. Not persisted; next to [com.openminis.app.service.ApprovalGate]
 * pending cards.
 */
data class InterceptEvent(
    val id: String,
    val toolName: String,
    val kind: Kind,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Kind { DENIED, REJECTED }
}

object InterceptFeedback {
    private const val MAX = 8

    private val _events = MutableStateFlow<List<InterceptEvent>>(emptyList())
    val events: StateFlow<List<InterceptEvent>> = _events.asStateFlow()

    fun publishDenied(toolName: String, reason: String) =
        publish(InterceptEvent.Kind.DENIED, toolName, reason)

    fun publishRejected(toolName: String, reason: String) =
        publish(InterceptEvent.Kind.REJECTED, toolName, reason)

    fun dismiss(id: String) {
        _events.value = _events.value.filterNot { it.id == id }
    }

    fun clear() {
        _events.value = emptyList()
    }

    private fun publish(kind: InterceptEvent.Kind, toolName: String, reason: String) {
        val event = InterceptEvent(
            id = UUID.randomUUID().toString(),
            toolName = toolName,
            kind = kind,
            reason = reason.trim().ifBlank { kind.name },
        )
        _events.value = (_events.value + event).takeLast(MAX)
    }
}
