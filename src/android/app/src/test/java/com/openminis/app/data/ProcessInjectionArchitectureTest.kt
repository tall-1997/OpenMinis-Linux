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
        val dispatch = ProductionSources.read("ui/chat/ChatViewModelDispatchProjection.kt")
        assertTrue("the agent loop uses the shared request projector",
            loop.contains("buildDispatchProjection(") && dispatch.contains("projectChatRequest("))
        val notes = ProductionSources.read("ui/chat/ChatViewModelHiddenNote.kt")
        assertTrue("goal is announced once", notes.contains("goalAnnouncedSha"))
        assertFalse("recall must not be pinned in transcript history before dispatch", notes.contains("newRecallReminder("))
        assertTrue("plan retry is not dropped", notes.contains("consumePlanStepRetryPrompt()"))
    }

    @Test
    fun `automatic recall is projected without writing the transcript`() {
        val projector = ProductionSources.read("ui/chat/ChatViewModelRequestProjection.kt")
        assertTrue(projector.contains("projectMemoryDeliveryPrompt("))
        assertTrue(projector.contains("withoutStoredRecallReminders()"))
        assertTrue(projector.contains("withMemoryRecallReminder(recall)"))
        assertFalse(projector.contains("recordSuccessfulMemoryDelivery("))
        assertFalse(projector.contains("commitHiddenUserNote("))
        assertFalse("Preview never starts semantic prefetch", projector.contains("prefetchSemanticRecallForRequest("))
        assertTrue(projector.contains("turnId = memoryRecallTurnIdForHistory(history)"))
        val dispatch = ProductionSources.read("ui/chat/ChatViewModelDispatchProjection.kt")
        assertTrue(dispatch.contains("prefetchSemanticRecallForRequest(history)"))
    }

    @Test
    fun `only the successful request settles memory delivery`() {
        val loop = ProductionSources.read("ui/chat/ChatViewModelAgentLoopExt.kt")
        val settle = ProductionSources.read("ui/chat/ChatViewModelRequestSettlement.kt")
        val truncated = loop.indexOf("SuccessfulOverflowKind.TRUNCATED")
        val eligible = loop.indexOf("collectDone && eligibleContextReceipt(")
        val accepted = loop.indexOf("acceptRequestProjection(")
        assertTrue("failed or truncated attempts cannot settle delivery", truncated >= 0 && eligible > truncated && accepted > eligible)
        assertTrue(settle.contains("recordSuccessfulMemoryDelivery(projection.prompt?.daily, request.systemPrompt, request.messages, projection.recall, realUserId)"))
    }

    @Test
    fun `the hidden-note helper persists once`() {
        val note = ProductionSources.read("ui/chat/ChatViewModelHiddenNote.kt")
        assertTrue(note.contains("internal suspend fun ChatViewModel.commitHiddenUserNote("))
        assertTrue("persisted so a reload does not re-inject", note.contains("chatRepository.appendTurnRows"))
        assertTrue("recheck ownership before inserting", note.contains("ownsWrite = ::ownsNote"))
        assertTrue("never rendered as a user bubble", note.contains("notePersistedUiRow(null"))
    }
}
