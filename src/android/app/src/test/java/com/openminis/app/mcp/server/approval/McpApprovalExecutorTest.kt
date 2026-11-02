package com.openminis.app.mcp.server.approval

import com.openminis.app.mcp.server.McpServerCore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class McpApprovalExecutorTest {

    private class FakeDispatcher(
        var result: McpServerCore.CallResult =
            McpServerCore.CallResult(JSONArray().put(JSONObject().put("type", "text").put("text", "hello"))),
        var throwOnCall: Boolean = false,
    ) : McpServerCore.ToolDispatcher {
        var lastTool: String? = null
        var lastArguments: JSONObject? = null
        var calls = 0

        override fun listTools(): JSONArray = JSONArray()

        override fun callTool(name: String, arguments: JSONObject): McpServerCore.CallResult {
            calls++
            lastTool = name
            lastArguments = arguments
            if (throwOnCall) throw IllegalStateException("boom")
            return result
        }
    }

    private fun operation(
        sourceFileHash: String? = null,
        argumentsJson: String = """{"command":"echo hi","timeout_sec":900}""",
    ) = McpApprovalCore.Operation(
        operationId = "op-1",
        taskId = "task-1",
        requestId = "req-1",
        tool = "shell_exec",
        argumentsJson = argumentsJson,
        status = McpApprovalCore.OperationStatus.APPROVED,
        createdAtMs = 1L,
        approvedAtMs = 2L,
        sourceFileHash = sourceFileHash,
        operationFingerprint = null,
    )

    // -- source binding ------------------------------------------------------

    @Test
    fun `no binding passes any evidence`() {
        val ex = McpApprovalExecutor(FakeDispatcher())
        val op = operation()
        assertTrue(ex.checkSource(op, McpApprovalExecutor.SourceContext()).ok)
        assertTrue(ex.checkSource(op, McpApprovalExecutor.SourceContext(sourceFileHash = "abc")).ok)
    }

    @Test
    fun `bound hash accepts a matching presented hash`() {
        val ex = McpApprovalExecutor(FakeDispatcher())
        val op = operation(sourceFileHash = "abc123")
        assertTrue(ex.checkSource(op, McpApprovalExecutor.SourceContext(sourceFileHash = "ABC123")).ok)
    }

    @Test
    fun `bound hash rejects a mismatching presented hash`() {
        val ex = McpApprovalExecutor(FakeDispatcher())
        val op = operation(sourceFileHash = "abc123")
        val r = ex.checkSource(op, McpApprovalExecutor.SourceContext(sourceFileHash = "zzz"))
        assertFalse(r.ok)
        assertEquals(McpApprovalCore.ErrorCode.REQUEST_ID_MISMATCH, r.errorCode)
    }

    @Test
    fun `bound hash accepts a matching file path`() {
        val ex = McpApprovalExecutor(FakeDispatcher())
        val dir = createTempDir(prefix = "mcp-src")
        val file = File(dir, "script.sh")
        file.writeText("#!/bin/sh\necho hi\n")
        val hash = McpApprovalExecutor.sha256Hex(file)
        val op = operation(sourceFileHash = hash)
        assertTrue(ex.checkSource(op, McpApprovalExecutor.SourceContext(sourceFilePath = file.path)).ok)
        dir.deleteRecursively()
    }

    @Test
    fun `bound hash rejects a file whose content differs`() {
        val ex = McpApprovalExecutor(FakeDispatcher())
        val dir = createTempDir(prefix = "mcp-src")
        val file = File(dir, "script.sh")
        file.writeText("different content")
        val op = operation(sourceFileHash = "abc123")
        val r = ex.checkSource(op, McpApprovalExecutor.SourceContext(sourceFilePath = file.path))
        assertFalse(r.ok)
        assertEquals(McpApprovalCore.ErrorCode.REQUEST_ID_MISMATCH, r.errorCode)
        dir.deleteRecursively()
    }

    @Test
    fun `bound hash rejects a missing evidence`() {
        val ex = McpApprovalExecutor(FakeDispatcher())
        val op = operation(sourceFileHash = "abc123")
        val r = ex.checkSource(op, McpApprovalExecutor.SourceContext())
        assertFalse(r.ok)
        assertEquals(McpApprovalCore.ErrorCode.REQUEST_ID_MISMATCH, r.errorCode)
    }

    @Test
    fun `bound hash rejects a nonexistent file`() {
        val ex = McpApprovalExecutor(FakeDispatcher())
        val op = operation(sourceFileHash = "abc123")
        val r = ex.checkSource(op, McpApprovalExecutor.SourceContext(sourceFilePath = "/tmp/definitely-missing-9f2"))
        assertFalse(r.ok)
        assertEquals(McpApprovalCore.ErrorCode.REQUEST_ID_MISMATCH, r.errorCode)
    }

    // -- execution -----------------------------------------------------------

    @Test
    fun `execute runs the tool and writes artifacts`() {
        val disp = FakeDispatcher()
        val ex = McpApprovalExecutor(disp)
        val dir = createTempDir(prefix = "mcp-run")
        val r = ex.execute(operation(), dir.path, McpApprovalExecutor.SourceContext())
        assertTrue(r.ok)
        assertEquals("hello", r.callResult!!.content.optJSONObject(0).optString("text"))
        assertEquals(1, disp.calls)
        assertTrue(File(dir, "arguments.json").isFile)
        assertTrue(File(dir, "result.json").isFile)
        assertTrue(File(dir, "output.txt").isFile)
        assertTrue(File(dir, "output.txt").readText().contains("hello"))
        assertTrue(File(dir, "result.json").readText().contains("shell_exec"))
        dir.deleteRecursively()
    }

    @Test
    fun `execute clamps the timeout to the run ceiling`() {
        val disp = FakeDispatcher()
        val ex = McpApprovalExecutor(disp)
        val dir = createTempDir(prefix = "mcp-run")
        ex.execute(operation(), dir.path, McpApprovalExecutor.SourceContext())
        assertEquals(McpApprovalCore.MAX_RUN_SECONDS, disp.lastArguments!!.optInt("timeout_sec"))
        dir.deleteRecursively()
    }

    @Test
    fun `execute leaves a small timeout untouched`() {
        val disp = FakeDispatcher()
        val ex = McpApprovalExecutor(disp)
        val dir = createTempDir(prefix = "mcp-run")
        ex.execute(operation(argumentsJson = """{"command":"x","timeout_sec":5}"""), dir.path, McpApprovalExecutor.SourceContext())
        assertEquals(5, disp.lastArguments!!.optInt("timeout_sec"))
        dir.deleteRecursively()
    }

    @Test
    fun `execute refuses when the source binding does not match`() {
        val disp = FakeDispatcher()
        val ex = McpApprovalExecutor(disp)
        val dir = createTempDir(prefix = "mcp-run")
        val r = ex.execute(operation(sourceFileHash = "abc123"), dir.path, McpApprovalExecutor.SourceContext())
        assertFalse(r.ok)
        assertEquals(McpApprovalCore.ErrorCode.REQUEST_ID_MISMATCH, r.errorCode)
        assertEquals(0, disp.calls)
        assertFalse(File(dir, "arguments.json").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `execute survives a throwing dispatcher`() {
        val disp = FakeDispatcher(throwOnCall = true)
        val ex = McpApprovalExecutor(disp)
        val dir = createTempDir(prefix = "mcp-run")
        val r = ex.execute(operation(), dir.path, McpApprovalExecutor.SourceContext())
        assertFalse(r.ok)
        assertEquals(McpApprovalCore.ErrorCode.TASK_NOT_ACTIVE, r.errorCode)
        dir.deleteRecursively()
    }

    @Test
    fun `execute rejects non-JSON arguments before touching the dispatcher`() {
        val disp = FakeDispatcher()
        val ex = McpApprovalExecutor(disp)
        val dir = createTempDir(prefix = "mcp-run")
        val r = ex.execute(operation(argumentsJson = "not json"), dir.path, McpApprovalExecutor.SourceContext())
        assertFalse(r.ok)
        assertEquals(McpApprovalCore.ErrorCode.ARGUMENTS_TOO_LARGE, r.errorCode)
        assertEquals(0, disp.calls)
        dir.deleteRecursively()
    }
}
