package com.openminis.app.tools

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMMessage.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorPromptsTest {

    @Test
    fun supervisorSystem_statesProtocolEnvelopeAndRubberStampRule() {
        val s = SupervisorPrompts.supervisorSystem("GPT-4o")
        assertTrue(s.contains("DONE:"))
        assertTrue(s.contains("## Task"))
        assertTrue(s.contains("## Expected result"))
        assertTrue(s.contains("GPT-4o"))
    }

    @Test
    fun firstTurn_echoesObjectiveInboxAndRecovery() {
        val p = SupervisorPrompts.firstTurn(
            objective = "ship the parser",
            contextDigest = "user: fix the parser",
            inbox = listOf("keep it small", "no regex"),
            recovery = null,
        )
        assertTrue(p.contains("ship the parser"))
        assertTrue(p.contains("user: fix the parser"))
        assertTrue(p.contains("keep it small"))
        assertTrue(p.contains("no regex"))
        assertFalse(p.contains("上次中断"))
    }

    @Test
    fun firstTurn_includesRecoverySectionWhenPresent() {
        val p = SupervisorPrompts.firstTurn(
            objective = "o",
            contextDigest = "",
            inbox = emptyList(),
            recovery = "上次中断于第 7 轮",
        )
        assertTrue(p.contains("上次中断于第 7 轮"))
    }

    @Test
    fun reviewTurn_includesReportAcceptanceBudgetAndBounce() {
        val p = SupervisorPrompts.reviewTurn(
            objective = "o",
            lastReport = "wrote the file",
            acceptance = listOf("tests pass", "no regressions"),
            inbox = emptyList(),
            workerTurns = 7,
            maxWorkerTurns = 50,
            bounceNote = null,
        )
        assertTrue(p.contains("wrote the file"))
        assertTrue(p.contains("tests pass"))
        assertTrue(p.contains("no regressions"))
        assertTrue(p.contains("7"))
        assertTrue(p.contains("50"))
        assertFalse(p.contains("envelope"))
    }

    @Test
    fun reviewTurn_includesBounceNoteOnlyWhenPresent() {
        val args = listOf("o", "r", emptyList<String>(), emptyList<String>(), 1, 50)
        val without = SupervisorPrompts.reviewTurn("o", "r", emptyList(), emptyList(), 1, 50, null)
        val with = SupervisorPrompts.reviewTurn("o", "r", emptyList(), emptyList(), 1, 50, "envelope rejected")
        assertFalse(without.contains("envelope rejected"))
        assertTrue(with.contains("envelope rejected"))
    }

    @Test
    fun recoveryIntro_includesRoundsAndLastDigest() {
        val record = SupervisorRunStore.RunRecord(
            id = "id",
            sessionId = "s",
            objective = "o",
            state = SupervisorRunStore.STATE_INTERRUPTED,
            workerTurns = 7,
            rounds = listOf(
                SupervisorRunStore.RoundRecord(1, "a", "b", 1L),
                SupervisorRunStore.RoundRecord(7, "do c", "did c", 2L),
            ),
            doneSummary = null,
            createdAt = 1L,
            updatedAt = 2L,
        )
        val intro = SupervisorPrompts.recoveryIntro(record)
        assertTrue(intro.contains("7"))
        assertTrue(intro.contains("did c"))
    }

    @Test
    fun contextDigest_formatsDropsBlankAndTailCaps() {
        val messages = listOf(
            LLMMessage(role = Role.USER, content = "first message"),
            LLMMessage(role = Role.ASSISTANT, content = ""),
            LLMMessage(role = Role.ASSISTANT, content = "second message"),
        )
        val digest = SupervisorPrompts.contextDigest(messages, maxChars = 40)
        assertFalse(digest.contains("first message"))
        assertTrue(digest.contains("second message"))
        assertTrue(digest.length <= 40)
    }

    @Test
    fun bounceEnvelope_and_bounceRubberStamp_mentionTheContract() {
        assertTrue(SupervisorPrompts.bounceEnvelope("missing section").contains("missing section"))
        assertTrue(SupervisorPrompts.bounceRubberStamp(listOf("tests pass")).contains("tests pass"))
    }

    @Test
    fun capReached_mentionsObjectiveAndBudget() {
        val p = SupervisorPrompts.capReached("ship it", 50)
        assertTrue(p.contains("ship it"))
        assertTrue(p.contains("50"))
    }
}
