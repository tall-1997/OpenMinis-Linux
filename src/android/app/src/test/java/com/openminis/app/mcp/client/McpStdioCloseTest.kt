package com.openminis.app.mcp.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-mcp-stdio-close-sequence] Contract tests for the close-sequence decision
 * core — pins the exact pi transports/stdio.ts numbers (stdin grace 500ms,
 * SIGTERM→SIGKILL window 2s, grace capped by the close timeout) and the
 * phase/window sequence the client must run.
 */
class McpStdioCloseTest {

    @Test
    fun `pi constants are pinned`() {
        assertEquals(500L, McpStdioClose.STDIN_CLOSE_GRACE_MS)
        assertEquals(2000L, McpStdioClose.DEFAULT_CLOSE_TIMEOUT_MS)
    }

    @Test
    fun `grace is the stdin window, capped by the close timeout`() {
        assertEquals(500L, McpStdioClose.graceMs(2000L))
        assertEquals(500L, McpStdioClose.graceMs(500L))
        assertEquals("a tiny close timeout caps the grace (pi: Math.min)", 200L, McpStdioClose.graceMs(200L))
    }

    @Test
    fun `sigterm window is the close timeout`() {
        assertEquals(2000L, McpStdioClose.sigtermWindowMs(2000L))
        assertEquals(200L, McpStdioClose.sigtermWindowMs(200L))
    }

    @Test
    fun `sigkill deadline is grace plus close timeout, measured from stdin close`() {
        assertEquals(2500L, McpStdioClose.sigkillDeadlineMs(2000L))
        assertEquals(400L, McpStdioClose.sigkillDeadlineMs(200L))
    }

    @Test
    fun `sequence drains stdin, then SIGTERM, then SIGKILL with pi windows`() {
        assertEquals(
            listOf(
                McpStdioClose.Phase.DRAIN_STDIN to 500L,
                McpStdioClose.Phase.SIGTERM to 2000L,
                McpStdioClose.Phase.SIGKILL to 0L,
            ),
            McpStdioClose.sequence(2000L),
        )
        assertEquals(
            listOf(
                McpStdioClose.Phase.DRAIN_STDIN to 200L,
                McpStdioClose.Phase.SIGTERM to 200L,
                McpStdioClose.Phase.SIGKILL to 0L,
            ),
            McpStdioClose.sequence(200L),
        )
    }
}
