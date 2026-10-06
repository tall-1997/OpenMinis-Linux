package com.openminis.app.tools

import com.openminis.app.mcp.client.McpJsonRpc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-native] Pure-JVM coverage of the `mcp` tool surface: the schema the
 * model sees and the McpError → ToolErrorCode mapping that drives recovery
 * hints. execute() itself needs Context + repository and is covered by the
 * instrumented side; the mapping is where the CLI-parity behaviour lives.
 */
class McpNativeToolMappingTest {

    @Test
    fun definitionShape() {
        val def = McpNativeTool.definition()
        assertEquals("mcp", def.name)
        assertEquals(listOf("action"), def.required)
        assertTrue(def.parameters.keys.containsAll(listOf("action", "server", "tool", "arguments")))
        assertEquals(
            listOf("servers", "tools", "call"),
            def.parameters["action"]?.enumValues,
        )
        assertTrue(def.description.contains("minis-mcp-cli"))
    }

    @Test
    fun connectionErrorMapsToNetwork() {
        val r = McpNativeTool.failure(
            McpJsonRpc.McpError(McpJsonRpc.McpError.CONNECTION_ERROR, "connect failed"),
            "mcp call:git/get_diff",
        )
        assertFalse(r.success)
        assertEquals(ToolErrorCode.NETWORK_ERROR, r.errorCode)
        assertEquals("[CONNECTION_ERROR] connect failed", r.output)
        assertEquals("mcp call:git/get_diff", r.toolTitle)
        assertTrue(r.recoveryHint!!.contains("Settings → MCP"))
    }

    @Test
    fun timeoutMapsToTimeout() {
        val r = McpNativeTool.failure(
            McpJsonRpc.McpError(McpJsonRpc.McpError.TIMEOUT, "timed out"),
            "mcp tools:x",
        )
        assertEquals(ToolErrorCode.TIMEOUT, r.errorCode)
        assertTrue(r.recoveryHint!!.contains("startupTimeoutSeconds"))
    }

    @Test
    fun authRequiredMapsAndHintsOAuth() {
        val r = McpNativeTool.failure(
            McpJsonRpc.McpError(McpJsonRpc.McpError.AUTH_REQUIRED, "401"),
            "mcp",
        )
        assertEquals(ToolErrorCode.AUTH_REQUIRED, r.errorCode)
        assertTrue(r.recoveryHint!!.contains("OAuth"))
    }

    @Test
    fun notFoundPointsToServersAction() {
        val r = McpNativeTool.failure(McpJsonRpc.McpError("NOT_FOUND", "no such server"), "mcp")
        assertEquals(ToolErrorCode.NOT_FOUND, r.errorCode)
        assertTrue(r.recoveryHint!!.contains("action=servers"))
    }

    @Test
    fun toolDisabledMapsToPermissionDenied() {
        val r = McpNativeTool.failure(McpJsonRpc.McpError("TOOL_DISABLED", "off by policy"), "mcp")
        assertEquals(ToolErrorCode.PERMISSION_DENIED, r.errorCode)
    }

    @Test
    fun unknownCodeFallsBackToExecutionFailed() {
        val r = McpNativeTool.failure(McpJsonRpc.McpError("SOMETHING_ELSE", "boom"), "mcp")
        assertEquals(ToolErrorCode.EXECUTION_FAILED, r.errorCode)
        assertNull(r.recoveryHint)
        assertEquals("[SOMETHING_ELSE] boom", r.output)
    }
}
