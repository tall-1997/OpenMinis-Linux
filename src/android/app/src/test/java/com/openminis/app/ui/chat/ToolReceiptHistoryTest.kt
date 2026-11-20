package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.tools.TOOL_RECEIPT_NOTICE
import com.openminis.app.tools.ToolErrorCode
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.ToolReceiptDecision
import com.openminis.app.tools.ToolReceiptScope
import com.openminis.app.tools.ToolReceiptState
import com.openminis.app.tools.ToolReceiptStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class ToolReceiptHistoryTest {
    @get:Rule val temp = TemporaryFolder()

    private val session = "session-1"
    private val userId = "stable-user"
    private val scope = ToolReceiptScope.userTurn(session, userId)!!
    private val resultId = UUID.randomUUID().toString()
    private val args = """{"content":"offloaded operation args","path":"note"}"""
    private val key = scope.call("file_write", args)!!

    private fun payload(type: String, value: Any): String = JSONArray()
        .put(JSONObject().put("type", type).put("value", value)).toString()

    private fun user(id: String = userId, text: String = "save a note") =
        receiptTranscriptRow(id, "user", payload("text", text))!!

    private fun assistant(wire: String = "wire", input: String = args) = receiptTranscriptRow(
        "assistant-row", "assistant", payload("toolUse", JSONObject()
            .put("toolUseId", wire).put("name", "file_write").put("input", input)),
    )!!

    private fun result(proof: String? = null, wire: String = "wire", success: Boolean = false) = receiptTranscriptRow(
        resultId, "user", payload("toolResult", JSONObject()
            .put("toolUseId", wire).put("name", "file_write").put("success", success)
            .put("output", if (success) "successful-looking text" else "accepted, but downstream failed")
            .put("receiptProof", proof)),
    )!!

    @Test fun `no result retry marker or cross-page half can settle without a complete original pair`() {
        val directory = temp.newFolder()
        val original = ToolReceiptStore(directory)
        val claim = original.claim(key, "wire") as ToolReceiptDecision.Claimed
        val reopened = ToolReceiptStore(directory)
        val useRow = assistant()
        val completedRow = result(claim.proof)
        assertFalse(receiptUserRow(completedRow))
        assertFalse(receiptUserRow(user("hidden", "<system-reminder>[working memory] internal</system-reminder>")))
        assertTrue(receiptUserRow(user()))
        assertTrue(loadedToolReceiptPairs(session, listOf(useRow, completedRow)).isEmpty())
        assertTrue(loadedToolReceiptPairs(session, listOf(user(), useRow)).isEmpty())
        assertTrue(loadedToolReceiptPairs(session, listOf(user(), useRow, result(success = true))).isEmpty())
        assertTrue(loadedToolReceiptPairs(session, listOf(user(), useRow, result(claim.proof, "wrong-wire"))).isEmpty())

        // Prepending/attaching original context to the two halves after reopen
        // produces a pair; a missing raw row explicitly resets the scanner.
        val scanner = ReceiptTranscriptScanner(session)
        scanner.consume(user())
        scanner.consume(useRow)
        scanner.consume(null)
        scanner.consume(completedRow)
        assertTrue(scanner.pairs.isEmpty())
        assertEquals(1, reopened.pending(scope.sessionSha)?.unknown)
        val restored = loadedToolReceiptPairs(session, listOf(user(), useRow, completedRow))
        assertEquals(1, restored.size)
        assertTrue(reopened.reconcile(scope.sessionSha, restored))
        val decision = reopened.prepare(key) as ToolReceiptDecision.AlreadySent
        assertEquals(ToolReceiptState.ACCEPTED, decision.state)
        assertEquals(resultId, decision.resultRef)
    }

    @Test fun `reconciliation rejects another turn arguments stale claim or wrong proof`() {
        val store = ToolReceiptStore(temp.newFolder())
        val stale = store.claim(key, "wire") as ToolReceiptDecision.Claimed
        assertTrue(store.settle(key, stale, ToolReceiptState.NOT_SENT))
        val claim = store.claim(key, "wire") as ToolReceiptDecision.Claimed
        for (rows in listOf(
            listOf(user("other-user"), assistant(), result(claim.proof)),
            listOf(user(), assistant(input = """{"path":"other"}"""), result(claim.proof)),
            listOf(user(), assistant(), result(stale.proof)),
            listOf(user(), assistant(), result("f".repeat(64))),
        )) {
            assertTrue(store.reconcile(scope.sessionSha, loadedToolReceiptPairs(session, rows)))
            assertEquals(ToolReceiptState.UNKNOWN, (store.prepare(key) as ToolReceiptDecision.AlreadySent).state)
        }
        val use = assistant().contentParts.single()
        val res = result(claim.proof).contentParts.single()
        assertTrue(persistedToolReceiptPairs(scope, "assistant-row", resultId, listOf(use, use), listOf(res)).isEmpty())
        assertTrue(persistedToolReceiptPairs(scope, "assistant-row", null, listOf(use), listOf(res)).isEmpty())
    }

    @Test fun `host reminder rows on reopen preserve the real owner and do not reauthorize an unknown call`() {
        val directory = temp.newFolder()
        val store = ToolReceiptStore(directory)
        val claim = store.claim(key, "wire") as ToolReceiptDecision.Claimed
        val host = receiptTranscriptRow(
            "host-row", "user",
            """[{"type":"text","value":"Continue the work."},{"type":"receiptOrigin","value":"host"}]""",
        )!!
        val resumed = user("resume-row", "<system-reminder>The user stopped the previous response but now wants to continue.</system-reminder>")
        val dropped = user("stream-drop", com.openminis.app.agent.StreamDropReminder.TEXT)
        val userQuote = user("another-real-user", "What does 'The user stopped the previous response' mean?")
        assertTrue(host.isHostGenerated)
        assertFalse(receiptUserRow(host))
        assertFalse(receiptUserRow(resumed))
        assertFalse(receiptUserRow(dropped))
        assertTrue(receiptUserRow(userQuote))
        val rows = listOf(user(), host, resumed, dropped, assistant(), result(claim.proof))
        val reopened = ToolReceiptStore(directory)
        val next = scope.afterTranscript(receiptAfterResultRowId(rows, userId)).call("file_write", args)!!
        assertEquals(ToolReceiptState.UNKNOWN, (reopened.prepare(next) as ToolReceiptDecision.AlreadySent).state)
        assertTrue(reopened.reconcile(scope.sessionSha, loadedToolReceiptPairs(session, rows)))
        assertEquals(ToolReceiptDecision.Ready, reopened.prepare(next))
    }

    @Test fun `request projection can advance only with both persisted halves and the same user anchor`() {
        val done = result("a".repeat(64))
        val use = assistant()
        assertEquals(resultId, receiptAfterResultRowId(listOf(user(), use, done), userId))
        assertNull(receiptAfterResultRowId(listOf(use, done), userId))
        assertNull(receiptAfterResultRowId(listOf(user(), done), userId))
        assertNull(receiptAfterResultRowId(listOf(user(), use.copy(dbMessageId = null), done), userId))
        assertNull(receiptAfterResultRowId(listOf(user(), use, done.copy(dbMessageId = null)), userId))
        assertNull(receiptAfterResultRowId(listOf(user(), use, done.copy(contentParts = emptyList())), userId))
        assertNull(receiptAfterResultRowId(listOf(user(), use, result("a".repeat(64), wire = "other")), userId))
        assertNull(receiptAfterResultRowId(listOf(user(), use, done, user("new-user")), userId))
    }

    @Test fun `script advisory appends after sandbox output instead of replacing it`() {
        val script = ToolExecutionResult("printed step 1\nprinted step 2", true, toolTitle = "execute_code")
        val advisory = ToolExecutionResult(
            "$TOOL_RECEIPT_NOTICE Delivery is pending confirmation. It may already have occurred.",
            false, errorCode = ToolErrorCode.RECEIPT_UNCONFIRMED,
        )
        val settled = scriptSettledResult(script, advisory)
        assertTrue(settled.output.startsWith("printed step 1"))
        assertTrue(settled.output.contains("printed step 2"))
        assertTrue(settled.output.contains(advisory.output))
        assertEquals(script.success, settled.success)
        assertEquals(script.toolTitle, settled.toolTitle)
        // A script with no output still surfaces the advisory verbatim.
        val blank = scriptSettledResult(ToolExecutionResult("", false), advisory)
        assertEquals(advisory.output, blank.output)
        assertFalse(blank.success)
        // An advisory with no text cannot erase the sandbox output either.
        val emptyNote = scriptSettledResult(script, ToolExecutionResult("", false))
        assertEquals(script.output, emptyNote.output)
    }

    @Test fun `in-process replay does not manufacture a fresh proof`() = kotlinx.coroutines.runBlocking {
        val ledger = ToolExecutionLedger()
        val real = AgentContentPart.ToolResult("wire", "file_write", "done", receiptProof = "a".repeat(64))
        assertEquals(real, ledger.executeOnce("wire", "file_write", "sha") { real })
        val replay = ledger.executeOnce("wire", "file_write", "sha") { error("re-dispatched") }
        assertEquals("done", replay.content)
        assertNull(replay.receiptProof)
    }
}
