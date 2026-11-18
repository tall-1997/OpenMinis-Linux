package com.openminis.app.data

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-gate-unblinded][T-compact-reduced-anchor] Architecture guards for
 * the two silent-loss paths a long run takes after its first compaction.
 *
 * Both are wiring fixes with no pure call site of their own, so the source
 * itself is the contract:
 *  - the compact gate must measure the UN-BUDGETED slice (otherwise the tail
 *    cap makes the threshold unreachable and the marker freezes forever);
 *  - a reduced compaction may only advance the marker over the prefix it
 *    actually summarized.
 */
class CompactGateArchitectureTest {

    @Test
    fun `compact gate measures the un-budgeted slice`() {
        val token = ProductionSources.read("ui/chat/ChatViewModelTokenExt.kt")
        assertTrue(
            "the gate's floor must disable the postAnchor budget",
            token.contains("applyTailBudget = false"),
        )
        val history = ProductionSources.read("ui/chat/ChatViewModelAgentHistoryExt.kt")
        assertTrue(
            "the assembly must expose an un-budgeted measurement mode",
            history.contains("applyTailBudget: Boolean = true"),
        )
        assertTrue(
            "the budget itself has one source",
            ProductionSources.read("ui/chat/ChatViewModelAgentHistoryExt.kt")
                .contains("PostAnchorTail.budgetTokensForWindow("),
        )
    }

    @Test
    fun `reduced compaction anchors only over the summarized prefix`() {
        val twoShot = ProductionSources.read("ui/chat/ChatViewModelTwoShotCompactExt.kt")
        assertTrue("pass carries its covered prefix", twoShot.contains("CompactOutcome("))
        assertTrue("reduced pass reports its own size", twoShot.contains("CompactOutcome(text, input.size)"))
        val compact = ProductionSources.read("ui/chat/ChatViewModelCompactExt.kt")
        assertTrue("marker uses the covered count", compact.contains("compactedCount = coveredCount"))
        assertTrue("anchor resolves inside the covered prefix", compact.contains("coveredEndIdx"))
        assertTrue(
            "a drop must re-arm the state snapshot independently of the ledger",
            ProductionSources.read("ui/chat/ChatViewModelAgentHistoryExt.kt")
                .contains("stateHydrationPending = true"),
        )
    }
}
