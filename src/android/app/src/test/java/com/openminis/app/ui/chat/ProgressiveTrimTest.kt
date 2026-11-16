package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-progressive-trim] Contract of the progressive context trim
 * applied to outgoing history projections (effectiveAgentHistory).
 *
 * The ladder (see ProgressiveTrim for the full narrative):
 *   ≥50% of window → STRIP_OLD_THINKING
 *   ≥70% of window → STUB_LARGE_TOOL_OUTPUTS
 * (the compact threshold sits above both, so trimming gets a chance to
 * pull the payload back under the compact line first)
 *
 * Safety invariants that MUST hold at every level:
 *   - the last PROTECTED_RECENT_MESSAGES messages pass through untouched
 *   - the current round (after the last user message) is never trimmed
 *   - tool_use / tool_result pairing is preserved (ids never change,
 *     stubs keep id+name so the pair still matches)
 *   - user content and assistant TEXT are never touched
 *   - below the first tier the projection is the SAME list instance
 */
class ProgressiveTrimTest {

    private fun user(text: String, dbId: Int) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = text,
        dbMessageId = "u$dbId",
    )

    private fun assistant(
        dbId: Int,
        text: String = "reply $dbId",
        reasoning: String? = null,
        parts: List<AgentContentPart> = emptyList(),
    ) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = text,
        contentParts = parts,
        reasoningContent = reasoning,
        dbMessageId = "a$dbId",
    )

    private fun toolRound(dbId: Int, resultContent: String): Pair<LLMMessage, LLMMessage> {
        val callId = "call_$dbId"
        val use = assistant(
            dbId = dbId,
            parts = listOf(
                AgentContentPart.ToolUse(id = callId, name = "shell", input = JSONObject("""{"cmd":"ls"}""")),
            ),
        )
        val result = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(id = callId, name = "shell", content = resultContent),
            ),
            dbMessageId = "t$dbId",
        )
        return use to result
    }

    /** 25 full tool rounds = 75 messages, well past the 40-message tail. */
    private fun toolSession(rounds: Int = 25, resultContent: (Int) -> String = { "x".repeat(5000) }) =
        buildList {
            repeat(rounds) {
                val (use, result) = toolRound(it, resultContent(it))
                add(user("q $it", it))
                add(use)
                add(result)
            }
        }

    // ── levelFor ────────────────────────────────────────────────────

    @Test
    fun `below the first tier no trimming happens`() {
        assertEquals(ProgressiveTrim.Level.NONE, ProgressiveTrim.levelFor(49, 100))
    }

    @Test
    fun `tier boundaries map to the right level`() {
        assertEquals(ProgressiveTrim.Level.STRIP_OLD_THINKING, ProgressiveTrim.levelFor(50, 100))
        assertEquals(ProgressiveTrim.Level.STRIP_OLD_THINKING, ProgressiveTrim.levelFor(69, 100))
        assertEquals(ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS, ProgressiveTrim.levelFor(70, 100))
        assertEquals(ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS, ProgressiveTrim.levelFor(84, 100))
        // [T-no-merge-old-steps] L3 (merge whole iterations) is never selected:
        // it is the only tier that DROPS messages, and dropping them took the
        // finished rounds' tool cards out of the transcript. The ladder tops out
        // at stubbing bulk, which keeps every message and every tool id.
        assertEquals(ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS, ProgressiveTrim.levelFor(85, 100))
        assertEquals(ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS, ProgressiveTrim.levelFor(120, 100))
    }

    @Test
    fun `missing or degenerate window means no trim`() {
        assertEquals(ProgressiveTrim.Level.NONE, ProgressiveTrim.levelFor(1000, null))
        assertEquals(ProgressiveTrim.Level.NONE, ProgressiveTrim.levelFor(1000, 0))
        assertEquals(ProgressiveTrim.Level.NONE, ProgressiveTrim.levelFor(0, 100))
    }

    // ── no-op identity ──────────────────────────────────────────────

    @Test
    fun `short list is returned as the same instance`() {
        val list = listOf(user("hi", 1), assistant(1))
        assertSame(list, ProgressiveTrim.trim(list, ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS))
    }

    @Test
    fun `below threshold trim returns the same instance`() {
        val list = toolSession()
        assertSame(list, ProgressiveTrim.trim(list, ProgressiveTrim.Level.NONE))
    }

    // ── L1: old reasoning stripped, tail + current round protected ──

    @Test
    fun `strip old thinking keeps the protected zones`() {
        val list = buildList {
            repeat(30) {
                add(user("q $it", it))
                add(assistant(it, reasoning = "chain $it"))
            }
        }
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.STRIP_OLD_THINKING)
        assertEquals(list.size, trimmed.size)

        val lastUserIdx = list.indexOfLast { it.role == LLMMessage.Role.USER }
        val last8Assistants = list.indices.reversed()
            .filter { list[it].role == LLMMessage.Role.ASSISTANT }
            .take(ProgressiveTrim.PROTECTED_RECENT_ASSISTANT_MESSAGES)
            .toSet()
        val firstProtected = list.size - ProgressiveTrim.PROTECTED_RECENT_MESSAGES

        trimmed.forEachIndexed { idx, msg ->
            val protectedZone = idx >= firstProtected || idx >= lastUserIdx || idx in last8Assistants
            if (protectedZone) {
                assertEquals(list[idx].reasoningContent, msg.reasoningContent)
            } else {
                assertNull("reasoning at $idx should be stripped", msg.reasoningContent)
            }
        }
        // Assistant TEXT is never touched at any level.
        trimmed.forEachIndexed { idx, msg -> assertEquals(list[idx].content, msg.content) }
    }

    @Test
    fun `strip old thinking never touches tool results`() {
        val list = toolSession()
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.STRIP_OLD_THINKING)
        trimmed.forEachIndexed { idx, msg ->
            assertEquals("parts at $idx", list[idx].contentParts, msg.contentParts)
        }
    }

    // ── L2: large tool results stubbed, pairing preserved ───────────

    @Test
    fun `stub large tool outputs keeps id and name and shrinks content`() {
        val list = toolSession()
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS)
        val firstProtected = list.size - ProgressiveTrim.PROTECTED_RECENT_MESSAGES
        var stubbed = 0
        trimmed.forEachIndexed { idx, msg ->
            list[idx].contentParts.filterIsInstance<AgentContentPart.ToolResult>().forEach { orig ->
                val out = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                    .first { it.id == orig.id }
                if (idx < firstProtected &&
                    orig.content.length > ProgressiveTrim.TOOL_RESULT_STUB_THRESHOLD_CHARS
                ) {
                    assertTrue(out.content.length < orig.content.length)
                    assertEquals(orig.id, out.id)
                    assertEquals(orig.name, out.name)
                    stubbed++
                } else {
                    assertEquals(orig.content, out.content)
                }
            }
        }
        assertTrue("expected some stubbed results", stubbed > 0)
    }

    @Test
    fun `small tool results pass through verbatim`() {
        val list = toolSession(resultContent = { "small $it" })
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS)
        trimmed.forEachIndexed { idx, msg -> assertEquals(list[idx].contentParts, msg.contentParts) }
    }

    @Test
    fun `tool pairing survives the most aggressive trim`() {
        // A tool_use in an old assistant message and its tool_result in
        // the following user message must BOTH still be present with the
        // same id after trimming — otherwise the request would carry an
        // orphaned half and 400 on strict providers.
        val list = toolSession()
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS)
        val useIds = trimmed.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolUse>()
            .map { it.id }
            .toSet()
        val resultIds = trimmed.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>()
            .map { it.id }
            .toSet()
        assertTrue(useIds.isNotEmpty())
        assertEquals(useIds, resultIds)
    }

    @Test
    fun `current round tool result is never stubbed`() {
        // Trailing round: user → tool_use → big tool_result. Everything
        // from the last user message on is the active round and must
        // survive verbatim even at L2.
        val big = "x".repeat(5000)
        val list = buildList {
            repeat(20) {
                val (use, result) = toolRound(it, "x".repeat(3000))
                add(user("q $it", it)); add(use); add(result)
            }
            add(user("final question", 99))
            val (use, result) = toolRound(99, big)
            add(use)
            add(result)
        }
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS)
        assertEquals(big, trimmed.last().contentParts.first().let { it as AgentContentPart.ToolResult }.content)
    }

    @Test
    fun `protected tail is untouched at the top level`() {
        val list = toolSession()
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS)
        assertEquals(
            list.takeLast(ProgressiveTrim.PROTECTED_RECENT_MESSAGES),
            trimmed.takeLast(ProgressiveTrim.PROTECTED_RECENT_MESSAGES),
        )
    }

    // ── structural invariants ───────────────────────────────────────

    @Test
    fun `trim is idempotent`() {
        val list = toolSession()
        val once = ProgressiveTrim.trim(list, ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS)
        val twice = ProgressiveTrim.trim(once, ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS)
        assertEquals(once, twice)
    }

    @Test
    fun `message count order and ids never change`() {
        val list = toolSession()
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS)
        assertEquals(list.size, trimmed.size)
        trimmed.forEachIndexed { idx, msg -> assertEquals(list[idx].dbMessageId, msg.dbMessageId) }
        assertFalse(trimmed.zip(list).any { (a, b) -> a.role != b.role })
    }

    @Test
    fun `user content is never modified`() {
        val list = buildList {
            repeat(30) {
                add(user("question $it with detail", it))
                add(assistant(it))
            }
        }
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS)
        trimmed.forEachIndexed { idx, msg ->
            if (list[idx].role == LLMMessage.Role.USER) {
                assertEquals(list[idx].content, msg.content)
            }
        }
    }

    // ── [T-wave-e-progressive-reclaim] L2 offload-backed stubs ──────

    @Test
    fun `stub rides the offload path and offers file_read back`() {
        val offloaded = mutableListOf<String>()
        val offloader = ProgressiveTrim.Offloader { content, _, _ ->
            offloaded += content
            "/var/minis/workspace/tool-spill/trim-call_0.txt"
        }
        val list = toolSession()
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS, offloader)
        assertTrue("old big outputs must be offloaded", offloaded.isNotEmpty())
        val stubbed = trimmed.asSequence()
            .flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>()
            .first { it.content.startsWith("[tool output offloaded") }
        assertTrue(stubbed.content.contains("file_read"))
        assertTrue(stubbed.content.contains("/var/minis/workspace/tool-spill/trim-call_0.txt"))
        // The stub keeps a recognisable verbatim head of the original: the
        // head the stub shows must be a prefix of what was handed offload.
        val shownHead = stubbed.content.substringAfter("]\n")
        assertTrue(offloaded.any { it.startsWith(shownHead) })
    }

    @Test
    fun `offload failure keeps the honest no-path stub`() {
        val offloader = ProgressiveTrim.Offloader { _, _, _ -> null }
        val list = toolSession()
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.STUB_LARGE_TOOL_OUTPUTS, offloader)
        val stubbed = trimmed.asSequence()
            .flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>()
            .first { it.content.startsWith("[tool output trimmed") }
        assertFalse(stubbed.content.contains("file_read"))
    }

    // ── [T-wave-e-progressive-reclaim] L3: merge old iterations ─────

    @Test
    fun `merge collapses old iterations into one index block`() {
        // 25 rounds = 75 messages; the protected tail is 40, so rounds 0..11
        // (assistant idx 1..34) are trimmable and older than the kept 6.
        val list = toolSession(rounds = 25)
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.MERGE_OLD_STEPS)
        assertTrue("merge must shrink the projection", trimmed.size < list.size)
        // Exactly ONE index block lands, on a user-role carrier.
        val indexMsgs = trimmed.filter { it.content.startsWith("[Minis context]") }
        assertEquals(1, indexMsgs.size)
        assertEquals(LLMMessage.Role.USER, indexMsgs[0].role)
        assertTrue(indexMsgs[0].content.contains("shell"))
        assertTrue(indexMsgs[0].content.contains("file_read"))
    }

    @Test
    fun `merge never orphans a tool_use or a tool_result`() {
        val list = toolSession(rounds = 25)
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.MERGE_OLD_STEPS)
        val useIds = trimmed.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolUse>()
            .map { it.id }
            .toSet()
        val resultIds = trimmed.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>()
            .map { it.id }
            .toSet()
        assertTrue(useIds.isNotEmpty())
        assertEquals("every surviving use keeps its result and vice versa", useIds, resultIds)
    }

    @Test
    fun `merge keeps the most recent iterations intact`() {
        val list = toolSession(rounds = 25)
        val trimmed = ProgressiveTrim.trim(list, ProgressiveTrim.Level.MERGE_OLD_STEPS)
        // The last PROTECTED_RECENT_MESSAGES messages pass through verbatim.
        assertEquals(
            list.takeLast(ProgressiveTrim.PROTECTED_RECENT_MESSAGES),
            trimmed.takeLast(ProgressiveTrim.PROTECTED_RECENT_MESSAGES),
        )
    }

    @Test
    fun `merge level on a session with nothing collapsible is a no-op`() {
        // 8 rounds = 24 messages — all inside the 40-message protected tail,
        // so no iteration is trimmable and the input survives as-is.
        val list = toolSession(rounds = 8)
        assertSame(list, ProgressiveTrim.trim(list, ProgressiveTrim.Level.MERGE_OLD_STEPS))
    }

    @Test
    fun `index text lists tool names statuses and output paths`() {
        val (use, result) = toolRound(0, "out — saved to: /var/minis/workspace/tool-spill/x.txt")
        val messages = listOf(user("q", 0), use, result)
        val its = ProgressiveTrim.iterations(messages)
        assertEquals(1, its.size)
        assertTrue("iteration must be paired", its[0].paired)
        val text = ProgressiveTrim.indexText(messages, its)
        assertTrue(text.contains("shell"))
        assertTrue(text.contains("ok"))
        assertTrue(text.contains("/var/minis/workspace/tool-spill/x.txt"))
    }

    // ── [pi-port 1.7] cut-point hard constraints ────────────────────────
    //
    // mergeOldSteps only ever considers its.take(size - MERGE_KEEP_RECENT_ITERATIONS)
    // as candidates, so every merge-level test below builds ≥ 8 iterations to
    // get a non-empty candidate set and actually reach the new constraint code.

    private fun useCall(id: String) = AgentContentPart.ToolUse(
        id = id, name = "shell", input = JSONObject("""{"cmd":"ls"}"""),
    )

    private fun resultMsg(callId: String, dbId: String, extra: List<AgentContentPart> = emptyList()) =
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(id = callId, name = "shell", content = "out $callId"),
            ) + extra,
            dbMessageId = dbId,
        )

    private fun useMsg(vararg callIds: String, dbId: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "calling ${callIds.joinToString()}",
        contentParts = callIds.map { useCall(it) },
        dbMessageId = dbId,
    )

    private fun userMsg(text: String, dbId: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = text,
        dbMessageId = dbId,
    )

    /** tool_use ids vs tool_result ids still in the projection. */
    private fun pairingOf(messages: List<LLMMessage>): Pair<Set<String>, Set<String>> {
        val uses = messages.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolUse>().map { it.id }.toSet()
        val results = messages.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>().map { it.id }.toSet()
        return uses to results
    }

    @Test
    fun `user prompt boundary is a safe cut`() {
        val list = listOf(
            user("t0", 0), useMsg("call_a", dbId = "a1"),
            resultMsg("call_a", "r1"), user("t1", 3),
        )
        assertTrue(ProgressiveTrim.isSafeCutBoundary(list, 3))
    }

    @Test
    fun `tool result carrier is never a safe cut boundary`() {
        val list = listOf(
            user("t0", 0), useMsg("call_a", dbId = "a1"),
            resultMsg("call_a", "r1"), user("t1", 3),
        )
        // The pure carrier, and any message carrying results alongside text.
        assertFalse(ProgressiveTrim.isSafeCutBoundary(list, 2))
        val pinned = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "note",
            contentParts = listOf(
                AgentContentPart.Text("note"),
                AgentContentPart.ToolResult(id = "call_a", name = "shell", content = "out"),
            ),
            dbMessageId = "r1",
        )
        val pinnedList = listOf(user("t0", 0), useMsg("call_a", dbId = "a1"), pinned, user("t1", 3))
        assertFalse(ProgressiveTrim.isSafeCutBoundary(pinnedList, 2))
    }

    @Test
    fun `assistant boundary is rejected as mid-turn without turn-prefix summarization`() {
        val list = listOf(
            user("t0", 0), useMsg("call_a", dbId = "a1"),
            resultMsg("call_a", "r1"), useMsg("call_b", dbId = "a2"),
        )
        assertFalse(ProgressiveTrim.isSafeCutBoundary(list, 3))
    }

    @Test
    fun `boundary at index zero list end or out of range is never safe`() {
        val list = listOf(user("t0", 0), useMsg("call_a", dbId = "a1"), resultMsg("call_a", "r1"))
        assertFalse(ProgressiveTrim.isSafeCutBoundary(list, 0))
        assertFalse(ProgressiveTrim.isSafeCutBoundary(list, list.size))
        assertFalse(ProgressiveTrim.isSafeCutBoundary(list, -1))
        assertFalse(ProgressiveTrim.isSafeCutBoundary(list, list.size + 1))
    }

    @Test
    fun `a pre-cut call result hanging after a plain user forbids the cut`() {
        // Malformed history: the answer trails AFTER a later plain prompt. The
        // cut at the prompt would summarize the call away and leave the result
        // dangling — pi isCandidate's forward scan rejects it.
        val list = listOf(
            user("t0", 0),
            useMsg("call_a", dbId = "a1"),
            user("continue?", 2),
            resultMsg("call_a", "r1"),
        )
        assertFalse(ProgressiveTrim.isSafeCutBoundary(list, 2))
    }

    @Test
    fun `merge refuses to orphan a use whose result rides a later carrier`() {
        // The exact #265-class shape the old subset-collapse could corrupt:
        // assistant 1's call never got a carrier of its own (its carrier chain
        // breaks at assistant 2), so its result rides the carrier of
        // assistant 2. The old code collapsed assistant 2 + its carrier ALONE
        // (the unpaired head iteration was filtered out of the subset),
        // deleting the result and orphaning assistant 1's tool_use. The new
        // prefix rule stops at the unpaired head iteration and degrades to NO
        // merge at all.
        val list = buildList {
            add(userMsg("q0", "u0"))
            add(useMsg("call_1", dbId = "a1"))   // unpaired head iteration
            add(useMsg("call_2", dbId = "a2"))   // carrier chain breaks here
            add(resultMsg("call_1", "rx", extra = listOf(
                AgentContentPart.ToolResult(id = "call_2", name = "shell", content = "out call_2"),
            )))
            var q = 1
            for (i in 3..8) {
                add(userMsg("q$q", "uq$q")); q++
                add(useMsg("call_$i", dbId = "a$i"))
                add(resultMsg("call_$i", "r$i"))
            }
        }
        assertNull(ProgressiveTrim.mergeOldSteps(list, lastUserIdx = 21, firstTrimmableIdx = 19))
    }

    @Test
    fun `merge degrades to nothing when no safe cut boundary exists`() {
        // One pure tool loop: no interstitial prompts, so every candidate cut
        // lands mid-turn on an assistant. Constraint 3 (turn boundary) has no
        // turn boundary to offer → collapse nothing rather than hard-cut.
        val list = buildList {
            add(userMsg("q0", "u0"))
            for (i in 1..8) {
                add(useMsg("call_$i", dbId = "a$i"))
                add(resultMsg("call_$i", "r$i"))
            }
        }
        assertNull(ProgressiveTrim.mergeOldSteps(list, lastUserIdx = -1, firstTrimmableIdx = 13))
    }

    @Test
    fun `merge collapses a whole old turn and cuts at the next turn boundary`() {
        // Positive pi case: the two oldest iterations are exactly turn 0 (the
        // rest of the session covers the kept-recent window), so the collapsed
        // prefix ends at the next turn's user prompt — a safe boundary — and
        // the whole old turn merges into the index block while the new turn
        // stays verbatim.
        val list = buildList {
            add(userMsg("task", "u0"))
            add(useMsg("call_1", dbId = "a1")); add(resultMsg("call_1", "r1"))
            add(useMsg("call_2", dbId = "a2")); add(resultMsg("call_2", "r2"))
            add(userMsg("new question", "t1"))
            for (i in 3..8) {
                add(useMsg("call_$i", dbId = "a$i"))
                add(resultMsg("call_$i", "r$i"))
            }
        }
        val merged = ProgressiveTrim.mergeOldSteps(list, lastUserIdx = 17, firstTrimmableIdx = 15)
        assertNotNull("turn-boundary collapse must be allowed", merged)
        merged!!
        val ids = merged.mapNotNull { it.dbMessageId }.toSet()
        assertFalse("collapsed calls must be gone", ids.any { it == "a1" || it == "a2" || it == "r1" || it == "r2" })
        assertTrue("the turn boundary prompt survives", "t1" in ids)
        assertTrue("the kept-recent iterations survive", "a8" in ids && "r8" in ids)
        assertEquals(18 - 4 + 1, merged.size)
        assertEquals(1, merged.count { it.content.startsWith("[Minis context]") })
        val (uses, results) = pairingOf(merged)
        assertEquals(uses, results)
    }

    @Test
    fun `merge keeps a protected tail carrier even when its assistant is trimmable`() {
        // #265 regression: iteration 2's assistant (index 4) sits inside the
        // trimmable prefix but its carrier (index 5) is in the protected tail
        // (firstTrimmableIdx = 5). The iteration must not collapse, and the
        // older fully-trimmable iteration still merges — fewer collapses,
        // never a dropped tail result.
        val list = buildList {
            add(userMsg("q0", "u0"))
            add(useMsg("call_1", dbId = "a1")); add(resultMsg("call_1", "r1"))
            add(userMsg("q1", "u1"))
            add(useMsg("call_2", dbId = "a2")); add(resultMsg("call_2", "r2"))  // carrier at 5
            var q = 2
            for (i in 3..9) {
                add(userMsg("q$q", "uq$q")); q++
                add(useMsg("call_$i", dbId = "a$i"))
                add(resultMsg("call_$i", "r$i"))
            }
        }
        val merged = ProgressiveTrim.mergeOldSteps(list, lastUserIdx = 21, firstTrimmableIdx = 5)!!
        assertTrue("protected-tail result must survive verbatim", "r2" in merged.mapNotNull { it.dbMessageId }.toSet())
        assertTrue("its call must survive with it", "a2" in merged.mapNotNull { it.dbMessageId }.toSet())
        assertFalse("the older trimmable iteration still merges", "r1" in merged.mapNotNull { it.dbMessageId }.toSet())
        val (uses, results) = pairingOf(merged)
        assertEquals(uses, results)
    }

    @Test
    fun `merge collapses both carriers of an assistant whose results span users`() {
        // Consecutive toolResult carriers: one assistant, two result messages.
        // Both carriers must drop together with their assistant.
        val list = buildList {
            add(userMsg("q0", "u0"))
            add(useMsg("call_1", "call_2", dbId = "a1"))
            add(resultMsg("call_1", "r1"))
            add(resultMsg("call_2", "r2"))
            var q = 1
            for (i in 3..9) {
                add(userMsg("q$q", "uq$q")); q++
                add(useMsg("call_$i", dbId = "a$i"))
                add(resultMsg("call_$i", "r$i"))
            }
        }
        val its = ProgressiveTrim.iterations(list)
        assertEquals(listOf(2, 3), its.first().carrierIdx)
        val merged = ProgressiveTrim.mergeOldSteps(list, lastUserIdx = 24, firstTrimmableIdx = 5)!!
        assertFalse(merged.mapNotNull { it.dbMessageId }.toSet().any { it == "r1" || it == "r2" })
        val (uses, results) = pairingOf(merged)
        assertEquals(uses, results)
    }
}
