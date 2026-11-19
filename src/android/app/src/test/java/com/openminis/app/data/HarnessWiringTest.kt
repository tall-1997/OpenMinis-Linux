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
        assertTrue(
            "a compaction must open its journal bracket",
            compact.contains("beginCompactionJournal("),
        )
        assertTrue(
            "a settled compaction must close it and advance the generation",
            compact.contains("settleCompactionJournal("),
        )
        assertTrue(
            "every failure path must abandon the bracket",
            compact.contains("abandonCompactionJournal("),
        )
        assertTrue(
            "a failed attempt must not be re-entered blindly",
            compact.contains("retryAllowedNow("),
        )
    }

    @Test
    fun theClockRunsOnEveryRequestPathNotOnlyCompactedOnes() {
        val history = src("ui/chat/ChatViewModelAgentHistoryExt.kt")
        // Two call sites: the compacted path and the plain path. A single one
        // means a fresh session (the motivating case) never gets a timestamp.
        val sites = Regex("applyRequestClockNote\\(").findAll(history).count()
        assertTrue("expected >= 2 clock call sites, found $sites", sites >= 2)
        assertTrue(
            "the clock must be gated on forRequest so measurement builds stay pure",
            history.contains("if (forRequest) applyRequestClockNote("),
        )
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
        assertTrue(
            "a single-row read must hydrate the BodyStore stub",
            repo.contains("hydrateDisplayRows("),
        )
    }

    @Test
    fun mutatingToolsWriteAReceiptAndCancelledOnesCloseIt() {
        // The receipt contract lives in ChatViewModelToolReceiptExt (extracted to
        // keep the tool-turn file under its line cap), so the guard points there.
        val exec = src("ui/chat/ChatViewModelToolReceiptExt.kt")
        assertTrue(exec.contains("ToolReceiptStore.begin("))
        assertTrue(exec.contains("ToolReceiptStore.settle("))
        assertTrue(
            "a throwing dispatch must abandon its open receipt",
            exec.contains("ToolReceiptStore.abandon("),
        )
        assertTrue(
            "the operation id is what a conflict is judged on",
            exec.contains("opId = call.id"),
        )
        val turn = src("ui/chat/ChatViewModelToolTurnExec.kt")
        val seam = src("ui/chat/ChatViewModelToolReceiptExt.kt")
        assertTrue("the call must be journaled before dispatch", seam.contains("recordToolCallJournal("))
        assertTrue("the result must be journaled after dispatch", seam.contains("recordToolResultJournal("))
        assertTrue("the dispatch must go through the receipt seam", turn.contains("dispatchWithReceipt("))
    }
    
    @Test
    fun theJournalRecordsTheFinalImageBudgetedHistory() {
        // [T-session-journal-final] The digest sequence must describe the request
        // that actually left the device, so the image budget is applied FIRST.
        val journal = src("ui/chat/ChatViewModelJournalExt.kt")
        val fn = journal.substringAfter("fun ChatViewModel.finalizeRequestHistory")
        val budgetAt = fn.indexOf("applyRequestImageBudget(history)")
        val recordAt = fn.indexOf("recordRequestJournal(turn, it)")
        assertTrue("image budget must run before the journal write", budgetAt >= 0 && recordAt > budgetAt)
        val loop = src("ui/chat/ChatViewModelAgentLoopExt.kt")
        assertTrue("the loop must journal through the finalizer", loop.contains("finalizeRequestHistory(turn,"))
    }
    
    @Test
    fun theCompactionCooldownUsesItsOwnMessage() {
        // [T-compaction-cooldown-message] A suppressed retry means the previous
        // attempt FAILED and nothing is running: saying "already in progress"
        // described a job that does not exist.
        val ext = src("ui/chat/ChatViewModelCompactExt.kt")
        val branch = ext.substringAfter("retry suppressed").take(1500)
        assertTrue("new cooldown message missing: " + branch.take(200), branch.contains("vm_compact_retry_cooldown"))
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
        assertTrue(history.contains("val harness = activeHarnessConfig()"))
        assertTrue(history.contains("budgetTokensForWindow(windowTokens, applyTailBudget, harness)"))
        val screen = src("ui/settings/ModelEntryDetailScreen.kt")
        assertTrue(screen.contains("HarnessConfigStore.save("))
        val sheet = src("ui/chat/TokenUsageSheet.kt")
        assertTrue(sheet.contains("memoryUsageSummary()"))
    }
}

