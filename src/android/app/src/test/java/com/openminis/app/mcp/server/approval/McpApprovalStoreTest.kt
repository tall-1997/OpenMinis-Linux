package com.openminis.app.mcp.server.approval

import com.openminis.app.mcp.server.approval.McpApprovalCore.ErrorCode
import com.openminis.app.mcp.server.approval.McpApprovalCore.Outcome
import com.openminis.app.mcp.server.approval.McpApprovalCore.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-approval] Round-trip contract for [McpApprovalStore]'s static JSON
 * (de)serialization and id generation. Pure JVM — the prefs wrapper is a
 * three-liner over these functions.
 */
class McpApprovalStoreTest {

    private val T0 = 1_000_000L

    private fun ok(state: Outcome): State = (state as Outcome.Ok).state

    private fun fullState(): State {
        var s = State(epoch = 7L)
        s = ok(McpApprovalCore.createTask(s, "task-a", "demo a", T0))
        s = ok(McpApprovalCore.approveTask(s, "task-a"))
        val args = """{"command":"echo hi"}"""
        s = ok(McpApprovalCore.submitRun(
            s, "task-a", "op-1", McpApprovalCore.requestIdFor("task-a", "shell_exec", args),
            "shell_exec", args, T0 + 1,
        ))
        s = ok(McpApprovalCore.approveOperation(s, "op-1", T0 + 2, sourceFileHash = "feed1234", operationFingerprint = "fp-xyz"))
        s = ok(McpApprovalCore.submitRun(
            s, "task-a", "op-2", McpApprovalCore.requestIdFor("task-a", "file_read", """{"path":"/a"}"""),
            "file_read", """{"path":"/a"}""", T0 + 3,
        ))
        s = ok(McpApprovalCore.markExecuted(s, "op-1"))
        return s
    }

    @Test
    fun `round trip preserves every field`() {
        val original = fullState()
        val json = McpApprovalStore.serialize(original)
        val parsed = McpApprovalStore.parse(json)
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun `round trip of fresh state`() {
        val original = State()
        val parsed = McpApprovalStore.parse(McpApprovalStore.serialize(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `round trip of epoch only`() {
        val original = McpApprovalCore.bumpEpoch(State())
        val parsed = McpApprovalStore.parse(McpApprovalStore.serialize(original))
        assertEquals(2L, parsed?.epoch)
    }

    @Test
    fun `malformed json falls back to null`() {
        assertNull(McpApprovalStore.parse(""))
        assertNull(McpApprovalStore.parse("{"))
        assertNull(McpApprovalStore.parse("not json"))
        assertNull(McpApprovalStore.parse("""{"epoch": 3"""))          // truncated object
        assertNull(McpApprovalStore.parse("""[1,2,3]"""))               // array is not an object
    }

    @Test
    fun `unknown status name falls back to first enum constant`() {
        val original = fullState()
        val tampered = McpApprovalStore.serialize(original)
            .replace("\"ACTIVE\"", "\"SOMETHING_NEW\"")
        val parsed = McpApprovalStore.parse(tampered)
        assertNotNull(parsed)
        // Forward-compatible: unknown status degrades to the first constant
        // instead of failing the whole parse.
        assertEquals(McpApprovalCore.TaskStatus.PENDING, parsed?.tasks?.getValue("task-a")?.status)
    }

    @Test
    fun `generated ids are valid and unique`() {
        val seen = mutableSetOf<String>()
        repeat(500) {
            val id = McpApprovalStore.newId()
            assertTrue(McpApprovalCore.validId(id))
            assertTrue(seen.add(id))
        }
        assertEquals(24, McpApprovalStore.newId().length)
    }

    @Test
    fun `task root path is derived from task id`() {
        val old = McpApprovalStore.taskRootPrefix
        try {
            // Pinned to the production default first: another test class may
            // have left the shared prefix at a temp dir (class order is not
            // guaranteed).
            McpApprovalStore.taskRootPrefix = McpApprovalStore.TASK_ROOT_PREFIX
            assertEquals("/var/minis/mcp-tasks/abc/", McpApprovalStore.taskRootFor("abc"))
            McpApprovalStore.taskRootPrefix = "/tmp/mcp-test/"
            assertEquals("/tmp/mcp-test/abc/", McpApprovalStore.taskRootFor("abc"))
        } finally {
            McpApprovalStore.taskRootPrefix = old
        }
    }

    @Test
    fun `serialized approvals json is compact and parseable by org json`() {
        val json = McpApprovalStore.serialize(fullState())
        val root = org.json.JSONObject(json)
        assertEquals(1, root.getInt("version"))
        assertEquals(2, root.getJSONArray("operations").length())
    }
}
