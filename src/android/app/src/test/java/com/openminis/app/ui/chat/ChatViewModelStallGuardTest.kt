package com.openminis.app.ui.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-loop-stall-guard] Pure JVM tests for the per-runAgentLoop stall
 * accounting: rule 1 (nudge at 2 frozen repeats), rule 2 (break at 4), rule 3
 * (silent-turn progress nudge at 12), and the reset semantics (different tool
 * interleave, changed results, text turns, run reset).
 */
class ChatViewModelStallGuardTest {

    private val guard = ChatViewModelStallGuard()
    private val shaA = "sha-a"
    private val shaB = "sha-b"
    private val resA = "res-a"

    private fun turn(
        name: String = "execute_shell",
        argsSha: String = shaA,
        textLen: Int = 0,
        resultSha: String? = resA,
        output: String? = "ok",
    ) = guard.recordTurn(
        toolCalls = listOf(ChatViewModelStallGuard.DispatchedToolCall(name, argsSha)),
        assistantTextLength = textLen,
        resultSha = resultSha,
        lastToolOutput = output,
    )

    // ── rule 1: nudge at 2 consecutive frozen repeats ────────────────────────

    @Test
    fun firstRepeatIsQuiet() {
        val d = turn()
        assertFalse(d.stalled)
        assertNull(d.nudge)
        assertEquals(1, d.repeatStreak)
    }

    @Test
    fun secondConsecutiveRepeatArmsTheNudgeOnce() {
        turn()
        val d = turn()
        assertEquals(2, d.repeatStreak)
        assertTrue(d.nudge!!.contains("Tool execute_shell with identical arguments has been executed 2 times in a row"))
        assertTrue(d.nudge!!.contains("Do NOT repeat it: analyze the output, change strategy, or report why the goal is unreachable."))
        // One-shot: the next frozen repeat does NOT re-arm.
        val d3 = turn()
        assertNull(d3.nudge)
        assertFalse(d3.stalled)
    }

    // ── rule 2: break at 4 consecutive frozen repeats ────────────────────────

    @Test
    fun fourthConsecutiveRepeatBreaksTheRun() {
        repeat(3) { turn() }
        val d = turn(output = "final tail output")
        assertTrue(d.stalled)
        assertEquals(4, d.repeatStreak)
        val report = d.breakReport!!
        assertTrue(report.contains("the same command (execute_shell) was executed 4 times in a row with an unchanged result"))
        assertTrue(report.contains("Last output: final tail output"))
    }

    @Test
    fun breakReportQuotesAtMost500TailChars() {
        repeat(3) { turn(output = "y".repeat(2000)) }
        val d = turn(output = "y".repeat(2000))
        val tail = d.breakReport!!.substringAfter("Last output: ")
        assertEquals(500, tail.length)
    }

    // ── resets ───────────────────────────────────────────────────────────────

    @Test
    fun aDifferentToolInterleaveResetsTheStreak() {
        turn(); turn() // streak = 2 → nudge armed once
        val other = turn(name = "read_file", argsSha = shaB)
        assertEquals(1, other.repeatStreak)
        assertNull("streak restarted, nudge spent on the previous streak", other.nudge)
        // Back to the original call: the interleave reset the streak, so the
        // first return of A starts a fresh streak at 1.
        val again = turn()
        assertEquals(1, again.repeatStreak)
        assertNull(again.nudge)
        // …and only a SECOND consecutive run of it re-arms a nudge.
        val onceMore = turn()
        assertTrue(onceMore.nudge!!.contains("executed 2 times"))
    }

    @Test
    fun changedResultsResetTheStreakSoPollingIsNotPenalized() {
        turn(); turn()
        val progressed = turn(resultSha = "res-changed")
        assertEquals(1, progressed.repeatStreak)
        assertNull(progressed.nudge)
        // Same args but results keep changing (a working poll): never breaks.
        repeat(6) { i ->
            val d = turn(resultSha = "res-$i")
            assertFalse("progress is never a stall", d.stalled)
        }
    }

    @Test
    fun nullResultShaResetsTheStreak() {
        turn()
        val d = turn(resultSha = null)
        assertEquals(1, d.repeatStreak)
    }

    @Test
    fun textOnlyTurnResetsEverything() {
        repeat(11) { turn() } // streak = 11, one away from breaking
        val d = guard.recordTurn(
            toolCalls = emptyList(),
            assistantTextLength = 5,
            resultSha = null,
            lastToolOutput = null,
        )
        assertFalse(d.stalled)
        // A fresh run of the same call starts from scratch.
        val next = turn()
        assertEquals(1, next.repeatStreak)
    }

    @Test
    fun resetClearsAllState() {
        turn(); turn()
        guard.reset()
        val d = turn()
        assertEquals(1, d.repeatStreak)
        assertNull(d.nudge)
    }

    // ── rule 3: silent tool turns ────────────────────────────────────────────

    @Test
    fun silentTurnNudgeFiresAtTwelveOnce() {
        var nudge: String? = null
        for (i in 1 until 12) {
            val d = turn(resultSha = "res-$i") // results change → no rule-1/2 interference
            assertNull(d.nudge)
        }
        val d12 = turn(resultSha = "res-12")
        nudge = d12.nudge
        assertTrue(nudge!!.contains("You have made 12 tool calls without any user-facing text"))
        // One-shot per run.
        val d13 = turn(resultSha = "res-13")
        assertNull(d13.nudge)
    }

    @Test
    fun textTurnResetsTheSilentStreak() {
        for (i in 1 until 12) turn(resultSha = "res-$i")
        // A turn WITH user-facing text resets rule 3 (and may still carry rule 1).
        turn(resultSha = "res-x", textLen = 10)
        for (i in 1 until 11) turn(resultSha = "res-y$i")
        val d = turn(resultSha = "res-y")
        assertNull("silent streak was reset by the text turn (11 < 12)", d.nudge)
    }

    @Test
    fun stallNudgeWinsWhenBothRulesFireOnTheSameTurn() {
        // 11 silent alternating turns, then a 12th that repeats turn 11: the
        // rule-1 pair completes exactly on turn 12, where rule 3's 12th
        // silent turn would also fire. Rule 1 is the more specific signal
        // and must win the slot.
        for (i in 1 until 12) {
            val d = turn(
                name = if (i % 2 == 0) "read_file" else "execute_shell",
                argsSha = if (i % 2 == 0) shaB else shaA,
            )
            assertNull("alternating tools must not stall", d.nudge)
            assertFalse(d.stalled)
        }
        val d12 = turn(
            name = "execute_shell",
            argsSha = shaA,
        )
        assertEquals(12, guard.snapshot().substringAfter("silentTurnStreak=").toInt())
        assertTrue(d12.nudge!!.contains("identical arguments"))
        assertFalse(d12.nudge!!.contains("Summarize progress"))
    }

    // ── hashing helpers ──────────────────────────────────────────────────────

    @Test
    fun dispatchedToolCallIgnoresToolTitle() {
        val withTitle = ChatViewModelStallGuard.dispatchedToolCall(
            "execute_shell",
            JSONObject("""{"cmd":"ls -la","tool_title":"List files"}"""),
        )
        val retitled = ChatViewModelStallGuard.dispatchedToolCall(
            "execute_shell",
            JSONObject("""{"cmd":"ls -la","tool_title":"List files #2"}"""),
        )
        val bare = ChatViewModelStallGuard.dispatchedToolCall(
            "execute_shell",
            JSONObject("""{"cmd":"ls -la"}"""),
        )
        assertEquals(withTitle.argsSha, retitled.argsSha)
        assertEquals(withTitle.argsSha, bare.argsSha)
        // Different command → different sha.
        val other = ChatViewModelStallGuard.dispatchedToolCall(
            "execute_shell",
            JSONObject("""{"cmd":"ls /"}"""),
        )
        assertTrue(other.argsSha != bare.argsSha)
    }

    @Test
    fun resultShaOfIsEmptySafe() {
        assertNull(ChatViewModelStallGuard.resultShaOf(emptyList()))
        val one = ChatViewModelStallGuard.resultShaOf(listOf("out"))
        val two = ChatViewModelStallGuard.resultShaOf(listOf("out"))
        assertEquals(one, two)
    }
}
