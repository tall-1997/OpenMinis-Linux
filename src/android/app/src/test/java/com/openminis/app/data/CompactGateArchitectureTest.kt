package com.openminis.app.data

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
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
        assertTrue(
            "a failed compaction must not be reportable as success",
            twoShot.contains("succeeded = false"),
        )
        val compact = ProductionSources.read("ui/chat/ChatViewModelCompactExt.kt")
        assertTrue("marker uses the covered count", compact.contains("compactedCount = coveredCount"))
        assertTrue("anchor resolves inside the covered prefix", compact.contains("coveredEndIdx"))
        assertTrue(
            "a failed compaction must not write a marker",
            compact.contains("coveredCountFor("),
        )
        assertTrue(
            "the compaction range must be capped to a finishable prefix",
            compact.contains("cappedCompactEnd("),
        )
        assertTrue(
            "state notes are re-injected only for a real request",
            ProductionSources.read("ui/chat/ChatViewModelAgentHistoryExt.kt")
                .contains("if (forRequest)"),
        )
        assertTrue(
            "the <32K tier must weigh the real request projection",
            ProductionSources.read("ui/chat/ChatViewModelTokenExt.kt")
                .contains("PolicyMeasurement.REQUEST -> effectiveAgentHistoryUncounted()"),
        )
        val main = ProductionSources.mainRoot() ?: error("production root missing")
        val res = java.io.File(main, "../../../../res")
        res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }
            .orEmpty()
            .forEach { dir ->
                val strings = java.io.File(dir, "strings.xml")
                if (strings.isFile) {
                    val text = strings.readText()
                    assertFalse(
                        "$strings still claims truncation",
                        text.contains("older context is truncated") ||
                            text.contains("truncate older context"),
                    )
                }
            }
        assertFalse(
            "a failed compaction no longer truncates; the notice must not claim it",
            twoShot.contains("Truncating older context"),
        )
        assertFalse(
            "a failed compaction no longer drops older context",
            twoShot.contains("before dropping older context"),
        )
        assertTrue(
            "a dropped state note must be re-injected into the request, not re-committed",
            ProductionSources.read("ui/chat/ChatViewModelAgentHistoryExt.kt")
                .contains("evictedStateNote("),
        )
        assertFalse(
            "measurement/projection must not clear the delivery sha",
            ProductionSources.read("ui/chat/ChatViewModelAgentHistoryExt.kt")
                .contains("stateSnapshotSha = null"),
        )
        assertTrue(
            "detached tails must be token-bounded",
            ProductionSources.read("ui/chat/ChatViewModelCompactTail.kt")
                .contains("detachedTailSize("),
        )
    }

    @Test
    fun `standing rules are assembled regardless of the memory toggle`() {
        // [T-rules-independent-of-memory] Only the diary side may follow the
        // session memory switch; rules are instructions (same contract as the
        // DISABLED prompt section states).
        val prompt = ProductionSources.read("ui/chat/ChatViewModelPromptExt.kt")
        assertFalse(
            "the whole assembly must not be gated by memoryOn",
            prompt.contains("val memoryAssembly = if (memoryOn)"),
        )
        assertTrue(
            "daily window stays gated",
            prompt.contains("daily = if (memoryOn) dailyFragments else emptyList()"),
        )
    }
}
