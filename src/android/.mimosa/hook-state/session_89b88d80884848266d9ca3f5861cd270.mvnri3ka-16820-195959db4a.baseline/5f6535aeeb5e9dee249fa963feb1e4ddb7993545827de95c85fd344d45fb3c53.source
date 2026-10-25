package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-policy-chained-bypass] The host-side gate parses `minis-mcp-cli`
 * commands out of an arbitrary shell command string. Pure parsing — no
 * Context — so the bypass surface is pinned here: chained invocations are ALL
 * collected (the old first-match parse judged `call a t1 && call b t2` on the
 * first alone), value flags are skipped, and quotes are stripped.
 */
class MCPToolPolicyParseTest {

    @Test
    fun `chained calls yield every invocation`() {
        val invs = MCPToolPolicy.parseAll(
            "minis-mcp-cli call git git_status && minis-mcp-cli call git git_commit"
        )
        assertEquals(2, invs.size)
        assertEquals(MCPToolPolicy.Invocation("call", "git", "git_status"), invs[0])
        assertEquals(MCPToolPolicy.Invocation("call", "git", "git_commit"), invs[1])
    }

    @Test
    fun `chained mixed with tools subcommand`() {
        val invs = MCPToolPolicy.parseAll(
            "minis-mcp-cli tools git; minis-mcp-cli call git git_commit"
        )
        assertEquals(2, invs.size)
        assertEquals(MCPToolPolicy.Invocation("tools", "git", null), invs[0])
        assertEquals(MCPToolPolicy.Invocation("call", "git", "git_commit"), invs[1])
    }

    @Test
    fun `input flag value is not mistaken for the tool`() {
        val invs = MCPToolPolicy.parseAll(
            """minis-mcp-cli call git git_commit --input '{"message":"x"}'"""
        )
        assertEquals(listOf(MCPToolPolicy.Invocation("call", "git", "git_commit")), invs)
    }

    @Test
    fun `quoted server and tool are stripped`() {
        val invs = MCPToolPolicy.parseAll(
            """minis-mcp-cli call 'my server' "my tool" """
        )
        assertEquals(listOf(MCPToolPolicy.Invocation("call", "my server", "my tool")), invs)
    }

    @Test
    fun `absolute cli path is recognized`() {
        val invs = MCPToolPolicy.parseAll("/usr/local/bin/minis-mcp-cli call git git_status")
        assertEquals(listOf(MCPToolPolicy.Invocation("call", "git", "git_status")), invs)
    }

    @Test
    fun `non cli commands parse to nothing`() {
        assertNull(MCPToolPolicy.parse("git status && ls -la"))
        assertTrue(MCPToolPolicy.parseAll("rm -rf /tmp/x").isEmpty())
    }

    @Test
    fun `parse stays first-invocation compatible`() {
        val cmd = "minis-mcp-cli call git git_status && minis-mcp-cli call git git_commit"
        assertEquals(MCPToolPolicy.parseAll(cmd).first(), MCPToolPolicy.parse(cmd))
    }
}
