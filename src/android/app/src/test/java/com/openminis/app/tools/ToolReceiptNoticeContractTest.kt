package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-receipt-notice-removed] The request-level receipt reminder is gone.
 *
 * It ended with "Verify before retrying…" — an instruction the model obeyed by
 * writing verification prose ("✓ 已验证") as ordinary reply text between tool
 * calls, which is the fragmented-reply pattern users report; and while one
 * interrupted call stayed unresolved it re-fired on every request.
 *
 * Duplicate-delivery protection must still exist, just NOT as a dialogue-level
 * reminder: the tool gate refuses replays, and the per-call result carries the
 * advisory. Those are asserted here so "no nag" never becomes "no guard".
 */
class ToolReceiptNoticeContractTest {

    private fun stripComments(src: String): String = src
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun requestLevelReceiptReminderIsGone() {
        val builder = stripComments(ProductionSources.read("ui/chat/ChatViewModelToolReceipts.kt"))
        for (gone in listOf("pendingToolReceiptNotice", "boundedReceiptNotice", "loopReceiptAnchor")) {
            assertFalse("$gone must stay deleted — no request-level receipt reminder", builder.contains(gone))
        }
        val projection = stripComments(ProductionSources.read("ui/chat/ChatViewModelRequestProjection.kt"))
        assertFalse("the reminder hook must not survive in request assembly", projection.contains("receiptNotice"))
    }

    @Test
    fun theActionDirectiveIsGoneFromEveryProjectionPath() {
        val paths = listOf(
            "ui/chat/ChatViewModelToolReceipts.kt",
            "ui/chat/ChatViewModelRequestProjection.kt",
            "ui/chat/ChatViewModelGroupChatExt.kt",
            "ui/chat/ChatViewModelSupervisorExt.kt",
        )
        for (path in paths) {
            val src = stripComments(ProductionSources.read(path))
            assertFalse(
                "$path still contains the directive that made the model emit verification prose",
                src.contains("Verify before retrying"),
            )
        }
    }

    @Test
    fun replayProtectionStaysAtTheToolBoundary() {
        val gate = stripComments(ProductionSources.read("tools/ToolReceiptDispatch.kt"))
        assertTrue("needsToolReceipt gate must remain", gate.contains("fun needsToolReceipt"))
        // [T-receipt-fact-not-order] The advisory still rides the tool result,
        // but as a STATEMENT of the delivery state. The old imperative
        // ("Do not repeat the operation automatically" / "Verify
        // independently") is what made the model break off into verification
        // prose, so the contract now forbids the imperative — not the notice.
        assertTrue("advisory must ride the tool result itself", gate.contains("TOOL_RECEIPT_NOTICE \$status"))
        assertTrue(
            "the advisory must not order the model to verify",
            !gate.contains("Verify independently") && !gate.contains("Do not repeat the operation automatically"),
        )
        val exec = stripComments(ProductionSources.read("ui/chat/ChatViewModelExecuteToolExt.kt"))
        assertTrue("execute path must still consult the gate", exec.contains("needsToolReceipt(canonical"))
    }
}
