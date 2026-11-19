package com.openminis.app.data

import com.openminis.app.ProductionSources
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
        assertTrue("the tool round must reach the journal", turn.contains("recordToolJournal("))
        assertTrue("the dispatch must go through the receipt seam", turn.contains("dispatchWithReceipt("))
    }
}
