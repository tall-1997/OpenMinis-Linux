package com.openminis.app.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ToolReceiptDispatchTest {
    @get:Rule val temp = TemporaryFolder()

    private val turn = requireNotNull(ToolReceiptScope.userTurn("session-a", "user-a"))
    private fun key(payload: String) = requireNotNull(turn.call("file_write", """{"path":"$payload"}"""))
    private fun ToolReceiptStore.state(key: ToolReceiptKey): ToolReceiptState = when (val decision = prepare(key)) {
        is ToolReceiptDecision.AlreadySent -> decision.state
        ToolReceiptDecision.Ready -> ToolReceiptState.NOT_SENT
        else -> error("Unexpected decision: $decision")
    }

    @Test fun `same intent is independent of wire id alias order and title but bound to real user row`() = runBlocking {
        val directory = temp.newFolder()
        val store = ToolReceiptStore(directory)
        val first = turn.call("bash", """{"timeout":1,"tool_title":"first","command":"opaque"}""")!!
        val retry = turn.call("shell_execute", """{"command":"opaque","tool_title":"second","timeout":120,"delay":5,"resource_class":"heavy"}""")!!
        assertEquals(first.logicalSha, retry.logicalSha)
        assertNotEquals(first.logicalSha, turn.call("bash", """{"command":"opaque","cwd":"/other"}""")!!.logicalSha)
        assertNotEquals(first.logicalSha, turn.call("bash", """{"command":"opaque  "}""")!!.logicalSha)
        assertNull(ToolReceiptScope.userTurn("session-a", null))
        assertNull(turn.call("bash", "[1]"))
        val otherTurn = ToolReceiptScope.userTurn("session-a", "user-b")!!
        val otherSession = ToolReceiptScope.userTurn("session-b", "user-a")!!
        val args = """{"command":"opaque","opts":{"b":2,"a":[1,"001"]}}"""
        assertNotEquals(first.logicalSha, otherTurn.call("bash", args)!!.logicalSha)
        assertNotEquals(first.logicalSha, otherSession.call("bash", args)!!.logicalSha)

        val calls = AtomicInteger()
        val approvals = AtomicInteger()
        suspend fun send(target: ToolReceiptKey, wire: String) = ToolReceiptDispatch(ToolReceiptStore(directory)).run(
            target, wire, authorize = { approvals.incrementAndGet(); null },
        ) { delivery ->
            assertEquals(ToolReceiptState.UNKNOWN, store.state(target))
            calls.incrementAndGet()
            delivery.completed(ToolExecutionResult("done", true))
        }
        assertTrue(send(first, "provider-old").success)
        val refused = send(retry, "provider-renumbered")
        assertFalse(refused.success)
        assertTrue(refused.output.contains("accepted"))
        assertNull(refused.receiptProof)
        assertTrue(send(otherTurn.call("bash", args)!!, "provider-old").success)
        assertEquals(2, calls.get())
        assertEquals(2, approvals.get())
    }

    @Test fun `pending deliveries are counted per anchor not across the whole session`() = runBlocking {
        val directory = temp.newFolder()
        val store = ToolReceiptStore(directory)
        val otherTurn = ToolReceiptScope.userTurn("session-a", "user-b")!!
        suspend fun strand(target: ToolReceiptKey, wire: String) = ToolReceiptDispatch(ToolReceiptStore(directory)).run(
            target, wire, authorize = { null },
        ) { delivery ->
            // A transport loss: claimed, never settled — the pin this fix scopes.
            throw java.io.IOException("connection lost")
        }
        strand(key("lost-this-turn"), "wire-a")
        strand(otherTurn.call("file_write", """{"path":"older"}""")!!, "wire-b")

        // The interrupted delivery pins its own anchor's ledger...
        assertEquals(ToolReceiptStore.Pending(1, 0), store.pending(turn.sessionSha, turn.parentSha))
        // ...without counting the other anchor's, and never the session-wide sum.
        assertEquals(ToolReceiptStore.Pending(1, 0), store.pending(turn.sessionSha, otherTurn.parentSha))
        assertEquals(ToolReceiptStore.Pending(2, 0), store.pending(turn.sessionSha))
    }

    @Test fun `approval rejection and cancellation before claim never dispatch and later attempts reauthorize`() = runBlocking {
        val store = ToolReceiptStore(temp.newFolder())
        val dispatch = ToolReceiptDispatch(store)
        val key = key("before-send")
        val enteredApproval = CompletableDeferred<Unit>()
        val denial = dispatch.run(key, "declined", {
            assertEquals(ToolReceiptState.NOT_SENT, store.state(key))
            ToolExecutionResult("User rejected", false, ToolErrorCode.PERMISSION_DENIED)
        }) { error("denied call dispatched") }
        assertTrue(denial.notSent)
        assertEquals(ToolErrorCode.PERMISSION_DENIED, denial.errorCode)
        val waiting = launch {
            dispatch.run(key, "cancelled-approval", {
                enteredApproval.complete(Unit)
                awaitCancellation()
            }) { error("cancelled approval dispatched") }
        }
        enteredApproval.await()
        waiting.cancelAndJoin()
        assertEquals(ToolReceiptState.NOT_SENT, store.state(key))
        val typed = dispatch.run(key, "never-started", { null }) {
            it.completed(ToolExecutionResult("locally rejected", false, notSent = true))
        }
        assertTrue(typed.notSent)
        assertEquals(ToolReceiptState.NOT_SENT, store.state(key))
        var approved = false
        assertTrue(dispatch.run(key, "approved", { approved = true; null }) {
            assertTrue(approved)
            it.completed(ToolExecutionResult("done", true))
        }.success)
    }

    @Test fun `generic success timeout and transport failure are not delivery evidence`() = runBlocking {
        val directory = temp.newFolder()
        val dispatch = ToolReceiptDispatch(ToolReceiptStore(directory))
        val placeholder = dispatch.run(key("placeholder"), "a", { null }) {
            ToolExecutionResult("Operation accepted (synthetic)", true)
        }
        assertFalse(placeholder.success)
        assertNull(placeholder.receiptProof)
        val timedOut = dispatch.run(key("timeout"), "b", { null }) {
            it.completed(ToolExecutionResult("partial", false, ToolErrorCode.TIMEOUT, timedOut = true))
        }
        assertTrue(timedOut.timedOut)
        assertFalse(timedOut.notSent)
        assertEquals(ToolErrorCode.RECEIPT_UNCONFIRMED, timedOut.errorCode)
        val dropped = dispatch.run(key("network"), "c", { null }) { throw IOException("network lost") }
        assertFalse(dropped.timedOut)
        assertTrue(dropped.output.contains("pending confirmation"))
        val restarted = ToolReceiptStore(directory)
        assertEquals(3, restarted.pending(turn.sessionSha, turn.parentSha)?.unknown)
        assertEquals(0, restarted.pending(turn.sessionSha)?.acceptedWithoutResult)
        val replay = ToolReceiptDispatch(restarted).run(key("network"), "new-c", { error("re-prompted") }) {
            error("re-dispatched")
        }
        assertEquals(ToolErrorCode.RECEIPT_UNCONFIRMED, replay.errorCode)
    }

    @Test fun `parallel batch settles only the accepted member and a waiting duplicate never starts`() = runBlocking {
        val directory = temp.newFolder()
        val dispatch = ToolReceiptDispatch(ToolReceiptStore(directory))
        val pendingKey = key("interrupted")
        val doneKey = key("done")
        val entered = CompletableDeferred<Unit>()
        val interrupted = launch {
            dispatch.run(pendingKey, "wire-1", { null }) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        val done = async {
            dispatch.run(doneKey, "wire-2", { null }) {
                // A terminal business failure can still prove delivery.
                it.completed(ToolExecutionResult(
                    "downstream business error", false, ToolErrorCode.EXECUTION_FAILED, deliveryAccepted = true,
                ))
            }
        }
        val duplicate = dispatch.run(pendingKey, "wire-other", { error("re-prompted") }) { error("restarted") }
        assertTrue(duplicate.output.contains("pending confirmation"))
        val actual = done.await()
        assertFalse(actual.success)
        assertTrue(actual.output.contains("accepted"))
        assertNotNull(actual.receiptProof)
        interrupted.cancelAndJoin()
        val restarted = ToolReceiptStore(directory)
        val resultId = UUID.randomUUID().toString()
        val pair = ToolReceiptPair(doneKey, "wire-2", "assistant-row", resultId, actual.receiptProof!!)
        assertTrue(restarted.reconcile(turn.sessionSha, listOf(pair)))
        assertEquals(resultId, (restarted.prepare(doneKey) as ToolReceiptDecision.AlreadySent).resultRef)
        assertEquals(ToolReceiptState.UNKNOWN, restarted.state(pendingKey))
        assertEquals(ToolReceiptStore.Pending(1, 0), restarted.pending(turn.sessionSha))
    }

    @Test fun `failed claim or corrupt shard fails closed and ledger has no plaintext`() = runBlocking {
        val bad = temp.newFolder()
        val store = ToolReceiptStore(bad, replace = { file: File, bytes: ByteArray ->
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val state = json.getJSONArray("records").getJSONObject(0).getString("state")
            if (state == "unknown") throw IOException("simulated failure")
            file.writeBytes(bytes)
        })
        val key = key("very-private-filename")
        val refusal = ToolReceiptDispatch(store).run(key, "provider-secret-id", { null }) { error("unrecorded send") }
        assertEquals(ToolErrorCode.RECEIPT_BLOCKED, refusal.errorCode)
        val shard = bad.listFiles()!!.single { it.extension == "json" }
        val saved = shard.readText()
        for (plain in listOf("very-private-filename", "provider-secret-id", "session-a", "user-a", "file_write")) {
            assertFalse(saved.contains(plain))
        }
        shard.writeText("{corrupt")
        val corrupt = ToolReceiptDispatch(ToolReceiptStore(bad)).run(key, "wire", { error("prompted") }) { error("sent") }
        assertEquals(ToolErrorCode.RECEIPT_BLOCKED, corrupt.errorCode)
        assertEquals("{corrupt", shard.readText())
    }

    @Test fun `memory restore alias is a write in permissions scheduling and read-only discussion`() {
        val gate = com.openminis.app.security.SecurityGateImpl()
        val ask = com.openminis.app.security.PermissionMode.ASK
        val readOnly = com.openminis.app.security.PermissionMode.READ_ONLY
        val plan = com.openminis.app.security.PermissionMode.PLAN
        val search = """{"scope":"archive"}"""
        val restore = """{"scope":"archive","action":"restore","entry_id":"2026-11-19:abc"}"""
        val canonical = com.openminis.app.security.ToolAliases.canonical("recall_memory")
        val read = gate.classify("recall_memory", search)
        val write = gate.classify("recall_memory", restore)
        assertTrue(gate.decide(read, readOnly) is com.openminis.app.security.Decision.Allow)
        assertTrue(gate.decide(write, readOnly) is com.openminis.app.security.Decision.Denied)
        assertTrue(gate.decide(write, plan) is com.openminis.app.security.Decision.Denied)
        assertTrue(gate.decide(write, ask) is com.openminis.app.security.Decision.NeedConfirm)
        assertFalse(needsToolReceipt(canonical, search))
        assertTrue(needsToolReceipt(canonical, restore))
        assertTrue(needsToolReceipt(canonical, "{invalid"))
        assertEquals(ToolCallLane.CONCURRENT, ToolCallLanes.laneFor(canonical, args = search))
        assertEquals(ToolCallLane.BARRIER, ToolCallLanes.laneFor(canonical, args = restore))
        assertNull(DiscussionGraph.denyExecution("recall_memory", search))
        assertNotNull(DiscussionGraph.denyExecution("recall_memory", restore))
        assertNotEquals(
            com.openminis.app.security.ToolApprovalKey.gist(canonical, search),
            com.openminis.app.security.ToolApprovalKey.gist(canonical, restore),
        )
    }

    @Test fun `MCP response loss retryable status and redirect give no authority to resend`() = runBlocking {
        val responses = listOf(
            okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST),
            okhttp3.mockwebserver.MockResponse().setResponseCode(503).setHeader("Retry-After", "0"),
            okhttp3.mockwebserver.MockResponse().setResponseCode(307).setHeader("Location", "/duplicate"),
        )
        for ((index, response) in responses.withIndex()) {
            okhttp3.mockwebserver.MockWebServer().use { server ->
                val sent = AtomicInteger()
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                        val rpc = JSONObject(request.body.readUtf8())
                        return when (rpc.getString("method")) {
                            "initialize" -> okhttp3.mockwebserver.MockResponse().setBody(JSONObject()
                                .put("jsonrpc", "2.0").put("id", rpc.getInt("id"))
                                .put("result", JSONObject().put("protocolVersion", "2025-06-18")).toString())
                            "notifications/initialized" -> okhttp3.mockwebserver.MockResponse().setResponseCode(202)
                            "tools/call" -> if (sent.incrementAndGet() == 1) response else okhttp3.mockwebserver.MockResponse()
                                .setBody(JSONObject().put("jsonrpc", "2.0").put("id", rpc.getInt("id"))
                                    .put("result", JSONObject()).toString())
                            else -> okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                        }
                    }
                }
                server.start()
                val http = okhttp3.OkHttpClient.Builder().callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
                val client = com.openminis.app.mcp.client.McpHttpClient(
                    "local-test", server.url("/mcp").toString(), emptyMap(), { null }, false, http,
                )
                val directory = temp.newFolder()
                val intent = turn.call("mcp", """{"action":"call","server":"local-test","tool":"operation"}""")!!
                val attempt = ToolReceiptDispatch(ToolReceiptStore(directory)).run(intent, "first-$index", { null }) {
                    client.callTool("operation", JSONObject())
                    it.completed(ToolExecutionResult("confirmed response", true))
                }
                assertEquals(ToolErrorCode.RECEIPT_UNCONFIRMED, attempt.errorCode)
                assertEquals(1, sent.get())
                val replay = ToolReceiptDispatch(ToolReceiptStore(directory)).run(intent, "again-$index", { error("re-prompted") }) {
                    error("re-sent")
                }
                assertEquals(ToolErrorCode.RECEIPT_UNCONFIRMED, replay.errorCode)
                assertEquals(1, sent.get())
            }
        }
    }

    @Test fun `recovering the same operation needs durable previous batch evidence`() = runBlocking {
        val directory = temp.newFolder()
        val first = key("same-operation")
        val sibling = key("sibling")
        val unresolved = key("unresolved")
        val rowA = UUID.randomUUID().toString()
        val rowB = UUID.randomUUID().toString()
        val sends = AtomicInteger()
        suspend fun send(key: ToolReceiptKey, wire: String) = ToolReceiptDispatch(ToolReceiptStore(directory)).run(
            key, wire, { null },
        ) {
            sends.incrementAndGet()
            it.completed(ToolExecutionResult("done", true))
        }
        val a = send(first, "first")
        val b = send(sibling, "sibling")
        assertTrue(ToolReceiptStore(directory).claim(unresolved, "unresolved") is ToolReceiptDecision.Claimed)
        val afterA = turn.afterTranscript(rowA).call("file_write", """{"path":"same-operation"}""")!!
        assertEquals(ToolErrorCode.RECEIPT_UNCONFIRMED, send(afterA, "not yet").errorCode)
        assertTrue(ToolReceiptStore(directory).reconcile(turn.sessionSha, listOf(
            ToolReceiptPair(first, "first", "owner-a", rowA, a.receiptProof!!),
            ToolReceiptPair(sibling, "sibling", "owner-b", rowB, b.receiptProof!!),
        )))
        assertTrue(send(afterA, "second").success)
        val afterB = turn.afterTranscript(rowB).call("file_write", """{"path":"same-operation"}""")!!
        val afterUnresolved = turn.afterTranscript(rowB).call("file_write", """{"path":"unresolved"}""")!!
        val guessedRow = turn.afterTranscript("not-a-real-row").call("file_write", """{"path":"new"}""")!!
        assertEquals(ToolErrorCode.RECEIPT_UNCONFIRMED, send(afterB, "another proof from this batch").errorCode)
        assertEquals(ToolErrorCode.RECEIPT_UNCONFIRMED, send(afterUnresolved, "changed wire").errorCode)
        assertEquals(ToolErrorCode.RECEIPT_BLOCKED, send(guessedRow, "guessed").errorCode)
        assertEquals(3, sends.get())
    }

    @Test fun `script callbacks and worker slots settle separately without saving their plaintext`() = runBlocking {
        val directory = temp.newFolder()
        val script = turn.child("script", "callbacks", "claimed-script")
        val first = script.step("callback", 1).call("file_write", """{"path":"private-path"}""")!!
        val second = script.step("callback", 2).call("file_write", """{"path":"private-path"}""")!!
        val sends = AtomicInteger()
        suspend fun send(key: ToolReceiptKey, wire: String) = ToolReceiptDispatch(ToolReceiptStore(directory)).run(
            key, wire, { null },
        ) {
            sends.incrementAndGet()
            it.completed(ToolExecutionResult("private-output", true))
        }
        assertNotEquals(first.logicalSha, second.logicalSha)
        val a = send(first, "old-wire")
        assertTrue(a.success)
        assertNotNull(a.receiptProof)
        val b = send(second, "old-wire")
        assertTrue(b.success)
        val replay = send(first, "renumbered")
        assertTrue(replay.notSent)
        assertTrue(replay.output.contains("completion was recorded"))
        assertNull(replay.receiptProof)
        val slotA = turn.child("worker", "slot-0", "wave-a:0").call("file_write", """{"path":"private-path"}""")!!
        val slotB = turn.child("worker", "slot-1", "wave-a:1").call("file_write", """{"path":"private-path"}""")!!
        assertTrue(send(slotA, "same-wire").success)
        assertTrue(send(slotB, "same-wire").success)
        assertEquals(4, sends.get())
        val stored = directory.resolve("${turn.sessionSha}.json").readText()
        assertFalse(stored.contains("private-path"))
        assertFalse(stored.contains("private-output"))
        assertFalse(stored.contains("old-wire"))
        assertEquals(ToolReceiptStore.Pending(0, 0), ToolReceiptStore(directory).pending(turn.sessionSha))
    }

    @Test fun `local completion write failure fences later positions and distinct owners remain independent`() = runBlocking {
        val directory = temp.newFolder()
        val failing = ToolReceiptStore(directory, replace = { file, bytes ->
            if (bytes.toString(Charsets.UTF_8).contains("local_result")) throw IOException("write failed")
            file.writeBytes(bytes)
        })
        val owner = turn.lane("member", "entry-a")
        val first = owner.step("discussion", 1).call("file_write", "{}")!!
        val second = owner.step("discussion", 2).call("file_write", "{}")!!
        val denied = ToolReceiptDispatch(failing).run(first, "a", { null }) {
            it.completed(ToolExecutionResult("private-return", true))
        }
        assertFalse(denied.success)
        assertNull(denied.receiptProof)
        val reopened = ToolReceiptStore(directory)
        assertEquals(ToolReceiptStore.Pending(0, 1), reopened.pending(turn.sessionSha))
        assertTrue(reopened.prepare(second) is ToolReceiptDecision.AlreadySent)
        val independent = turn.lane("member", "entry-b").step("discussion", 1).call("file_write", "{}")!!
        assertEquals(ToolReceiptDecision.Ready, reopened.prepare(independent))
    }

    @Test fun `local sibling evidence cannot make a second claim in the same next generation`() = runBlocking {
        val directory = temp.newFolder()
        val scope = turn.lane("worker", "seat")
        suspend fun send(scope: ToolReceiptScope, path: String) = ToolReceiptDispatch(ToolReceiptStore(directory)).run(
            scope.call("file_write", """{"path":"$path"}""")!!, "shared-provider-id", { null },
        ) { it.completed(ToolExecutionResult("done", true)) }
        val a = send(scope, "a")
        val b = send(scope, "b")
        assertTrue(send(scope.afterLocal(a.receiptProof), "a").success)
        val duplicate = send(scope.afterLocal(b.receiptProof), "a")
        assertEquals(ToolErrorCode.RECEIPT_UNCONFIRMED, duplicate.errorCode)
        assertTrue(duplicate.notSent)
    }

    @Test fun `MCP argument spellings resolve to the same effective operation`() {
        val obj = turn.call("mcp", """{"action":"call","server":"local","tool":"demo","arguments":{"z":1,"a":[1,2]}}""")!!
        val str = turn.call("mcp", """{"action":" call ","server":" local ","tool":" demo ","arguments":"{\"a\":[1,2],\"z\":1}"}""")!!
        assertEquals(obj.logicalSha, str.logicalSha)
        val store = ToolReceiptStore(temp.newFolder())
        assertTrue(store.claim(obj, "as-object") is ToolReceiptDecision.Claimed)
        assertEquals(ToolReceiptState.UNKNOWN, store.state(str))
        assertNull(turn.call("mcp", """{"action":"call","arguments":"{} junk"}"""))
        assertEquals(
            turn.call("mcp", """{"action":"call","server":"local","tool":"demo"}""")!!.logicalSha,
            turn.call("mcp", """{"action":"call","server":"local","tool":"demo","arguments":"{}"}""")!!.logicalSha,
        )
    }

    @Test fun `session history search and read do not claim durable receipts`() {
        assertFalse(needsToolReceipt(HistorySearchTool.SEARCH, """{"query":"migration"}"""))
        assertFalse(needsToolReceipt(HistorySearchTool.READ, """{"message_id":"prior-message","offset":0}"""))
    }

    @Test fun `observational terminal and collect operations remain pollable without exempting mutations`() {
        for (action in listOf("read", "list")) {
            assertTrue(isReceiptPolling("agent_terminal", """{"action":"$action"}"""))
            assertFalse(needsToolReceipt("agent_terminal", """{"action":"$action"}"""))
        }
        for (op in listOf("status", "list", "await", "collect")) {
            assertFalse(needsToolReceipt("check_agent", """{"op":"$op"}"""))
        }
        assertFalse(needsToolReceipt("check_agent", "{}"))
        assertTrue(needsToolReceipt("check_agent", """{"op":"clear"}"""))
        assertTrue(needsToolReceipt("check_agent", "{invalid"))
        for (action in listOf("open", "send", "signal", "close")) {
            assertTrue(needsToolReceipt("agent_terminal", """{"action":"$action"}"""))
        }
    }

    @Test fun `online write follow-ups are disabled for both normal and bodyless operations`() {
        val plugin = com.openminis.app.plugins.OnlineApiTool
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val http = plugin.configureConnectorTransport(okhttp3.OkHttpClient.Builder()).build()
            for (method in listOf("POST", "PUT", "DELETE")) {
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
                val body = if (method == "DELETE") null else "{}".toRequestBody()
                val request = okhttp3.Request.Builder().url(server.url("/local")).method(method, plugin.onlineRequestBody(method, body)).build()
                assertTrue(request.body!!.isOneShot())
                http.newCall(request).execute().use { assertEquals(503, it.code) }
                val received = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
                assertEquals(method, received.method)
                assertEquals(if (body == null) 0L else 2L, received.bodySize)
                assertNull(server.takeRequest(30, java.util.concurrent.TimeUnit.MILLISECONDS))
            }
            val oneShot = plugin.onlineRequestBody("DELETE", null)!!
            oneShot.writeTo(okio.Buffer())
            assertThrows(IOException::class.java) { oneShot.writeTo(okio.Buffer()) }
        }
    }

    @Test fun `terminal evidence is saved before rendering and admission alone cannot clear pending`() = runBlocking {
        val directory = temp.newFolder()
        val scope = turn.lane("worker", "seat")
        val done = scope.call("file_write", """{"path":"done"}""")!!
        val admitted = scope.call("file_write", """{"path":"admitted"}""")!!
        val dispatch = ToolReceiptDispatch(ToolReceiptStore(directory))
        val renderingFailure = dispatch.run(done, "render-failed", { null }) {
            it.completed(ToolExecutionResult("private body", true))
            throw IOException("post-processing failed")
        }
        assertFalse(renderingFailure.notSent)
        assertNull(renderingFailure.receiptProof)
        assertTrue(renderingFailure.output.contains("completion was recorded"))
        val transportFailure = dispatch.run(admitted, "transport-failed", { null }) {
            it.accepted()
            ToolExecutionResult("reply disappeared", false, ToolErrorCode.NETWORK_ERROR)
        }
        assertFalse(transportFailure.notSent)
        assertNull(transportFailure.receiptProof)
        assertEquals(ToolReceiptStore.Pending(0, 1), ToolReceiptStore(directory).pending(turn.sessionSha))
    }

    @Test fun `legacy normalization does not lose unknown receipts and only a verified pair advances them`() {
        val directory = temp.newFolder()
        val initial = turn.call("shell_execute", """{"command":"operation","timeout":30}""")!!
        val retry = turn.call("bash", """{"command":"operation","timeout":120,"delay":1}""")!!
        val token = UUID.randomUUID().toString()
        val wire = "prior-wire"
        val hashedWire = receiptDigest("wire", wire)
        val proof = receiptDigest("delivery-evidence", initial.legacySha, hashedWire, token)
        val row = JSONObject().put("session", turn.sessionSha).put("parent", turn.parentSha)
            .put("key", initial.legacySha).put("state", "unknown").put("call", hashedWire).put("claim", token)
        val legacy = directory.resolve("${turn.sessionSha.take(2)}.json")
        legacy.writeText(JSONObject().put("version", 1).put("records", org.json.JSONArray().put(row)).toString())

        val store = ToolReceiptStore(directory)
        assertEquals(ToolReceiptStore.Pending(1, 0), store.pending(turn.sessionSha))
        assertEquals(ToolReceiptState.UNKNOWN, store.state(retry))
        val rowId = UUID.randomUUID().toString()
        assertTrue(store.reconcile(turn.sessionSha, listOf(ToolReceiptPair(initial, wire, "assistant", rowId, proof))))
        assertTrue(legacy.isFile) // A migrated session must not clear another session's shard.
        val reopened = ToolReceiptStore(directory)
        assertEquals(ToolReceiptStore.Pending(0, 0), reopened.pending(turn.sessionSha))
        assertEquals(ToolReceiptState.ACCEPTED, reopened.state(retry))
        val next = turn.afterTranscript(rowId).call("bash", """{"command":"operation"}""")!!
        assertTrue(reopened.claim(next, "new-wire") is ToolReceiptDecision.Claimed)
    }

    @Test fun `a missing initialized journal fails closed while a fresh session leaves no files on read`() {
        val directory = temp.newFolder()
        val store = ToolReceiptStore(directory)
        assertEquals(ToolReceiptStore.Pending(0, 0), store.pending(turn.sessionSha))
        assertTrue(directory.listFiles()!!.isEmpty())
        val operation = key("interrupted")
        assertTrue(store.claim(operation, "before-loss") is ToolReceiptDecision.Claimed)
        assertTrue(directory.resolve("${turn.sessionSha}.json").delete())
        val reopened = ToolReceiptStore(directory)
        assertNull(reopened.pending(turn.sessionSha))
        assertEquals(ToolReceiptDecision.Unavailable, reopened.prepare(operation))
        assertEquals(ToolReceiptDecision.Unavailable, reopened.claim(operation, "new-wire"))
    }

    @Test fun `receipt capacity is isolated between sessions even with the same legacy shard prefix`() {
        val directory = temp.newFolder()
        val store = ToolReceiptStore(directory, maxRecords = 1)
        val other = generateSequence(0) { it + 1 }.map { ToolReceiptScope.userTurn("other-$it", "user")!! }
            .first { it.sessionSha.take(2) == turn.sessionSha.take(2) && it.sessionSha != turn.sessionSha }
        assertTrue(store.claim(key("first session"), "a") is ToolReceiptDecision.Claimed)
        assertEquals(ToolReceiptDecision.Full, store.prepare(key("blocked by its own capacity")))
        val independent = other.call("file_write", "{}")!!
        assertTrue(store.claim(independent, "b") is ToolReceiptDecision.Claimed)
        assertEquals(2, directory.listFiles()!!.count { it.extension == "json" })
        assertEquals(ToolReceiptStore.Pending(1, 0), store.pending(other.sessionSha))
        assertEquals(ToolReceiptStore.Pending(1, 0), store.pending(turn.sessionSha))
    }

    @Test fun `a full sized journal can settle its last slot without exceeding the byte limit`() {
        val directory = temp.newFolder()
        val journal = directory.resolve("${turn.sessionSha}.json")
        val rows = org.json.JSONArray()
        val hash = "f".repeat(64)
        repeat(ToolReceiptStore.MAX_RECORDS - 1) { index ->
            rows.put(JSONObject().put("session", turn.sessionSha).put("parent", turn.parentSha)
                .put("key", receiptDigest("sized-row", index.toString())).put("state", "accepted")
                .put("call", hash).put("proof_sha", hash).put("pair", hash).put("result_row_sha", hash)
                .put("result_ref", UUID.randomUUID().toString())
                .put("lane", hash).put("owner", hash).put("operation", hash).put("sequence", hash)
                .put("step", Int.MAX_VALUE).put("batch", ToolReceiptStore.MAX_RECORDS))
        }
        journal.writeText(JSONObject().put("version", 2).put("records", rows).toString())
        val store = ToolReceiptStore(directory)
        val last = key("last slot")
        val claim = store.claim(last, "last") as ToolReceiptDecision.Claimed
        val pair = ToolReceiptPair(last, "last", "assistant", UUID.randomUUID().toString(), claim.proof)
        assertTrue(store.reconcile(turn.sessionSha, listOf(pair)))
        assertTrue(journal.length() <= ToolReceiptStore.MAX_SESSION_BYTES.toLong())
        assertEquals(ToolReceiptStore.Pending(0, 0), store.pending(turn.sessionSha))
        // At capacity, a new claim recycles the OLDEST fully settled slot
        // instead of refusing; only unknown/unpaired evidence refuses.
        assertEquals(ToolReceiptDecision.Ready, store.prepare(key("over capacity")))
    }

    @Test fun `proven ended failures settle instead of pending forever`() = runBlocking {        val directory = temp.newFolder()
        val dispatch = ToolReceiptDispatch(ToolReceiptStore(directory))
        val scope = turn.lane("worker", "seat")
        suspend fun run(key: ToolReceiptKey, wire: String, result: ToolExecutionResult) =
            dispatch.run(key, wire, { null }) { it.completed(result) }
        val timedOut = run(scope.call("shell_execute", """{"command":"long"}""")!!, "shell-wire",
            ToolExecutionResult("partial before the watchdog", false, timedOut = true, deliveryAccepted = true))
        assertFalse(timedOut.success)
        assertTrue(timedOut.timedOut)
        assertNotNull(timedOut.receiptProof)
        assertTrue(timedOut.output.contains("did not report success"))
        val executedFailure = run(scope.call("memory_write", """{"content":"x"}""")!!, "memory-wire",
            ToolExecutionResult("MEMORY_CONFLICT", false, deliveryAccepted = true))
        assertNotNull(executedFailure.receiptProof)
        assertEquals(ToolReceiptStore.Pending(0, 0), ToolReceiptStore(directory).pending(turn.sessionSha))
        // An uncertain failure without the terminal marker stays pending.
        val uncertain = run(scope.call("ssh_exec", """{"action":"exec","command":"x"}""")!!, "ssh-wire",
            ToolExecutionResult("connection lost mid-command", false, timedOut = true))
        assertNull(uncertain.receiptProof)
        assertTrue(uncertain.output.contains("pending confirmation"))
        assertEquals(ToolReceiptStore.Pending(1, 0), ToolReceiptStore(directory).pending(turn.sessionSha))
    }

    @Test fun `execute code refusals stay retryable and sandbox failures settle`() = runBlocking {
        val rejected = com.openminis.app.tools.ExecuteCodeTool.execute(
            """{"language":"python","code":"1"}""", invoke = { _, _ -> error("never invoked") })
        assertTrue(rejected.notSent)
        assertFalse(rejected.deliveryAccepted)
        assertTrue(com.openminis.app.tools.ExecuteCodeTool.execute(
            """{"tool_title":"t"}""", invoke = { _, _ -> error("never invoked") },
        ).notSent)
        val failed = com.openminis.app.tools.ExecuteCodeTool.execute(
            """{"code":"throw new Error('boom')"}""", invoke = { _, _ -> error("never invoked") })
        assertFalse(failed.success)
        assertTrue(failed.deliveryAccepted)
        assertFalse(failed.notSent)
        assertTrue(failed.output.contains("boom"))
    }

    @Test fun `mcp failures classify provable delivery outcomes`() {
        fun fail(code: String) = com.openminis.app.tools.McpNativeTool.failure(
            com.openminis.app.mcp.client.McpJsonRpc.McpError(code, "detail"), "title")
        // Local policy/config refusals and proven connect-phase failures: not sent.
        assertTrue(fail("NOT_FOUND").notSent)
        assertTrue(fail("TOOL_DISABLED").notSent)
        assertTrue(fail("NOT_INITIALIZED").notSent)
        assertTrue(fail("CONNECT_FAILED").notSent)
        assertFalse(fail("CONNECT_FAILED").deliveryAccepted)
        // A server answer (auth rejection, HTTP 5xx, JSON-RPC error) is delivered.
        assertTrue(fail("AUTH_REQUIRED").deliveryAccepted)
        assertTrue(fail("SERVER_ERROR").deliveryAccepted)
        assertTrue(fail("RPC_-32601").deliveryAccepted)
        // Read-phase timeouts, protocol garbage, and generic IO stay uncertain.
        val uncertain = fail("TIMEOUT")
        assertFalse(uncertain.notSent)
        assertFalse(uncertain.deliveryAccepted)
        assertTrue(uncertain.timedOut)
        assertFalse(fail("PROTOCOL_ERROR").deliveryAccepted)
        assertFalse(fail("CONNECTION_ERROR").deliveryAccepted)
        // The real message is preserved on every path.
        assertEquals("[TOOL_DISABLED] detail", fail("TOOL_DISABLED").output)
        assertEquals("[CONNECT_FAILED] detail", fail("CONNECT_FAILED").output)
    }

    @Test fun `fully settled history recycles oldest but unknown and unpaired evidence never evict`() {
        val store = ToolReceiptStore(temp.newFolder(), maxRecords = 2)
        val scope = turn.lane("worker", "recycle")
        val settledA = scope.call("file_write", """{"path":"settled-a"}""")!!
        val settledB = scope.call("file_write", """{"path":"settled-b"}""")!!
        val claimA = store.claim(settledA, "a") as ToolReceiptDecision.Claimed
        assertTrue(store.completeLocal(settledA, claimA, true))
        val claimB = store.claim(settledB, "b") as ToolReceiptDecision.Claimed
        assertTrue(store.completeLocal(settledB, claimB, false))
        // Capacity hit with two fully settled records: the OLDEST settled
        // record gives way for a new claim; the newer one survives.
        val fresh = key("fresh")
        assertTrue(store.claim(fresh, "c") is ToolReceiptDecision.Claimed)
        // Non-mutating probe first: the newer settled record is intact.
        val kept = store.prepare(settledB) as ToolReceiptDecision.AlreadySent
        assertEquals(ToolReceiptState.ACCEPTED, kept.state)
        assertTrue(kept.resultRecorded)
        // The oldest settled record was recycled: re-preparing it starts a
        // fresh NOT_SENT slot (this probe re-inserts it, recycling settledB).
        assertEquals(ToolReceiptDecision.Ready, store.prepare(settledA))
        // The in-flight claim's own slot is untouched by that recycle.
        val inFlight = store.prepare(fresh) as ToolReceiptDecision.AlreadySent
        assertEquals(ToolReceiptState.UNKNOWN, inFlight.state)

        // UNKNOWN and ACCEPTED-without-result are never evicted.
        val locked = ToolReceiptStore(temp.newFolder(), maxRecords = 2)
        val unknownA = scope.call("file_write", """{"path":"u-a"}""")!!
        val unknownB = scope.call("file_write", """{"path":"u-b"}""")!!
        assertTrue(locked.claim(unknownA, "a") is ToolReceiptDecision.Claimed)
        assertTrue(locked.claim(unknownB, "b") is ToolReceiptDecision.Claimed)
        assertEquals(ToolReceiptDecision.Full, locked.prepare(key("blocked")))
        val keptUnknownA = locked.prepare(unknownA) as ToolReceiptDecision.AlreadySent
        val keptUnknownB = locked.prepare(unknownB) as ToolReceiptDecision.AlreadySent
        assertEquals(ToolReceiptState.UNKNOWN, keptUnknownA.state)
        assertEquals(ToolReceiptState.UNKNOWN, keptUnknownB.state)
        assertFalse(keptUnknownA.resultRecorded)
        assertFalse(keptUnknownB.resultRecorded)
    }

    @Test fun `capacity preserves unknown and accepted and recycles only confirmed not sent`() {
        val store = ToolReceiptStore(temp.newFolder(), maxRecords = 2)
        val unknown = key("unknown")
        val unsent = key("unsent")
        val accepted = key("accepted")
        assertTrue(store.claim(unknown, "one") is ToolReceiptDecision.Claimed)
        assertEquals(ToolReceiptDecision.Ready, store.prepare(unsent))
        val claim = store.claim(accepted, "two") as ToolReceiptDecision.Claimed
        assertTrue(store.settle(accepted, claim, ToolReceiptState.ACCEPTED))
        assertEquals(ToolReceiptDecision.Full, store.prepare(key("new")))
        assertEquals(ToolReceiptState.UNKNOWN, store.state(unknown))
        assertEquals(ToolReceiptState.ACCEPTED, store.state(accepted))
    }

    /**
     * [T-receipt-terminal-evidence] An in-process adapter that RETURNED has a
     * known delivery state. A plain business failure used to stay UNKNOWN, so
     * the model was told "it may already have occurred, do not repeat" about an
     * edit that certainly never happened.
     */
    @Test fun `an adapter that answered settles a plain business failure`() {
        val editFailure = ToolExecutionResult("Error: old string not found in /tmp/a.kt", false)
        assertTrue(normalizeDeliveryEvidence("file_edit", editFailure).deliveryAccepted)
        assertFalse(normalizeDeliveryEvidence("file_edit", editFailure).success)
        assertTrue(normalizeDeliveryEvidence("web_fetch", ToolExecutionResult("HTTP 404", false)).deliveryAccepted)
        // Adapters that produce their own evidence keep their own verdict.
        assertFalse(normalizeDeliveryEvidence("mcp", ToolExecutionResult("lost", false, timedOut = true)).deliveryAccepted)
        assertFalse(normalizeDeliveryEvidence("ssh_exec", ToolExecutionResult("closed", false)).deliveryAccepted)
        assertFalse(normalizeDeliveryEvidence("shell_execute", ToolExecutionResult("killed", false)).deliveryAccepted)
        assertFalse(normalizeDeliveryEvidence("execute_code", ToolExecutionResult("boom", false)).deliveryAccepted)
        // Never dispatched, and receipt advisories, stay exactly as reported.
        assertFalse(normalizeDeliveryEvidence("file_edit", ToolExecutionResult("no", false, notSent = true)).deliveryAccepted)
        val advisory = toolReceiptMissingAnchor()
        assertSame(advisory, normalizeDeliveryEvidence("file_edit", advisory))
    }

    /** [T-receipt-large-args] A big payload used to lose its identity and refuse to run at all. */
    @Test fun `a large call still has a stable identity`() {
        val big = "x".repeat(600 * 1024)
        val args = """{"path":"/tmp/big.txt","content":"$big"}"""
        val first = requireNotNull(turn.call("file_write", args))
        val second = requireNotNull(turn.call("file_write", args))
        assertEquals(first.logicalSha, second.logicalSha)
        assertEquals(first.operationSha, second.operationSha)
    }

    /**
     * [T-receipt-internal-run] A Goal run started from a fresh chat persists no
     * user row, so the user-row anchor is null and every receipted tool used to
     * be refused — a Goal could read files and nothing else.
     */
    @Test fun `an internal run without a user row still gets a stable anchor`() {
        val run = requireNotNull(ToolReceiptScope.internalRun("session-a", "run-1"))
        assertNotNull(run.call("file_write", """{"path":"/tmp/a"}"""))
        assertEquals(run.parentSha, requireNotNull(ToolReceiptScope.internalRun("session-a", "run-1")).parentSha)
        assertNotEquals(run.parentSha, requireNotNull(ToolReceiptScope.internalRun("session-a", "run-2")).parentSha)
        // Its namespace never collides with a user row that happens to share the id.
        assertNotEquals(run.parentSha, requireNotNull(ToolReceiptScope.userTurn("session-a", "run-1")).parentSha)
        // No run identity at all still fails closed.
        assertNull(ToolReceiptScope.internalRun("session-a", null))
        assertNull(ToolReceiptScope.internalRun("session-a", "  "))
    }
}
