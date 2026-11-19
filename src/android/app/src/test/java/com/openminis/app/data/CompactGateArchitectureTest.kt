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
        assertTrue("the successful pass count goes straight to the transactional commit",
            compact.contains("source, coveredCount, outcome.summary"))
        val reducer = ProductionSources.read("data/repository/ChatRepositoryCompact.kt")
        assertTrue("marker anchors at the last submitted row", reducer.contains("source.rows[coveredCount - 1]"))
        assertTrue("the new marker must publish only after commit", compact.indexOf("_cachedLatestMarker = marker") >
            compact.indexOf("chatRepository.commitCompactPrefix("))
    }

    /**
     * [P3-4] Digest re-verification hashes up to MAX_COMPACT_READ_BYTES of
     * body content. It must run BEFORE the write transaction opens, so the
     * hashing never holds the DB write lock; inside the transaction only the
     * cheap row-set/order re-check runs.
     */
    @Test
    fun `compact body digest verification stays outside the marker write transaction`() {
        val reducer = ProductionSources.read("data/repository/ChatRepositoryCompact.kt")
        val preVerify = reducer.indexOf("verifyCompactRows(source.sessionId, source.start, expected)")
        val txnOpen = reducer.indexOf("withTransaction {", preVerify)
        assertTrue("digest verification must run before the write transaction opens",
            preVerify in 0 until txnOpen)
        // [P3-1 round-2] The whole function REMAINDER after the transaction opens
        // is guarded — including reduceCompactMarker(...).also { insert } — so a
        // hash added inside the commit lambda cannot slip past the tripwire.
        val functionEnd = reducer.indexOf("private fun reduceCompactMarker", txnOpen)
        val inside = reducer.substring(txnOpen, if (functionEnd > txnOpen) functionEnd else reducer.length)
        assertTrue("no digest hashing inside the write transaction",
            !inside.contains("verifyCompactRows") && !inside.contains("Digest.sha256Hex") &&
                !inside.contains("compactInlineDigest") && !inside.contains("resolveCompactStart"))
        assertTrue("the transaction must still re-check the row set/ordering",
            inside.contains("dao.compactSourceRows(source.sessionId, source.start"))
        assertTrue("rejection semantics must stay", reducer.contains(
            "Conversation rows changed while summarizing. Retry compaction."))
    }
}
