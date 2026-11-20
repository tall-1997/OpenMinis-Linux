package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tool-pairing-single-authority] One implementation of "where may this
 * list be cut". The cases below are the ones the three former copies disagreed
 * on; if any of them regresses, the post-anchor budget, the resident window and
 * the request repair start disagreeing again.
 */
class ToolPairingCoreTest {

    private fun user(text: String = "") =
        LLMMessage(role = LLMMessage.Role.USER, content = text)

    private fun assistant(vararg parts: AgentContentPart) =
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = parts.toList(),
        )

    private fun carrier(id: String, name: String = "shell_execute") =
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(AgentContentPart.ToolResult(id, name, "out")),
        )

    private fun use(id: String, name: String = "shell_execute") =
        AgentContentPart.ToolUse(id, name, org.json.JSONObject("{}"))

    private fun result(id: String, name: String = "shell_execute") =
        AgentContentPart.ToolResult(id, name, "out")

    @Test
    fun aCompletePairYieldsLegalCutsOnBothSides() {
        val history = listOf(user("go"), assistant(use("c1")), carrier("c1"))
        val legal = ToolPairingCore.legalCuts(history)
        // A cut keeps the SUFFIX and discards the prefix, so a cut is legal
        // whenever everything after it is pairing-clean.
        assertTrue("cut before the user turn", legal[0])
        assertTrue("cut before the call keeps call+result together", legal[1])
        // Cutting between the call and its result would orphan the call.
        assertFalse("cut between call and result", legal[2])
    }

    @Test
    fun anInFlightFinalCallIsTolerated() {
        val history = listOf(user("go"), assistant(use("c1")))
        val legal = ToolPairingCore.legalCuts(history)
        assertTrue(legal[0])
        assertFalse("the final assistant may still owe a result", legal[2])
    }

    @Test
    fun aMidHistoryCallWithoutResultIsNotAToleratedCut() {
        // The call at index 1 can never be answered. Keeping the suffix that
        // CONTAINS it (cut at 0 or 1) is unbalanced; cutting it away is fine.
        val history = listOf(user("a"), assistant(use("c1")), user("b"))
        val legal = ToolPairingCore.legalCuts(history)
        assertFalse("suffix still contains the unanswered call", legal[1])
        assertTrue("cutting the call away leaves a clean suffix", legal[2])
    }

    @Test
    fun aCutCannotReassignAnEarlierResultToAReusedId() {
        val history = listOf(user("go"), assistant(use("c1")), assistant(use("c1"), result("c1")))
        val legal = ToolPairingCore.legalCuts(history)
        assertTrue(legal[1])
        assertFalse("the result belongs to the first call, not the in-flight reuse", legal[2])
        assertTrue(ToolPairingCore.hasPendingUse(listOf(carrier("c1"), assistant(use("c1")))))
    }

    @Test
    fun aResultBeforeItsCallIsNotBalanced() {
        val history = listOf(carrier("c1"), assistant(use("c1")))
        val legal = ToolPairingCore.legalCuts(history)
        assertFalse(legal[0])
    }

    @Test
    fun theResponsesCombinedIdMatchesItsCallHalf() {
        // OpenAI Responses stores "call_id|fc_id" on one side and the plain
        // call_id on the other; a raw comparison calls that an orphan.
        val history = listOf(
            user("go"),
            assistant(use("call_1|fc_1")),
            carrier("call_1"),
        )
        val legal = ToolPairingCore.legalCuts(history)
        assertTrue(legal[0])
        // Cutting between the call and its result is the orphan case.
        assertFalse(legal[2])
    }

    @Test
    fun aNameMismatchIsNotAPair() {
        val history = listOf(
            user("go"),
            assistant(use("c1", "shell_execute")),
            carrier("c1", "file_read"),
        )
        assertFalse("a different tool name is a different call", ToolPairingCore.legalCuts(history)[1])
    }

    @Test
    fun firstAndLastLegalCutRespectTheRequestedIndex() {
        val history = listOf(
            user("a"), assistant(use("c1")), carrier("c1"),
            user("b"), assistant(use("c2")), carrier("c2"),
        )
        assertEquals(0, ToolPairingCore.firstLegalCutAtOrAfter(history, 0))
        // Index 5 is the c2 result carrier: cutting there would orphan it, so
        // the nearest legal cut at or before it is index 4 (before c2's call).
        assertEquals(4, ToolPairingCore.lastLegalCutAtOrBefore(history, 5))
        assertNull(ToolPairingCore.firstLegalCutAtOrAfter(history, history.size))
    }

    @Test
    fun pendingUseIsDetectedUntilTheResultLands() {
        val group = arrayListOf(user("go"), assistant(use("c1")))
        assertTrue(ToolPairingCore.hasPendingUse(group))
        group += carrier("c1")
        assertFalse(ToolPairingCore.hasPendingUse(group))
    }

    @Test
    fun twoUsesSharingAKeyWithOneResultIsStillPending() {
        // [T-tool-pairing-single-authority] COUNT semantics, not set membership.
        // A reload or a relay rewriting ids can leave two calls with the same
        // key and a single result; the set-based check called that fully
        // answered and let the tail budget split between the two calls, which is
        // exactly the orphaning bug this object exists to close.
        val group = listOf(assistant(use("c1"), use("c1")), carrier("c1"))
        assertTrue(ToolPairingCore.hasPendingUse(group))
        // One result per call is fully answered.
        val answered = listOf(assistant(use("c1"), use("c1")), carrier("c1"), carrier("c1"))
        assertFalse(ToolPairingCore.hasPendingUse(answered))
    }

    @Test
    fun aResultWithoutItsCallLeavesNothingPending() {
        val group = listOf(user("go"), carrier("c1"))
        assertFalse(ToolPairingCore.hasPendingUse(group))
    }

    @Test
    fun emptyHistoryHasOneLegalCut() {
        val legal = ToolPairingCore.legalCuts(emptyList())
        assertEquals(1, legal.size)
        assertTrue(legal[0])
    }

    @Test
    fun messagesWithoutToolPartsAreAlwaysCuttable() {
        val history = listOf(user("a"), assistant(AgentContentPart.Text("hi")), user("b"))
        val legal = ToolPairingCore.legalCuts(history)
        assertTrue(legal[0])
        assertTrue(legal[1])
        assertTrue(legal[2])
    }
}
