package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-work-report] The report store is the structured half of "does the run know
 * where it is". These pin the stage derivation table (prototype order: question
 * outranks ready, ready outranks waiting) and the write-time contract.
 */
class AgentReportToolTest {

    private val sid = "chat-report"

    @Before
    fun reset() {
        AgentReportStore.clear(sid, null)
    }

    @After
    fun tearDown() {
        AgentReportStore.clear(sid, null)
    }

    @Test
    fun noReportMeansWorkingWithNoPrompt() {
        assertEquals(AgentReportStore.Stage.WORKING, AgentReportStore.stageOf(null))
        assertNull(AgentReportStore.stagePrompt(sid, null))
    }

    @Test
    fun statusMapsToStage() {
        fun stageFor(status: String) = AgentReportStore.stageOf(
            AgentReportStore.Report(status = status, summary = "s"),
        )
        assertEquals(AgentReportStore.Stage.NEEDS_REVIEW, stageFor("ready"))
        assertEquals(AgentReportStore.Stage.BLOCKED, stageFor("blocked"))
        assertEquals(AgentReportStore.Stage.FAILED, stageFor("failed"))
        assertEquals(AgentReportStore.Stage.WAITING, stageFor("waiting"))
        assertEquals(AgentReportStore.Stage.IDLE, stageFor("dropped"))
        assertEquals(AgentReportStore.Stage.WORKING, stageFor("progress"))
    }

    @Test
    fun anOpenQuestionOutranksEveryStatus() {
        // A question asked while still working must still surface: that is the
        // difference between "busy" and "waiting on a person".
        val report = AgentReportStore.Report(
            status = "progress",
            summary = "s",
            question = "Ship it?",
        )
        assertEquals(AgentReportStore.Stage.NEEDS_ANSWER, AgentReportStore.stageOf(report))
    }

    @Test
    fun recordKeepsTheNewestReport() {
        AgentReportStore.record(sid, null, "progress", "step one")
        AgentReportStore.record(sid, null, "ready", "done", evidence = "tests pass")
        val latest = AgentReportStore.latest(sid, null)
        assertEquals("ready", latest?.status)
        assertEquals(2, AgentReportStore.history(sid, null).size)
    }

    @Test
    fun historyIsBounded() {
        repeat(AgentReportStore.MAX_HISTORY + 5) { i ->
            AgentReportStore.record(sid, null, "progress", "step $i")
        }
        assertEquals(AgentReportStore.MAX_HISTORY, AgentReportStore.history(sid, null).size)
        assertEquals("step ${AgentReportStore.MAX_HISTORY + 4}", AgentReportStore.latest(sid, null)?.summary)
    }

    @Test
    fun summaryIsRequired() {
        val error = runCatching { AgentReportStore.record(sid, null, "progress", "  ") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun blockedAndFailedRequireABlocker() {
        assertTrue(
            runCatching { AgentReportStore.record(sid, null, "blocked", "cannot continue") }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { AgentReportStore.record(sid, null, "failed", "cannot continue") }
                .exceptionOrNull() is IllegalArgumentException,
        )
        // ...and with one, they land.
        AgentReportStore.record(sid, null, "blocked", "needs the API key", blocker = "no credentials")
        assertEquals("blocked", AgentReportStore.latest(sid, null)?.status)
    }

    @Test
    fun unknownStatusIsRejected() {
        val error = runCatching { AgentReportStore.record(sid, null, "done", "s") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("progress"))
    }

    @Test
    fun thePromptCarriesStageSummaryAndQuestion() {
        AgentReportStore.record(
            sid, null, "blocked", "cannot reach the API",
            blocker = "the token expired", question = "Rotate the token now?",
        )
        val prompt = AgentReportStore.stagePrompt(sid, null)!!
        assertTrue(prompt.contains("stage: needs-answer"))
        assertTrue(prompt.contains("cannot reach the API"))
        assertTrue(prompt.contains("the token expired"))
        assertTrue(prompt.contains("Rotate the token now?"))
        assertTrue(prompt.contains("agent_report"))
    }

    @Test
    fun renderTellsTheModelWhatToDoNext() {
        val review = AgentReportStore.render(AgentReportStore.Report(status = "ready", summary = "s"))
        assertTrue(review.contains("needs-review"))
        val answer = AgentReportStore.render(
            AgentReportStore.Report(status = "blocked", summary = "s", blocker = "b", question = "q"),
        )
        assertTrue(answer.contains("needs-answer"))
        assertTrue(answer.contains("let the user answer"))
    }

    @Test
    fun theToolRecordsAndReportsTheStage() {
        val ok = AgentReportTool.execute(
            """{"tool_title":"t","status":"progress","summary":"did a thing"}""",
            sid,
            null,
        )
        assertTrue(ok.success)
        assertTrue(ok.output.contains("stage is now working"))
        assertEquals("progress", AgentReportStore.latest(sid, null)?.status)
    }

    @Test
    fun theToolSurfacesContractBreachesAsErrors() {
        val bad = AgentReportTool.execute(
            """{"tool_title":"t","status":"blocked","summary":"stuck"}""",
            sid,
            null,
        )
        assertFalse(bad.success)
        assertTrue(bad.output.contains("blocker"))

        val invalid = AgentReportTool.execute(
            """{"tool_title":"t","status":"finished","summary":"s"}""",
            sid,
            null,
        )
        assertFalse(invalid.success)
    }

    @Test
    fun clearingRemovesThePrompt() {
        AgentReportStore.record(sid, null, "ready", "done")
        AgentReportTool.execute("""{"tool_title":"t","op":"clear"}""", sid, null)
        assertNull(AgentReportStore.stagePrompt(sid, null))
    }
}
