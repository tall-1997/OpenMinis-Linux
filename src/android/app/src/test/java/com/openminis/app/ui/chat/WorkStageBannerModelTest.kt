package com.openminis.app.ui.chat

import com.openminis.app.tools.AgentReportStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-work-report-ui] A banner appears exactly when the person is what the run is
 * waiting for — and carries one line of the reason.
 */
class WorkStageBannerModelTest {

    private fun of(
        stage: AgentReportStore.Stage,
        summary: String = "did a thing",
        question: String = "",
        blocker: String = "",
    ) = WorkStageBannerModel.of(stage, summary, question, blocker)

    @Test
    fun aQuestionBecomesAnAnswerBannerCarryingTheQuestion() {
        val model = of(AgentReportStore.Stage.NEEDS_ANSWER, question = "Rotate the token now?")
        assertEquals(WorkStageBannerModel.Kind.ANSWER, model!!.kind)
        assertEquals("Rotate the token now?", model.detail)
    }

    @Test
    fun aQuestionlessAnswerStageFallsBackToTheSummary() {
        val model = of(AgentReportStore.Stage.NEEDS_ANSWER, summary = "waiting on you")
        assertEquals("waiting on you", model!!.detail)
    }

    @Test
    fun aReadyRunBecomesAReviewBanner() {
        val model = of(AgentReportStore.Stage.NEEDS_REVIEW, summary = "tests pass, PR open")
        assertEquals(WorkStageBannerModel.Kind.REVIEW, model!!.kind)
        assertEquals("tests pass, PR open", model.detail)
    }

    @Test
    fun blockedAndFailedPreferTheBlockerLine() {
        val blocked = of(AgentReportStore.Stage.BLOCKED, summary = "stuck", blocker = "the token expired")
        assertEquals(WorkStageBannerModel.Kind.BLOCKED, blocked!!.kind)
        assertEquals("the token expired", blocked.detail)

        val failed = of(AgentReportStore.Stage.FAILED, summary = "build failed")
        assertEquals(WorkStageBannerModel.Kind.BLOCKED, failed!!.kind)
        assertEquals("build failed", failed.detail)
    }

    @Test
    fun workInProgressShowsNothing() {
        assertNull(of(AgentReportStore.Stage.WORKING))
        assertNull(of(AgentReportStore.Stage.WAITING))
        assertNull(of(AgentReportStore.Stage.IDLE))
    }

    @Test
    fun anEmptyReasonShowsNothingRatherThanAnEmptyBanner() {
        assertNull(of(AgentReportStore.Stage.NEEDS_REVIEW, summary = "   "))
    }

    @Test
    fun longDetailIsClippedToOneLine() {
        val model = of(
            AgentReportStore.Stage.NEEDS_REVIEW,
            summary = "line one\nline two " + "x".repeat(WorkStageBannerModel.MAX_DETAIL_CHARS),
        )
        assertTrue(model!!.detail.length <= WorkStageBannerModel.MAX_DETAIL_CHARS)
        assertTrue(!model.detail.contains('\n'))
    }
}
