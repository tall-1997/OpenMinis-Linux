package com.openminis.app.data

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-harness-wiring] The seams this branch added are pure objects with their own
 * tests; what a unit test cannot reach is whether the ViewModel actually CALLS
 * them. Two of them are load-bearing in a way that fails silently:
 *
 *  - an unsent compaction journal means a paid-for attempt leaves no evidence,
 *  - an unwired clock means the model answers from the session's first turn.
 *
 * This is the same source-drift guard the compaction gate tests already use: the
 * decision logic is entangled in ChatViewModel (Context + DB + provider to
 * construct), so the test pins the call site instead of pretending to drive it.
 */
class HarnessWiringTest {

    private fun src(rel: String): String = ProductionSources.read(rel)

    @Test
    fun compactionOpensAndClosesATransaction() {
        val compact = src("ui/chat/ChatViewModelCompactExt.kt")
        val start = compact.indexOf("beginCompactionJournal(sid,")
        val summarize = compact.indexOf("val outcome = runTwoShotCompact(")
        val commit = compact.indexOf("chatRepository.commitCompactPrefix(")
        val settle = compact.indexOf("settleCompactionJournal(sid, journalId,")
        assertTrue("journal opens before generation", start in 0 until summarize)
        assertTrue("settlement follows a real marker commit", commit > summarize && settle > commit)
        assertTrue("failure paths abandon the same session's attempt in finally",
            compact.contains("if (!succeeded) attemptId?.let { abandonCompactionJournal(sid, it, failureReason) }"))
        val cooldown = compact.indexOf("retryAllowedNow(sid,")
        assertTrue("cooldown precedes opening the attempt", cooldown in 0 until start)
    }

    @Test
    fun theClockRunsOnEveryRequestPathNotOnlyCompactedOnes() {
        val dispatch = src("ui/chat/ChatViewModelDispatchProjection.kt")
        val projection = src("ui/chat/ChatViewModelRequestProjection.kt")
        val loop = src("ui/chat/ChatViewModelAgentLoopExt.kt")
        // [T-clock-note-removed] The request-level clock note is gone: the runtime
        // context already carries the date, and the note throttled unreliably in the
        // field. What must stay true is that NOTHING re-introduces it silently: the
        // dispatch projection no longer calls requestClockNote, and the projection
        // no longer renders the clock reminder.
        assertFalse(
            "the dispatch projection must not call requestClockNote again",
            dispatch.contains("requestClockNote(sessionId, history)"),
        )
        assertTrue(dispatch.contains("val clock: RequestClockNote? = null"))
        assertTrue(dispatch.contains("clockNote = clock"))
        // The projection keeps the parameterized reminder seam; what matters is
        // that no production caller fills it any more (the dispatch layer above).
        assertTrue(projection.contains(".withRequestReminder(clockNote?.text)"))
        // The progress hook itself stays wired even though its clock half is a no-op
        // today: it is the seam a working throttle would use.
        assertTrue(loop.contains("if (streamPhase.onChunk(chunk)) projection.markModelProgress(loopSessionId)"))
        assertFalse(projection.contains(".confirm("))
    }

    @Test
    fun thePostAnchorTailAndTheDetachedTailShareOneConfigObject() {
        val tail = src("ui/chat/ChatViewModelPostAnchorTail.kt")
        assertTrue(
            "the post-anchor reserve must come from HarnessConfig, not a literal",
            tail.contains("HarnessConfig.current()"),
        )
        val detached = src("ui/chat/ChatViewModelCompactTail.kt")
        assertTrue(
            "the detached tail must subtract the same reserve",
            detached.contains("effectiveTailBudget("),
        )
    }

    @Test
    fun historyRetrievalIsDispatchedAndUsesTheRealBody() {
        val dispatch = src("ui/chat/ChatViewModelExecuteToolExt.kt")
        assertTrue(dispatch.contains("HistorySearchTool.SEARCH"))
        assertTrue(dispatch.contains("HistorySearchTool.READ"))
        val repo = src("data/repository/ChatRepositoryHistory.kt")
        assertTrue(repo.contains("readExact("))
        assertFalse(repo.contains("hydrateDisplayRows("))
    }

    @Test
    fun durableDispatchOwnsAuthorizationAndJournalBracketsTheAdapter() {
        val exec = src("ui/chat/ChatViewModelExecuteToolExt.kt")
        val adapter = exec.substringAfter("val dispatch:").substringBefore("val result = if (needsToolReceipt")
        val useAt = adapter.indexOf("recordToolUseJournal(")
        val dispatchAt = adapter.indexOf("executeToolDispatched(")
        val resultAt = adapter.indexOf("recordToolResultJournal(")
        assertTrue(useAt >= 0 && dispatchAt > useAt && resultAt > dispatchAt)
        assertTrue(exec.contains("ToolReceiptDispatch(toolReceiptStore()).run(key, toolId, authorize, dispatch)"))
        assertTrue(exec.contains("SecurityGateHolder.intercept(context, canonical, argsJson, sessionId)"))
        assertFalse(exec.contains("LegacyToolReceiptStore"))
        val turn = src("ui/chat/ChatViewModelToolTurnExec.kt")
        assertTrue(turn.contains("receiptScope = ctx.receiptScope"))
        assertTrue(turn.contains("expectedSessionId = ctx.activeSessionId, journalTurn = ctx.turn"))
        assertFalse(turn.contains("dispatchWithReceipt("))
    }

    @Test
    fun theJournalRecordsTheFinalImageBudgetedHistory() {
        val projection = src("ui/chat/ChatViewModelRequestProjection.kt")
        val journal = src("ui/chat/ChatViewModelJournalExt.kt")
        val loop = src("ui/chat/ChatViewModelAgentLoopExt.kt")
        assertTrue(projection.contains("val stableMessages = applyRequestImageBudget("))
        assertTrue(projection.contains("ChatRequest(addReminders(stableMessages)"))
        assertTrue(journal.contains("projection.outboundRequest.messages"))
        val gate = loop.indexOf("when (inLoopContextCheck(projection,")
        val record = loop.indexOf("journal.recordRequestJournal(turn, projection)")
        val send = loop.indexOf("currentProvider.streamMessage(", record)
        assertTrue("journal the final projection only after the gate and before dispatch", gate >= 0 && record > gate && send > record)
        assertFalse("journal must not budget images a second time", journal.contains("applyRequestImageBudget("))
    }

    @Test
    fun theCompactionCooldownUsesItsOwnMessage() {
        // [T-compaction-cooldown-message] A suppressed retry means the previous
        // attempt FAILED and nothing is running: saying "already in progress"
        // described a job that does not exist.
        val ext = src("ui/chat/ChatViewModelCompactExt.kt")
        val branch = ext.substringAfter("if (!CompactionTransaction.retryAllowedNow(sid, nowMs)) {")
            .substringBefore("val stages = compactStagePlan()")
        assertTrue("new cooldown message missing", branch.contains("vm_compact_retry_cooldown"))
        assertFalse("already-running string leaked into the cooldown branch", branch.contains("vm_compact_already_running"))
    }

    @Test
    fun theLoopGuardHonoursTheSettingsSwitch() {
        // [T-guard-stop-policy] A switch with no reader is a dead toggle.
        val guard = src("guard/ActionGuard.kt")
        assertTrue(guard.contains("GuardPrefs.stopEnabled()"))
        val screen = src("ui/settings/ToolLimitsSettingsScreen.kt")
        assertTrue(screen.contains("GuardPrefs.setStopEnabled("))
    }

    @Test
    fun thePerModelBudgetIsResolvedAndPersisted() {
        // [T-harness-config-resolve] Resolution per assembly + a real writer, or
        // the knob is unreachable exactly like the deleted first attempt.
        val history = src("ui/chat/ChatViewModelAgentHistoryExt.kt")
        assertTrue(history.contains("harnessConfig: HarnessConfig = activeHarnessConfig()"))
        assertTrue(history.contains("detachedTailStartForWindow(history, windowTokens, applyTailBudget, harnessConfig)"))
        assertTrue(history.contains("config = harnessConfig"))
        val screen = src("ui/settings/ModelEntryDetailScreen.kt")
        assertTrue(screen.contains("HarnessConfigStore.save("))
        val projection = src("ui/chat/ChatViewModelRequestProjection.kt")
        assertTrue(projection.contains("activeHarnessConfig(requestEntryId(attribution))"))
        assertTrue(projection.contains("harnessConfig = harnessConfig"))
    }
}
