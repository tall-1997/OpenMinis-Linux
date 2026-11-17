package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-harness-note-fold] Pins the whole-sequence fold semantics:
 * - notes ANYWHERE in the sequence (head, mid, tail) must not survive as
 *   independent "the user spoke again" turns;
 * - a mid/tail note folds onto the nearest preceding non-harness USER
 *   message (real user turn or tool-result carrier);
 * - leading notes (no preceding user turn) collapse into the first
 *   following real user turn's prefix;
 * - notes wrapped in the `<system-reminder>` framing (the production
 *   format, see commitHiddenUserNote) are recognized — the startsWith
 *   regression that made the original fold a no-op is pinned here;
 * - steering rounds (goal continuation etc.) are NOT folded.
 */
class HarnessNoteFoldTest {

    private fun user(text: String, vararg parts: AgentContentPart) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = text,
        contentParts = if (parts.isEmpty()) listOf(AgentContentPart.Text(text)) else parts.toList(),
    )

    private fun assistant(text: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = text,
        contentParts = listOf(AgentContentPart.Text(text)),
    )

    /** Production framing: every committed note goes through commitHiddenUserNote. */
    private fun framedNote(inner: String) =
        user("<system-reminder>\n$inner\n</system-reminder>")

    private fun toolCarrier(id: String, result: String) = user(
        "",
        AgentContentPart.ToolResult(id = id, name = "shell_execute", content = result),
    )

    @Test
    fun `head note is recognized through the system-reminder framing`() {
        val history = listOf(
            framedNote(HarnessNoteMarkers.RECALL),
            user("do the task"),
        )
        val folded = foldHarnessNotes(history)
        assertEquals(1, folded.size)
        assertTrue(folded[0].content.contains(HarnessNoteMarkers.RECALL))
        assertTrue(folded[0].content.contains("do the task"))
    }

    @Test
    fun `mid-sequence note after a tool carrier rides the carrier`() {
        val history = listOf(
            user("do the task"),
            assistant("calling tool"),
            toolCarrier("call_1", "ok"),
            framedNote(HarnessNoteMarkers.GOAL),
            assistant("next"),
            toolCarrier("call_2", "done"),
        )
        val folded = foldHarnessNotes(history)
        assertEquals(5, folded.size)
        // The note must be appended onto the tool-result carrier, not sent standalone.
        val carrier = folded[2]
        assertTrue(carrier.content.contains(HarnessNoteMarkers.GOAL))
        // Tool blocks stay in their own message.
        assertTrue(carrier.contentParts.any { it is AgentContentPart.ToolResult })
        // No STANDALONE harness note remains — the structural carrier that
        // absorbed the note is text+ToolResult, so isUserHarnessNote (not the
        // plain content check) is the correct predicate here.
        assertTrue(folded.none { HarnessNoteMarkers.isUserHarnessNote(it) })
    }

    @Test
    fun `mid-sequence note after a real user turn rides that turn`() {
        val history = listOf(
            user("first"),
            assistant("reply"),
            user("second"),
            framedNote(HarnessNoteMarkers.STATE),
        )
        val folded = foldHarnessNotes(history)
        assertEquals(3, folded.size)
        assertTrue(folded[2].content.contains("second"))
        assertTrue(folded[2].content.contains(SessionStateSnapshot.MARKER))
    }

    @Test
    fun `consecutive notes fold onto the same preceding turn in order`() {
        val history = listOf(
            user("task"),
            assistant("work"),
            toolCarrier("call_1", "ok"),
            framedNote(HarnessNoteMarkers.GOAL),
            framedNote(HarnessNoteMarkers.RECALL),
        )
        val folded = foldHarnessNotes(history)
        assertEquals(3, folded.size)
        val carrier = folded[2]
        val goalPos = carrier.content.indexOf(HarnessNoteMarkers.GOAL)
        val recallPos = carrier.content.indexOf(HarnessNoteMarkers.RECALL)
        assertTrue("both notes present", goalPos >= 0 && recallPos > goalPos)
    }

    @Test
    fun `detached summary prefix does not break marker recognition`() {
        // The v2 path prepends `<context-summary>` onto the first post-anchor
        // user message. If that message is a harness note, the old startsWith
        // match failed — the fold must still recognize it.
        val history = listOf(
            user("<context-summary>\nold summary\n</context-summary>\n\n${HarnessNoteMarkers.GOAL} in this conversation."),
        )
        assertTrue(HarnessNoteMarkers.isHarnessNote(history[0].content))
    }

    @Test
    fun `tail notes with no following user turn flush as a standalone user turn`() {
        val history = listOf(
            assistant("final answer"),
            framedNote(HarnessNoteMarkers.GOAL),
        )
        val folded = foldHarnessNotes(history)
        assertEquals(2, folded.size)
        assertEquals(LLMMessage.Role.USER, folded[1].role)
        assertTrue(folded[1].content.contains(HarnessNoteMarkers.GOAL))
    }

    @Test
    fun `goal continuation round is NOT folded`() {
        // Steering rounds after an assistant turn are structural: the model
        // must respond to them. They are deliberately outside the marker set.
        val history = listOf(
            user("task"),
            assistant("done for now"),
            user("<system-reminder source=\"goal-continuation\">\n<goal_context>...</goal_context>\n</system-reminder>"),
        )
        val folded = foldHarnessNotes(history)
        assertEquals(3, folded.size)
    }

    @Test
    fun `history without notes is returned untouched`() {
        val history = listOf(
            user("hi"),
            assistant("hello"),
            toolCarrier("call_1", "ok"),
        )
        val folded = foldHarnessNotes(history)
        assertEquals(history, folded)
    }

    @Test
    fun `no-reply head does not collide with markers`() {
        // Legacy #339 rows carry the head (the constant now lives in
        // PreviewText, the single stripping authority); carrier/eviction
        // detection must keep working with it in place.
        val content = "${com.openminis.app.data.repository.PreviewText.HOST_NOTE_NO_REPLY_HEAD}\n<system-reminder>\n${HarnessNoteMarkers.RECALL}\n</system-reminder>"
        assertTrue(HarnessNoteMarkers.isHarnessNote(content))
        val folded = foldHarnessNotes(listOf(user("task"), assistant("a"), user(content)))
        assertEquals(2, folded.size)
        assertTrue(folded[0].content.contains(com.openminis.app.data.repository.PreviewText.HOST_NOTE_NO_REPLY_HEAD))
    }

    @Test
    fun `a real user message quoting a marker mid-sentence is NOT folded`() {
        // [T-harness-note-leading-match] The old CONTAINS match classified a
        // question that merely quoted a marker ("what does [working memory]
        // in your prompt do?") as a harness note and folded it into the
        // previous turn — the user's question lost its turn boundary. The
        // match is leading-only now: framing artifacts may precede the
        // marker, real prose may not.
        val quoted = user("what does [working memory] in your prompt do? and why do I see RETRY REQUESTED by the user sometimes?")
        val history = listOf(
            user("earlier question"),
            assistant("answer"),
            quoted,
        )
        val folded = foldHarnessNotes(history)
        assertEquals(3, folded.size)
        assertEquals(quoted.content, folded[2].content)
        assertTrue(!HarnessNoteMarkers.isUserHarnessNote(quoted))
    }

    @Test
    fun `a note preceded by framing artifacts still folds`() {
        // [T-harness-note-leading-match] The v2 compact path prepends a
        // <context-summary> block; the no-reply head precedes the reminder in
        // legacy rows. Both artifacts strip before the leading marker check.
        val withSummary = user("<context-summary>\nold summary\n</context-summary>\n\n<system-reminder>\n${HarnessNoteMarkers.GOAL} text\n</system-reminder>")
        val withHead = user(
            com.openminis.app.data.repository.PreviewText.HOST_NOTE_NO_REPLY_HEAD +
                "\n<system-reminder>\n${HarnessNoteMarkers.RECALL} text\n</system-reminder>",
        )
        val history = listOf(user("task"), assistant("a"), withSummary, withHead)
        val folded = foldHarnessNotes(history)
        assertEquals(2, folded.size)
        // Both notes fold forward onto the LAST user turn (folded[0]); the
        // assistant row is untouched.
        assertTrue(folded[0].content.contains(HarnessNoteMarkers.GOAL))
        assertTrue(folded[0].content.contains(HarnessNoteMarkers.RECALL))
        assertTrue(folded[1].content == "a")
    }

    @Test
    fun `fold-forward onto a parts-carrying carrier syncs contentParts`() {
        // [T-user-text-prefix-parts-sync] The wire builders serialize
        // contentParts when non-empty and never read `content`, so a
        // content-only fold silently drops the note for tool-result
        // carriers (the dominant fold target). The appended note must
        // appear as a Text part too.
        val history = listOf(
            user("do the task"),
            assistant("calling tool"),
            toolCarrier("call_1", "ok"),
            framedNote(HarnessNoteMarkers.GOAL),
        )
        val folded = foldHarnessNotes(history)
        val carrier = folded[2]
        val textParts = carrier.contentParts.filterIsInstance<AgentContentPart.Text>()
        assertTrue(
            "folded note must ride the parts as well as content",
            textParts.any { it.text.contains(HarnessNoteMarkers.GOAL) },
        )
        assertTrue(carrier.content.contains(HarnessNoteMarkers.GOAL))
    }

    @Test
    fun `leading note flushed into a parts-carrying user turn syncs contentParts`() {
        // [T-user-text-prefix-parts-sync] Leading notes are prepended onto
        // the first real user turn — which always carries parts on the
        // production send path. The prefix must become a Text part.
        val note = framedNote(HarnessNoteMarkers.RECALL)
        val realSend = user(
            "do the task",
            AgentContentPart.Text("do the task"),
        )
        val folded = foldHarnessNotes(listOf(note, realSend))
        assertEquals(1, folded.size)
        val textParts = folded[0].contentParts.filterIsInstance<AgentContentPart.Text>()
        assertTrue(
            "prefix must ride the parts as well as content",
            textParts.first().text.contains(HarnessNoteMarkers.RECALL),
        )
        assertTrue(folded[0].content.contains(HarnessNoteMarkers.RECALL))
        assertTrue(folded[0].content.contains("do the task"))
    }
}
