package com.openminis.app.data

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-no-repeated-injection] Architecture guard: per-turn state is never rebuilt
 * into every request.
 *
 * Regression this pins (the reported "keeps circling"): the agent loop used to
 * re-inject stage/lanes/jobs/plan-board/recall/goal into a fresh request every
 * turn. ZCode's shape is the opposite — a notice is written into the
 * transcript once, and current state lives in tool-result history.
 */
class ProcessInjectionArchitectureTest {

    @Test
    fun `the agent loop injects notices once into history`() {
        val loop = ProductionSources.read("ui/chat/ChatViewModelAgentLoopExt.kt")
        assertFalse(
            "process blocks must not be grafted onto the tool_result carrier",
            loop.contains("withGoalAndPlanContext("),
        )
        assertFalse(
            "per-request process tail was the repeated-injection bug",
            loop.contains("processInjectedSystemPrompt("),
        )
        assertTrue("notices are committed once", loop.contains("commitSessionNoticesOnce("))
        assertTrue(
            "the request prompt is only date-refreshed",
            loop.contains("refreshRuntimeContextDate("),
        )
        val notes = ProductionSources.read("ui/chat/ChatViewModelHiddenNote.kt")
        assertTrue("goal is announced once", notes.contains("goalAnnouncedSha"))
        assertTrue("recall is committed once", notes.contains("newRecallReminder()"))
        assertTrue("recall re-surfaces only after eviction", notes.contains("announcedRecallCarrier"))
        assertTrue("plan retry is not dropped", notes.contains("consumePlanStepRetryPrompt()"))
    }

    @Test
    fun `recall never re-selects a delivered line`() {
        val ext = ProductionSources.read("ui/chat/ChatViewModelPlanContextExt.kt")
        assertTrue("fresh-hit filter must exist", ext.contains("internal fun <T> recallFreshHits("))
        assertTrue("delivered lines are remembered", ext.contains("announcedRecallKeys"))
        assertFalse("the per-turn recall part is gone", ext.contains("internal fun ChatViewModel.liveRecallPrompt("))
    }

    @Test
    fun `the hidden-note helper persists once`() {
        val note = ProductionSources.read("ui/chat/ChatViewModelHiddenNote.kt")
        assertTrue(note.contains("internal suspend fun ChatViewModel.commitHiddenUserNote("))
        assertTrue("persisted so a reload does not re-inject", note.contains("chatRepository.appendMessage"))
        assertTrue("never rendered as a user bubble", note.contains("notePersistedUiRow(null"))
    }
}
