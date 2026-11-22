package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-steering-withdraw-hint] Removing a queued steering message must remove
 * EVERY row the offer created, not just the bubble.
 *
 * The reported bug: send a message while the agent runs (it becomes a steering
 * offer, which materialises a placeholder bubble plus an ephemeral "queued"
 * system-info row), then delete the queued message. The bubble vanished, the
 * hint stayed forever with nothing left to deliver.
 */
class SteeringRowSurgeryTest {

 private fun bubble(id: String, role: String, queuedId: String?) = ChatMessage(
 id = id,
 role = role,
 content = "text of $id",
 isQueued = queuedId != null,
 queuedPromptId = queuedId,
 )

 @Test
 fun withdrawingAQueuedSteeringMessageDropsBothItsRows() {
        val pid = "steer_1234_5678"
        val entry = SteeringEntry(pid, "text")
        val messages = listOf(
            bubble("u1", "user", null),
            bubble(entry.sysinfoMessageId, "system", null),
            bubble(entry.placeholderMessageId, "user", pid),
            bubble("a1", "assistant", null),
        )
        val kept = withoutSteeringRows(messages, pid)
        assertEquals("only the two steering rows go", listOf("u1", "a1"), kept.map { it.id })
        assertFalse("the queued hint must not survive the withdrawal", kept.any { it.id == entry.sysinfoMessageId })
    }

 @Test
 fun theTwoRowIdsAreDerivedFromThePromptId() {
        val ids = steeringRowIds("steer_9_9")
        assertEquals(2, ids.size)
        assertTrue(ids.all { it.contains("steer_9_9") })
        assertEquals(
            "a different prompt keeps its own ids",
            2,
            steeringRowIds("steer_9_9_other").count { it !in ids },
        )
    }

 @Test
 fun aPromptQueueBubbleIsUntouchedBySteeringSurgery() {
        val messages = listOf(bubble("queued_msg_queued_1", "user", "queued_1"))
        assertEquals(messages, withoutSteeringRows(messages, "steer_1_1"))
    }
}
