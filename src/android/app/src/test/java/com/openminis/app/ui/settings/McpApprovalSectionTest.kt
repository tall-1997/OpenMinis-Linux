package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-approval-ui] The argument digest is the only piece of the approvals
 * sheet with logic worth pinning on the JVM: it must stay one line, collapse
 * whitespace, and never let a large payload grow the card.
 */
class McpApprovalSectionTest {

    @Test
    fun `short payloads are shown verbatim`() {
        assertEquals("""{"command":"id"}""", summarizeArguments("""{"command":"id"}"""))
    }

    @Test
    fun `whitespace and newlines collapse to single spaces`() {
        val pretty = "{\n  \"command\": \"echo   hi\",\n  \"timeout\": 5\n}"
        assertEquals("""{ "command": "echo hi", "timeout": 5 }""", summarizeArguments(pretty))
    }

    @Test
    fun `long payloads are truncated to the limit`() {
        val payload = "x".repeat(500)
        val out = summarizeArguments(payload, limit = 40)
        assertEquals(40, out.length)
        assertTrue("must end with the ellipsis marker", out.endsWith("…"))
        assertEquals(payload.take(39), out.dropLast(1))
    }

    @Test
    fun `default limit keeps the card to one line`() {
        val out = summarizeArguments("y".repeat(10_000))
        assertEquals(120, out.length)
    }

    @Test
    fun `outside whitespace is trimmed before measuring`() {
        val out = summarizeArguments("   \n\t  {}  \n ")
        assertEquals("{}", out)
    }
}
