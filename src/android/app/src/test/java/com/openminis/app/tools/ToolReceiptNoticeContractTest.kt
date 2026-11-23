package com.openminis.app.tools

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-receipt-notice-no-directive] A host receipt notice states FACTS about the
 * ledger. It must not instruct the model to act ("verify before retrying…"):
 * the model obeys such directives by writing verification prose as ordinary
 * reply text, which is the fragmented-reply pattern users report.
 *
 * Source-level contract: the instruction wording must stay out of the notice
 * builders, while the per-call tool-result advisory (which carries the
 * do-not-repeat rule at dispatch time) keeps its directive.
 */
class ToolReceiptNoticeContractTest {

    private val noticeBuilders = listOf("ui/chat/ChatViewModelToolReceipts.kt")

    @Test
    fun receiptNoticeDoesNotTellTheModelToVerify() {
        val forbidden = listOf("Verify before retrying", "verifies business success", "Do not repeat earlier operations")
        for (path in noticeBuilders) {
            // Strip comments: only string LITERALS are model-facing text.
            val src = ProductionSources.read(path)
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("//[^\n]*"), "")
            for (phrase in forbidden) {
                assertFalse(
                    "$path still contains the action directive \"$phrase\" — a notice may state facts only",
                    src.contains(phrase),
                )
            }
        }
    }

    @Test
    fun perCallAdvisoryKeepsItsOwnDirective() {
        // The dispatch boundary DID deliver a call; that result's advisory is the
        // right place for "do not repeat automatically" — assert it still exists.
        val src = ProductionSources.read("tools/ToolReceiptDispatch.kt")
        assertTrue(src.contains("Do not repeat the operation automatically"))
    }
}
