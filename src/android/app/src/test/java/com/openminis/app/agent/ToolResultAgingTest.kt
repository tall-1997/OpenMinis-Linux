package com.openminis.app.agent

import com.openminis.app.data.ContextSaverPrefs
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tool-result-aging] Contracts for the history-side token saver.
 *
 * The invariants under test are the ones a provider serializer depends on:
 * nothing is removed, tool_use ⇄ tool_result ids stay pairable, the pass is
 * idempotent, and folding is always subtractive. A regression in any of them
 * shows up as a 400 from Anthropic/OpenAI rather than as a visible bug, so they
 * are pinned here instead of in an integration test.
 */
class ToolResultAgingTest {

    private fun result(
        id: String,
        chars: Int,
        name: String = "shell_execute",
        isError: Boolean = false,
        filler: Char = 'x',
    ): AgentContentPart.ToolResult = AgentContentPart.ToolResult(
        id = id,
        name = name,
        content = filler.toString().repeat(chars),
        isError = isError,
    )

    /** One assistant message carrying [parts], the way the loop stores them. */
    private fun msg(vararg parts: AgentContentPart): LLMMessage =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = parts.toList())

    private fun contents(messages: List<LLMMessage>): List<String> =
        messages.flatMap { m -> m.contentParts.filterIsInstance<AgentContentPart.ToolResult>().map { it.content } }

    // ---------------------------------------------------------------- gating

    @Test
    fun `empty history is a no-op sharing the same instance`() {
        val history = emptyList<LLMMessage>()
        val plan = ToolResultAging.age(history)
        assertFalse(plan.changed)
        assertSame(history, plan.messages)
        assertEquals(0, plan.scannedResults)
    }

    @Test
    fun `history inside the keep window is returned untouched`() {
        val history = listOf(msg(result("a", 5_000)), msg(result("b", 5_000)))
        val plan = ToolResultAging.age(history, keepRecent = 6, minChars = 1_200)
        assertFalse(plan.changed)
        assertSame(history, plan.messages)
        assertEquals(2, plan.scannedResults)
    }

    @Test
    fun `only results older than the keep window are folded`() {
        // 4 results, keep the newest 2 → a and b fold, c and d survive verbatim.
        val history = listOf(
            msg(result("a", 4_000)),
            msg(result("b", 4_000)),
            msg(result("c", 4_000)),
            msg(result("d", 4_000)),
        )
        val plan = ToolResultAging.age(history, keepRecent = 2, minChars = 1_200)
        assertEquals(2, plan.agedCount)
        val all = contents(plan.messages)
        assertTrue(ToolResultAging.isAged(all[0]))
        assertTrue(ToolResultAging.isAged(all[1]))
        assertEquals("x".repeat(4_000), all[2])
        assertEquals("x".repeat(4_000), all[3])
    }

    @Test
    fun `results under the size floor are left alone and do not consume a slot`() {
        // Oldest is tiny (not worth folding), then 2 big ones with keepRecent=1.
        val history = listOf(
            msg(result("tiny", 300)),
            msg(result("big1", 4_000)),
            msg(result("big2", 4_000)),
        )
        val plan = ToolResultAging.age(history, keepRecent = 1, minChars = 1_200)
        val all = contents(plan.messages)
        assertEquals("x".repeat(300), all[0])
        assertTrue(ToolResultAging.isAged(all[1]))
        assertEquals("x".repeat(4_000), all[2])
        assertEquals(1, plan.agedCount)
    }

    // ---------------------------------------------------------- idempotency

    @Test
    fun `second pass over aged history is a no-op`() {
        val history = (1..4).map { msg(result("r$it", 4_000)) }
        val first = ToolResultAging.age(history, keepRecent = 1, minChars = 1_200)
        assertEquals(3, first.agedCount)

        val second = ToolResultAging.age(first.messages, keepRecent = 1, minChars = 1_200)
        assertEquals(0, second.agedCount)
        assertSame(first.messages, second.messages)
    }

    @Test
    fun `already aged parts do not reset the keep window`() {
        // 3 aged + 1 fresh, keepRecent = 1 → the fresh one is inside the window
        // and must survive; the pass must not re-count the aged ones as "live".
        val aged = ToolResultAging.placeholder("shell_execute", "y".repeat(5_000))
        val history = listOf(
            msg(AgentContentPart.ToolResult(id = "o1", name = "shell_execute", content = aged)),
            msg(AgentContentPart.ToolResult(id = "o2", name = "shell_execute", content = aged)),
            msg(result("fresh", 5_000)),
        )
        val plan = ToolResultAging.age(history, keepRecent = 1, minChars = 1_200)
        assertEquals(0, plan.agedCount)
        assertEquals(1, plan.scannedResults) // only the fresh part is a candidate
        assertEquals("x".repeat(5_000), contents(plan.messages)[2])
    }

    // ------------------------------------------------------ structure safety

    @Test
    fun `folding preserves ids names error flags and pairing`() {
        val toolUse = AgentContentPart.ToolUse(id = "call_1", name = "shell_execute", input = JSONObject("""{"command":"ls"}"""))
        val err = AgentContentPart.ToolResult(
            id = "call_1",
            name = "shell_execute",
            content = "boom\n".repeat(2_000),
            isError = true,
        )
        val history = listOf(msg(toolUse, err), msg(result("call_2", 4_000)), msg(result("call_3", 4_000)))
        val plan = ToolResultAging.age(history, keepRecent = 1, minChars = 1_200)
        // 3 live results, keep the newest 1 → both older ones fold.
        assertEquals(2, plan.agedCount)

        val folded = plan.messages[0].contentParts[1] as AgentContentPart.ToolResult
        assertEquals("call_1", folded.id)
        assertEquals("shell_execute", folded.name)
        assertTrue(folded.isError)
        assertTrue(ToolResultAging.isAged(folded.content))

        // The tool_use that pairs with it is untouched and still first.
        val use = plan.messages[0].contentParts[0] as AgentContentPart.ToolUse
        assertEquals("call_1", use.id)
        assertEquals("shell_execute", use.name)
        // Message and part counts are identical — nothing removed.
        assertEquals(history.size, plan.messages.size)
        assertEquals(
            history.sumOf { it.contentParts.size },
            plan.messages.sumOf { it.contentParts.size },
        )
    }

    @Test
    fun `image bytes and linux path survive folding`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val withImage = AgentContentPart.ToolResult(
            id = "shot",
            name = "screenshot",
            content = "z".repeat(6_000),
            imageData = bytes,
            imageMimeType = "image/png",
            imageLinuxPath = "/var/minis/attachments/shot.png",
        )
        val plan = ToolResultAging.age(
            listOf(msg(withImage), msg(result("keep", 4_000))),
            keepRecent = 1,
            minChars = 1_200,
        )
        val folded = plan.messages[0].contentParts[0] as AgentContentPart.ToolResult
        assertTrue(folded.imageData!!.contentEquals(bytes))
        assertEquals("image/png", folded.imageMimeType)
        assertEquals("/var/minis/attachments/shot.png", folded.imageLinuxPath)
    }

    // -------------------------------------------------------------- content

    @Test
    fun `placeholder keeps a readable path so the model can page it back`() {
        val spill = "/var/minis/workspace/tool-spill/tool_abc123.txt"
        val body = "line1\nline2\n" + "q".repeat(6_000) + "\n[full output: $spill]\n"
        val text = ToolResultAging.placeholder("shell_execute", body)
        assertTrue(text.startsWith(ToolResultAging.MARKER))
        assertTrue(text.contains(spill))
        assertTrue(text.contains("full output: $spill"))
        // Folded text is strictly shorter than the original.
        assertTrue(text.length < body.length)
    }

    @Test
    fun `placeholder without a path tells the model to re-run the tool`() {
        val text = ToolResultAging.placeholder("grep", "m".repeat(3_000))
        assertFalse(text.contains("/var/minis/"))
        assertTrue(text.contains("re-run this tool"))
    }

    @Test
    fun `placeholder wording carries no CJK so the ratchet stays clean`() {
        // Tool-result bodies are model-facing, not UI copy: the repo's
        // hardcoded-CJK ratchet must never see one. Also pins the numbers the
        // model uses to decide "is this worth re-reading".
        val text = ToolResultAging.placeholder("shell_execute", "z".repeat(4_321))
        assertFalse(text.any { it.code in 0x3000..0x303F || it.code in 0x4E00..0x9FFF })
        assertTrue(text.contains("4321 chars folded to save context"))
    }

    @Test
    fun `error placeholders are labelled and keep a longer head`() {
        val body = "Exception: connection reset by peer\n" + "e".repeat(3_000)
        val text = ToolResultAging.placeholder("shell_execute", body, isError = true)
        assertTrue(text.contains("(error)"))
        assertTrue(text.contains("connection reset"))
        // Longer preview than a normal result gets (400 vs 160 chars).
        assertTrue(text.length > ToolResultAging.placeholder("shell_execute", body).length)
    }

    @Test
    fun `folding is never additive`() {
        // A 1.3k result produces a placeholder of a few hundred chars, so it
        // folds; a result barely over the floor must not grow. Force the worst
        // case by asking for a minChars of 0 and a tiny body.
        val history = listOf(msg(result("tiny", 1)), msg(result("keep", 4_000)))
        val plan = ToolResultAging.age(history, keepRecent = 1, minChars = 0)
        // Placeholder is longer than "x" → subtract-only guard keeps it verbatim.
        assertEquals("x", contents(plan.messages)[0])
        assertEquals(0, plan.agedCount)
    }

    @Test
    fun `headline collapses whitespace and stays surrogate safe`() {
        val messy = "a\n\n\n  b\t\tc"
        assertEquals("a b c", ToolResultAging.headlineOf(messy))

        // Emoji at the cut boundary must not split a surrogate pair.
        val emoji = "😘".repeat(200)
        val cut = ToolResultAging.headlineOf(emoji, limit = 5)
        assertTrue(cut.endsWith("…"))
        // Valid UTF-16: no lone high surrogate at the end before the ellipsis.
        val body = cut.dropLast(1)
        assertFalse(Character.isHighSurrogate(body.last()))
    }

    // ------------------------------------------------------------- counting

    @Test
    fun `totalChars sums text parts and tool payloads`() {
        val history = listOf(
            msg(AgentContentPart.Text("abcd"), result("t", 10)),
            LLMMessage(role = LLMMessage.Role.USER, content = "12345"),
        )
        // 4 text + 10 tool result + 5 message content = 19
        assertEquals(19L, ToolResultAging.totalChars(history))
    }

    // ---------------------------------------------------------------- prefs

    @Test
    fun `trigger tokens follow the level and shrink for small windows`() {
        val balanced = ContextSaverPrefs.Mode.BALANCED
        // Big window: the level's own floor wins (never raised by the window).
        assertEquals(balanced.triggerTokens, ContextSaverPrefs.triggerTokens(balanced, 1_000_000))
        // Small window: 45% of it is the earlier trigger.
        assertEquals((32_000 * 0.45).toInt(), ContextSaverPrefs.triggerTokens(balanced, 32_000))
        // Unknown window: fall back to the floor.
        assertEquals(balanced.triggerTokens, ContextSaverPrefs.triggerTokens(balanced, 0))
    }

    @Test
    fun `off never triggers`() {
        val off = ContextSaverPrefs.Mode.OFF
        assertEquals(Int.MAX_VALUE, ContextSaverPrefs.triggerTokens(off, 1_000_000))
        assertEquals(Int.MAX_VALUE, ContextSaverPrefs.triggerTokens(off, 0))
    }

    @Test
    fun `unknown persisted mode falls back to balanced`() {
        assertEquals(ContextSaverPrefs.Mode.BALANCED, ContextSaverPrefs.Mode.fromId(null))
        assertEquals(ContextSaverPrefs.Mode.BALANCED, ContextSaverPrefs.Mode.fromId("garbage"))
        assertEquals(ContextSaverPrefs.Mode.AGGRESSIVE, ContextSaverPrefs.Mode.fromId("aggressive"))
    }

    @Test
    fun `aggressive folds more than balanced on the same history`() {
        val history = (1..8).map { msg(result("r$it", 1_000)) }
        val balanced = ToolResultAging.age(
            history,
            keepRecent = ContextSaverPrefs.Mode.BALANCED.keepRecent,
            minChars = ContextSaverPrefs.Mode.BALANCED.minChars,
        )
        val aggressive = ToolResultAging.age(
            history,
            keepRecent = ContextSaverPrefs.Mode.AGGRESSIVE.keepRecent,
            minChars = ContextSaverPrefs.Mode.AGGRESSIVE.minChars,
        )
        // 1_000-char results sit under BALANCED's 1_200 floor → nothing folds;
        // AGGRESSIVE's 400 floor folds all but its newest 3.
        assertEquals(0, balanced.agedCount)
        assertEquals(5, aggressive.agedCount)
    }
}
