package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.WalkBack
import com.openminis.app.ui.chat.buildDetachedCompactHistory
import com.openminis.app.ui.chat.detachedTailSize
import com.openminis.app.ui.chat.dropOrphanedToolParts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-detached-anchor] The regression under test: once a marker's
 * anchor falls out of the bounded agentHistory window, the old code threw
 * the summary away and sent the full history on every turn — a session could
 * never get back under budget. The fallback must instead deliver the summary
 * plus a verbatim tail, starting on a user turn.
 *
 * [T-compact-tail-round-boundary] The tail must also start on a COMPLETE
 * round: a tool_use/tool_result pair may never be split by the cut. A slice
 * that starts on the result carrier (or on the assistant's tool_use) feeds
 * the model a half-round; it re-issues the same tool call and the run loops.
 */
class CompactDetachedAnchorTest {

    private fun user(i: Int) = LLMMessage(role = LLMMessage.Role.USER, content = "u$i", dbMessageId = "id$i")
    private fun asst(i: Int) = LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "a$i", dbMessageId = "id${i}a")

    private fun history(n: Int): List<LLMMessage> =
        (1..n).flatMap { listOf(user(it), asst(it)) }

    private fun useMsg(id: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "",
        contentParts = listOf(AgentContentPart.ToolUse(id, "shell_execute", JSONObject())),
    )

    private fun resultMsg(id: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(AgentContentPart.ToolResult(id, "shell_execute", "ok")),
    )

    private fun toolIds(history: List<LLMMessage>): Pair<Set<String>, Set<String>> {
        val uses = HashSet<String>()
        val results = HashSet<String>()
        for (m in history) {
            for (p in m.contentParts) {
                if (p is AgentContentPart.ToolUse) uses.add(p.id)
                if (p is AgentContentPart.ToolResult) results.add(p.id)
            }
        }
        return uses to results
    }

    @Test
    fun `a short history is passed through with the summary inlined`() {
        val h = listOf(user(1), asst(1), user(2))
        val out = buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertEquals(3, out.size)
        assertEquals(LLMMessage.Role.USER, out.first().role)
        assertTrue(out.first().content.startsWith("<summary/>"))
        assertTrue(out.first().content.endsWith("u1"))
    }

    @Test
    fun `an oversized history is cut down to the tail budget`() {
        val h = history(200) // 400 entries
        val out = buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        // The cut walks back to the nearest round boundary, so it may include
        // at most the opening user turn of that round.
        assertTrue("expected a bounded tail, got ${out.size}", out.size in 60..61)
        // Tail semantics: the newest entries survive, the oldest are dropped.
        assertEquals("u200", out.last { it.role == LLMMessage.Role.USER }.content)
    }

    @Test
    fun `leading assistant turns are peeled so the first message is user`() {
        val h = listOf(asst(1), asst(2), user(3), asst(3))
        val out = buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertEquals(LLMMessage.Role.USER, out.first().role)
        assertTrue(out.first().content.startsWith("<summary/>"))
    }

    @Test
    fun `a tail with no user turn still carries the summary first`() {
        val h = listOf(asst(1), asst(2))
        val out = buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        // [T-detached-tail-pairing] Assistant-only tail: the summary becomes
        // its own user turn before the paired suffix.
        assertEquals(3, out.size)
        assertEquals(LLMMessage.Role.USER, out.first().role)
        assertEquals("<summary/>", out.first().content)
        assertEquals("a1", out[1].content)
    }

    @Test
    fun `the injected summary is strictly smaller than sending the whole history`() {
        // The point of the fix: bound what reaches the provider.
        val h = history(200)
        val out = buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertTrue(
            "expected ${out.size} sent messages vs ${h.size} in history",
            out.size < h.size,
        )
    }

    @Test
    fun `the cut never lands on a tool-result carrier`() {
        // Rounds: u1 / a1(use) / r1 / u2 / a2(use) / r2 / u3 ...
        val h = listOf(
            user(1), useMsg("call_1"), resultMsg("call_1"),
            user(2), useMsg("call_2"), resultMsg("call_2"),
            user(3),
        )
        // tailSize=3 → rawCut=4 → lands on assistant tool_use → walk back to u2 (index 3).
        val out = buildDetachedCompactHistory("<summary/>", h, tailSize = 3)
        assertEquals(LLMMessage.Role.USER, out.first().role)
        val (uses, results) = toolIds(out)
        assertEquals("every kept use must keep its result", uses, results)
    }

    @Test
    fun `the detached tail always pairs every tool round it keeps`() {
        val h = listOf(
            user(1), useMsg("call_1"), resultMsg("call_1"),
            user(2), useMsg("call_2"), resultMsg("call_2"),
            user(3), useMsg("call_3"), resultMsg("call_3"),
            user(4), useMsg("call_4"),
        )
        for (tailSize in 1..h.size) {
            val out = buildDetachedCompactHistory("<summary/>", h, tailSize = tailSize)
            // A trailing in-flight use (the last message is the assistant's
            // tool_use, no result yet) is legitimate; every OTHER round kept
            // by the cut must be complete.
            val tailInFlight = out.lastOrNull()?.let { last ->
                last.role == LLMMessage.Role.ASSISTANT &&
                    last.contentParts.any { it is AgentContentPart.ToolUse }
            } == true
            val repaired = dropOrphanedToolParts(out)
            assertEquals(
                "tailSize=$tailSize must already be pairing-clean apart from the tail in-flight use",
                out.size,
                repaired.size,
            )
            val (uses, results) = toolIds(out)
            if (!tailInFlight) {
                assertEquals("tailSize=$tailSize: uses=$uses results=$results", uses, results)
            }
        }
    }

    @Test
    fun `summary injection onto a parts-carrying user turn syncs contentParts`() {
        // [T-user-text-prefix-parts-sync] The wire builders serialize
        // contentParts when non-empty and never read `content`, so the
        // content-only injection dropped the whole summary from the wire
        // whenever the first tail row carried parts (tool-result carrier
        // or a real send). The summary must ride the parts as well.
        // [T-anthropic-toolresult-first] On a carrier the text appends AFTER
        // the tool parts (Anthropic requires tool_result first in a user
        // message) — the assertions below pin both the ride and the order.
        val h = listOf(useMsg("call_1"), resultMsg("call_1"))
        val out = buildDetachedCompactHistory("<summary/>", h, tailSize = 2)
        val first = out.first()
        assertEquals(LLMMessage.Role.USER, first.role)
        assertTrue(first.content.startsWith("<summary/>"))
        assertTrue(
            "summary must ride contentParts too",
            first.contentParts.filterIsInstance<AgentContentPart.Text>()
                .any { it.text.contains("<summary/>") },
        )
        // The tool result part is preserved after the injected text.
        val toolIdx = first.contentParts.indexOfFirst { it is AgentContentPart.ToolResult }
        val textIdx = first.contentParts.indexOfFirst {
            it is AgentContentPart.Text && it.text.contains("<summary/>")
        }
        assertTrue("summary text must follow the tool_result on the carrier", textIdx > toolIdx)
    }

    @Test
    fun `detached cut states the real gap and digests the newest dropped rounds`() {
        // [T-detached-drop-truth] 400 entries, tail 60 → 340 dropped. The old
        // path said nothing at all; the notice must name the gap and carry
        // excerpts of the newest dropped messages (the ones next to the tail).
        val h = history(200)
        val out = buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        val first = out.first().content
        assertTrue(
            "gap must be stated: $first",
            first.contains("[detached compact boundary: 340 earlier messages are not in this request."),
        )
        assertTrue(first.contains("Most recent dropped messages"))
        assertTrue("digest keeps real user text", first.contains("u170"))
        assertTrue(first.contains("summary may not cover all"))
    }

    @Test
    fun `detached notice lives inside the summary envelope`() {
        // The first tail row is a REAL user turn. Appending the notice after
        // </context-summary> would make HarnessNoteMarkers classify that turn
        // as a host note and fold it backwards; the notice must stay inside
        // the background-context envelope.
        val h = history(200)
        val wrapped = "<context-summary>\nS\n</context-summary>"
        val out = buildDetachedCompactHistory(wrapped, h, tailSize = 60)
        val first = out.first().content
        val noticeAt = first.indexOf("[detached compact boundary:")
        val closeAt = first.indexOf("</context-summary>")
        assertTrue(noticeAt in 0 until closeAt)
    }

    @Test
    fun `detached tail size is token bounded not a fixed count`() {
        // [T-detached-tail-budget] 60 spill-sized tool results = ~240k tokens.
        // A 128k-window tail budget (35% = 44.8k) must not keep all 60.
        val h = (0 until 60).map { i ->
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult("c$i", "shell_execute", "x".repeat(16_000)),
                ),
            )
        }
        val size = detachedTailSize(h, 44_800)
        assertTrue("expected a bounded tail, got $size", size in 1..20)
        val kept = h.takeLast(size)
        val tokens = kept.sumOf { WalkBack.estimateMessageTokens(it) }
        assertTrue("tail tokens=$tokens", tokens <= 44_800)
    }

    @Test
    fun `detached tail size always keeps the newest entry`() {
        val h = (0 until 5).map { i ->
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult("c$i", "shell_execute", "x".repeat(80_000)),
                ),
            )
        }
        assertEquals(1, detachedTailSize(h, 100))
    }

    @Test
    fun `single user autonomous run keeps a bounded legal tail`() {
        // [T-detached-tail-pairing] One user turn + 300 tool rounds: the old
        // safeTailStart walked back to the only real user (index 0), shipping
        // the whole history regardless of tailSize.
        val h = mutableListOf(user(1))
        repeat(300) { i ->
            h.add(useMsg("c$i"))
            h.add(resultMsg("c$i"))
        }
        val out = buildDetachedCompactHistory("<summary/>", h, tailSize = 10)
        assertTrue("bounded tail, got ${out.size}", out.size <= 12)
        assertEquals(LLMMessage.Role.USER, out.first().role)
        val (uses, results) = toolIds(out)
        assertEquals(uses, results)
    }

    @Test
    fun `a fully retained history has no detached notice`() {
        val h = listOf(user(1), asst(1), user(2))
        val out = buildDetachedCompactHistory("<summary/>", h, tailSize = 60)
        assertTrue(out.first().content.startsWith("<summary/>"))
        assertTrue(!out.first().content.contains("[detached compact boundary:"))
    }
}
