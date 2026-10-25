package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-stale-sticker] The inline error banner describes the CURRENT tail
 * state, so buildFlatChatItems emits [FlatChatItem.AssistantError] for the
 * tail assistant row only. Device capture (2026-10-24): a partial reply
 * kept its red "interrupted" sticker while the resumed run was already
 * streaming below it — the sticker belonged to an earlier row that every
 * tail-scoped clear had lost track of. The state-side counterpart is
 * sweepStaleInlineErrors(); this suite pins the render invariant that
 * holds even when some future race re-strands an error after the sweep.
 */
class ChatFlatItemsTailErrorTest {

    private fun msg(
        id: String,
        role: String,
        text: String = "",
        error: String? = null,
        isStreaming: Boolean = false,
    ) = ChatMessage(id = id, role = role, content = text, error = error, isStreaming = isStreaming)

    private fun errorsIn(items: List<FlatChatItem>) =
        items.filterIsInstance<FlatChatItem.AssistantError>()

    @Test
    fun strandedErrorOnAnEarlierAssistantRowIsNeverEmitted() {
        val items = buildFlatChatItems(
            listOf(
                msg("u1", "user", "prompt"),
                msg("a1", "assistant", "partial reply", error = "interrupted"),
                msg("b1", "assistant", "resumed run", isStreaming = true),
            ),
        )
        assertTrue(
            "the stranded sticker must not render beside live output",
            errorsIn(items).isEmpty(),
        )
    }

    @Test
    fun tailAssistantErrorIsEmitted() {
        val items = buildFlatChatItems(
            listOf(
                msg("u1", "user", "prompt"),
                msg("a1", "assistant", "failed reply", error = "401 unauthorized"),
            ),
        )
        val errors = errorsIn(items)
        assertEquals(1, errors.size)
        assertEquals("a1", errors.single().messageId)
        assertEquals("401 unauthorized", errors.single().error)
    }

    @Test
    fun tailErrorStillRendersWhileThatRowStreams() {
        // The transient retry countdown banner legitimately lives on the
        // streaming tail row — the gate must not suppress it.
        val items = buildFlatChatItems(
            listOf(
                msg("u1", "user", "prompt"),
                msg("a1", "assistant", "partial", error = "retrying in 3s", isStreaming = true),
            ),
        )
        assertEquals(1, errorsIn(items).size)
    }

    @Test
    fun tailMeansLastAssistantEvenAfterATrailingUserRow() {
        // The user queued a follow-up under a failed reply: a1 is still the
        // last assistant row, its banner stays until the next generation
        // starts (sweepStaleInlineErrors clears it then).
        val items = buildFlatChatItems(
            listOf(
                msg("a1", "assistant", "failed", error = "boom"),
                msg("u2", "user", "follow-up"),
            ),
        )
        assertEquals("a1", errorsIn(items).single().messageId)
    }

    @Test
    fun splitBuildEmitsTheSameErrorsAsAFullBuild() {
        val messages = listOf(
            msg("u1", "user", "first"),
            msg("a1", "assistant", "stranded", error = "old interrupt"),
            msg("u2", "user", "second"),
            msg("a2", "assistant", "tail", error = "live failure"),
        )
        val full = buildFlatChatItems(messages, foldAiProcess = true)
        val suffix = buildFlatChatItems(
            messages,
            fromIndex = 2,
            foldAiProcess = true,
            processCardBoundary = 2,
        )
        val fullIds = errorsIn(full).map { it.messageId }
        val suffixIds = errorsIn(suffix).map { it.messageId }
        assertEquals(listOf("a2"), fullIds)
        assertEquals(fullIds, suffixIds)
    }

    @Test
    fun noAssistantRowsMeansNoErrorBanner() {
        val items = buildFlatChatItems(
            listOf(msg("u1", "user", "hello", error = "ignored on user rows")),
        )
        assertTrue(errorsIn(items).isEmpty())
    }
}
