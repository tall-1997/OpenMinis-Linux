package com.openminis.app.mcp.server.approval

import android.content.SharedPreferences
import com.openminis.app.mcp.server.McpServerCore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before

/** In-memory SharedPreferences so the RPC layer runs on the JVM. */
private class FakePrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?): String? = map[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val staged = HashMap<String, Any?>()
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { staged[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { staged[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { staged[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { staged.clear(); staged["__clear__"] = true }
        override fun commit(): Boolean { flush(); return true }
        override fun apply() { flush() }
        private fun flush() {
            if (staged.remove("__clear__") == true) map.clear()
            staged.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            staged.clear()
        }
    }
}

/** One test world: rpc + its backing manager/store, so cleanup tests can age state. */
private class Harness(
    val rpc: McpApprovalRpc,
    val manager: McpApprovalManager,
    val store: McpApprovalStore,
)

class McpApprovalRpcTest {

    @Before
    fun setUp() {
        // The production root lives under /var/minis, which a CI runner cannot
        // create (non-root user). Point the shared root at a writable temp dir.
        val tmp = File(System.getProperty("java.io.tmpdir"), "mcp-approval-test")
        McpApprovalStore.taskRootPrefix = tmp.path + "/"
    }

    @After
    fun tearDown() {
        // Symmetric reset so other test classes see the production default.
        McpApprovalStore.taskRootPrefix = McpApprovalStore.TASK_ROOT_PREFIX
    }

    private class FakeDispatcher : McpServerCore.ToolDispatcher {
        var lastArguments: JSONObject? = null
        var calls = 0
        override fun listTools(): JSONArray = JSONArray()
        override fun callTool(name: String, arguments: JSONObject): McpServerCore.CallResult {
            calls++
            lastArguments = arguments
            return McpServerCore.CallResult(JSONArray().put(JSONObject().put("type", "text").put("text", "executed:$name")))
        }
    }

    private fun harness(dispatch: McpServerCore.ToolDispatcher = FakeDispatcher()): Harness {
        val store = McpApprovalStore(FakePrefs())
        return Harness(
            McpApprovalRpc(McpApprovalManager(store), McpApprovalExecutor(dispatch)),
            McpApprovalManager(store),
            store,
        )
    }

    private fun call(h: Harness, method: String, params: JSONObject = JSONObject()): Pair<JSONObject?, JSONObject?> {
        val resp = h.rpc.handle(method, params, 42)
        val body = JSONObject(resp.body)
        return (body.optJSONObject("result")) to (body.optJSONObject("error"))
    }

    private fun createActiveTask(h: Harness, taskId: String = "task-1") {
        val (_, err) = call(h, "phone_task_request", JSONObject().put("taskId", taskId).put("name", "demo"))
        assertTrue("create failed: $err", err == null)
        val (_, err2) = call(h, "phone_task_approve", JSONObject().put("taskId", taskId))
        assertTrue("approve failed: $err2", err2 == null)
    }

    private fun submit(h: Harness, taskId: String = "task-1", opId: String = "op-1"): JSONObject {
        val (res, err) = call(h, "phone_local_run", JSONObject()
            .put("taskId", taskId)
            .put("operationId", opId)
            .put("tool", "shell_exec")
            .put("arguments", """{"command":"echo hi","timeout_sec":900}"""))
        assertTrue("request failed: $err", err == null)
        return res!!
    }

    // -- happy path ----------------------------------------------------------

    @Test
    fun `full three-phase flow executes and commits`() {
        val disp = FakeDispatcher()
        val h = harness(disp)
        createActiveTask(h)

        val submitted = submit(h)
        assertEquals("task-1", submitted.optString("taskId"))
        assertEquals("op-1", submitted.optString("operationId"))
        assertEquals("WAITING", submitted.optString("status"))
        assertEquals(600_000L, submitted.optLong("approvalTtlMs"))
        assertTrue(submitted.optString("requestId").isNotBlank())

        val (_, err1) = call(h, "phone_task_approve_op", JSONObject().put("operationId", "op-1"))
        assertTrue("approve_op failed: $err1", err1 == null)

        val (exec, err2) = call(h, "phone_operation_execute", JSONObject().put("operationId", "op-1"))
        assertTrue("execute failed: $err2", err2 == null)
        assertEquals("EXECUTED", exec!!.optString("status"))
        assertEquals(1, disp.calls)
        assertEquals("executed:shell_exec",
            exec.optJSONArray("content")!!.optJSONObject(0).optString("text"))
        assertEquals(McpApprovalStore.taskRootFor("task-1"), exec.optString("taskDir"))

        // Artifacts landed on disk.
        val dir = File(McpApprovalStore.taskRootFor("task-1"))
        assertTrue(File(dir, "arguments.json").isFile)
        assertTrue(File(dir, "result.json").isFile)
        assertTrue(File(dir, "output.txt").readText().contains("executed:shell_exec"))
        dir.deleteRecursively()
    }

    @Test
    fun `execute twice is idempotent`() {
        val disp = FakeDispatcher()
        val h = harness(disp)
        createActiveTask(h)
        submit(h)
        call(h, "phone_task_approve_op", JSONObject().put("operationId", "op-1"))
        val (_, err1) = call(h, "phone_operation_execute", JSONObject().put("operationId", "op-1"))
        assertTrue(err1 == null)
        val (_, err2) = call(h, "phone_operation_execute", JSONObject().put("operationId", "op-1"))
        assertTrue("second execute failed: $err2", err2 == null)
        assertEquals("tool must not run twice", 1, disp.calls)
        File(McpApprovalStore.taskRootFor("task-1")).deleteRecursively()
    }

    // -- gates ---------------------------------------------------------------

    @Test
    fun `execute after task revocation is rejected`() {
        val h = harness()
        createActiveTask(h)
        submit(h)
        call(h, "phone_task_approve_op", JSONObject().put("operationId", "op-1"))
        call(h, "phone_task_revoke", JSONObject().put("taskId", "task-1"))
        val (res, err) = call(h, "phone_operation_execute", JSONObject().put("operationId", "op-1"))
        assertTrue(res == null)
        assertEquals(McpApprovalCore.ErrorCode.OPERATION_NOT_FOUND.name, err!!.optJSONObject("data").optString("approvalCode"))
    }

    @Test
    fun `execute before operation approval is rejected`() {
        val h = harness()
        createActiveTask(h)
        submit(h)
        val (res, err) = call(h, "phone_operation_execute", JSONObject().put("operationId", "op-1"))
        assertTrue(res == null)
        assertEquals(McpApprovalCore.ErrorCode.OPERATION_NOT_WAITING.name, err!!.optJSONObject("data").optString("approvalCode"))
    }

    @Test
    fun `fingerprint bound at approval gates execution`() {
        val disp = FakeDispatcher()
        val h = harness(disp)
        createActiveTask(h)
        submit(h)
        call(h, "phone_task_approve_op", JSONObject()
            .put("operationId", "op-1")
            .put("operationFingerprint", "fp-abc"))
        // Execute without the fingerprint → rejected, dispatcher untouched.
        val (_, err1) = call(h, "phone_operation_execute", JSONObject().put("operationId", "op-1"))
        assertEquals(McpApprovalCore.ErrorCode.REQUEST_ID_MISMATCH.name, err1!!.optJSONObject("data").optString("approvalCode"))
        assertEquals(0, disp.calls)
        // Execute with the right fingerprint → runs.
        val (exec, err2) = call(h, "phone_operation_execute", JSONObject()
            .put("operationId", "op-1")
            .put("operationFingerprint", "fp-abc"))
        assertTrue("execute failed: $err2", err2 == null)
        assertEquals(1, disp.calls)
        File(McpApprovalStore.taskRootFor("task-1")).deleteRecursively()
    }

    // -- list / status / cancel / delete / cleanup ---------------------------

    @Test
    fun `list exposes tasks operations and execution flags`() {
        val h = harness()
        createActiveTask(h)
        submit(h)
        call(h, "phone_task_approve_op", JSONObject().put("operationId", "op-1"))
        call(h, "phone_operation_execute", JSONObject().put("operationId", "op-1"))
        val (list, err) = call(h, "phone_task_list")
        assertTrue(err == null)
        assertEquals(1, list!!.optJSONArray("tasks").length())
        val task = list.optJSONArray("tasks").optJSONObject(0)
        assertEquals("task-1", task.optString("taskId"))
        assertEquals("ACTIVE", task.optString("status"))
        val op = list.optJSONArray("operations").optJSONObject(0)
        assertEquals("op-1", op.optString("operationId"))
        assertTrue(op.optBoolean("executed"))
        // Per-operation status view matches.
        val (st, err2) = call(h, "phone_task_status", JSONObject().put("operationId", "op-1"))
        assertTrue(err2 == null)
        assertTrue(st!!.optBoolean("executed"))
        File(McpApprovalStore.taskRootFor("task-1")).deleteRecursively()
    }

    @Test
    fun `cancel drops a waiting operation`() {
        val h = harness()
        createActiveTask(h)
        submit(h)
        val (res, err) = call(h, "phone_task_cancel", JSONObject().put("operationId", "op-1"))
        assertTrue("cancel failed: $err", err == null)
        assertEquals("CANCELLED", res!!.optString("status"))
        // Approving a cancelled op is impossible.
        val (_, err2) = call(h, "phone_task_approve_op", JSONObject().put("operationId", "op-1"))
        assertEquals(McpApprovalCore.ErrorCode.OPERATION_NOT_FOUND.name, err2!!.optJSONObject("data").optString("approvalCode"))
    }

    @Test
    fun `delete removes the task and its operations`() {
        val h = harness()
        createActiveTask(h)
        submit(h)
        val (res, err) = call(h, "phone_task_delete", JSONObject().put("taskId", "task-1"))
        assertTrue("delete failed: $err", err == null)
        val (list, _) = call(h, "phone_task_list")
        assertEquals(0, list!!.optJSONArray("tasks").length())
        assertEquals(0, list.optJSONArray("operations").length())
        // Executing the gone operation is rejected.
        val (_, err2) = call(h, "phone_operation_execute", JSONObject().put("operationId", "op-1"))
        assertEquals(McpApprovalCore.ErrorCode.OPERATION_NOT_FOUND.name, err2!!.optJSONObject("data").optString("approvalCode"))
    }

    @Test
    fun `cleanup prunes expired approvals and stale task dirs`() {
        val h = harness()
        createActiveTask(h)
        submit(h)
        call(h, "phone_task_approve_op", JSONObject().put("operationId", "op-1"))
        // Age the approval past the TTL.
        val state = h.store.load()
        val aged = state.copy(
            operations = state.operations.mapValues { (_, op) ->
                op.copy(
                    status = McpApprovalCore.OperationStatus.APPROVED,
                    approvedAtMs = System.currentTimeMillis() - 73_000_000L,
                )
            },
        )
        h.store.replace(aged)
        val dir = File(McpApprovalStore.taskRootFor("task-1"))
        dir.mkdirs()
        File(dir, "output.txt").writeText("stale")
        val (res, err) = call(h, "phone_task_cleanup")
        assertTrue("cleanup failed: $err", err == null)
        assertEquals(1, res!!.optInt("removed"))
        assertEquals(1, res.optInt("dirsDeleted"))
        assertFalse(dir.exists())
    }

    @Test
    fun `revoke flips an active task back`() {
        val h = harness()
        createActiveTask(h)
        val (res, err) = call(h, "phone_task_revoke", JSONObject().put("taskId", "task-1"))
        assertTrue("revoke failed: $err", err == null)
        assertEquals("REVOKED", res!!.optString("status"))
        // Requests under a revoked task are refused.
        val (_, err2) = call(h, "phone_local_run", JSONObject()
            .put("taskId", "task-1").put("operationId", "op-x").put("tool", "shell_exec").put("arguments", "{}"))
        assertEquals(McpApprovalCore.ErrorCode.TASK_NOT_ACTIVE.name, err2!!.optJSONObject("data").optString("approvalCode"))
    }

    @Test
    fun `phone_local_run rejects a requestId that does not match the content`() {
        val h = harness()
        createActiveTask(h)
        val (res, err) = call(h, "phone_local_run", JSONObject()
            .put("taskId", "task-1")
            .put("operationId", "op-1")
            .put("requestId", "deadbeef")
            .put("tool", "shell_exec")
            .put("arguments", """{"command":"echo hi"}"""))
        assertTrue(res == null)
        assertEquals(McpApprovalCore.ErrorCode.REQUEST_ID_MISMATCH.name, err!!.optJSONObject("data").optString("approvalCode"))
    }

    @Test
    fun `only the request run and execute methods are externally callable`() {
        val h = harness()
        val external = listOf(
            "phone_task_request",
            "phone_local_run",
            "phone_operation_execute",
            "phone_task_status",
        )
        external.forEach { assertTrue(it, h.rpc.isExternalMethod(it)) }
        // Approval is a phone-only action: an external caller never grants itself permission.
        listOf(
            "phone_task_approve",
            "phone_task_approve_op",
            "phone_task_revoke",
            "phone_task_list",
            "phone_task_cancel",
            "phone_task_cleanup",
            "phone_task_delete",
        ).forEach { assertFalse(it, h.rpc.isExternalMethod(it)) }
    }

    @Test
    fun `task status answers for a whole task with every operation`() {
        val h = harness()
        createActiveTask(h)
        submit(h)
        submit(h, opId = "op-2")
        call(h, "phone_task_approve_op", JSONObject().put("operationId", "op-2"))
        call(h, "phone_operation_execute", JSONObject().put("operationId", "op-2"))

        val (st, err) = call(h, "phone_task_status", JSONObject().put("taskId", "task-1"))
        assertTrue("status failed: $err", err == null)
        assertEquals("task-1", st!!.optString("taskId"))
        assertEquals("ACTIVE", st.optString("status"))
        val ops = st.optJSONArray("operations")
        assertEquals(2, ops.length())
        // Order is (createdAtMs, operationId), so the list is deterministic for a client.
        val first = ops.optJSONObject(0)
        val second = ops.optJSONObject(1)
        assertEquals("op-1", first.optString("operationId"))
        assertEquals("WAITING", first.optString("status"))
        assertFalse(first.optBoolean("executed"))
        assertEquals("op-2", second.optString("operationId"))
        assertTrue(second.optBoolean("executed"))
        assertEquals("APPROVED", second.optString("status"))
        assertTrue(second.optLong("approvedAtMs") > 0L)
        File(McpApprovalStore.taskRootFor("task-1")).deleteRecursively()
    }

    @Test
    fun `task status needs an id and reports unknown tasks`() {
        val h = harness()
        val (_, err) = call(h, "phone_task_status", JSONObject())
        assertEquals(McpApprovalCore.ErrorCode.INVALID_ID.name, err!!.optJSONObject("data").optString("approvalCode"))
        val (_, err2) = call(h, "phone_task_status", JSONObject().put("taskId", "missing"))
        assertEquals(McpApprovalCore.ErrorCode.TASK_NOT_FOUND.name, err2!!.optJSONObject("data").optString("approvalCode"))
    }

    @Test
    fun `unknown phone method yields a JSON-RPC error`() {
        val h = harness()
        val (res, err) = call(h, "phone_does_not_exist")
        assertTrue(res == null)
        assertEquals(-32601, err!!.optInt("code"))
    }
}
