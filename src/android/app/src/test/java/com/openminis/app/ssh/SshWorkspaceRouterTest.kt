package com.openminis.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ssh-workspace] (#32) Pure-JVM tests for the SSH workspace routing
 * table and the session → host binding store. No Android, no network: the
 * binding store runs over [InMemorySshWorkspaceStore].
 */
class SshWorkspaceRouterTest {

    // ------------------------------------------------------------ decide()

    @Test
    fun `unbound session routes everything locally`() {
        for (tool in listOf(
            "shell_execute", "env_exec", "su_exec", "file_read", "file_write",
            "web_search", "memory_write", "anything_else",
        )) {
            assertEquals(
                "tool=$tool must stay local without a binding",
                SshWorkspaceRouter.Decision.Local,
                SshWorkspaceRouter.decide(tool, null),
            )
        }
    }

    @Test
    fun `blank binding routes locally`() {
        assertEquals(
            SshWorkspaceRouter.Decision.Local,
            SshWorkspaceRouter.decide("shell_execute", "  "),
        )
    }

    @Test
    fun `shell tools route to the bound host`() {
        for (tool in SshWorkspaceRouter.SHELL_TOOLS) {
            assertEquals(
                SshWorkspaceRouter.Decision.RouteRemote("prod-box"),
                SshWorkspaceRouter.decide(tool, "prod-box"),
            )
        }
        // The documented pair explicitly.
        assertEquals(
            SshWorkspaceRouter.Decision.RouteRemote("h1"),
            SshWorkspaceRouter.decide("shell_execute", "h1"),
        )
        assertEquals(
            SshWorkspaceRouter.Decision.RouteRemote("h1"),
            SshWorkspaceRouter.decide("env_exec", "h1"),
        )
    }

    @Test
    fun `file surface tools are refused with the tool name preserved`() {
        for (tool in SshWorkspaceRouter.UNSUPPORTED_FILE_TOOLS) {
            val d = SshWorkspaceRouter.decide(tool, "prod-box")
            assertTrue("tool=$tool must be Unsupported", d is SshWorkspaceRouter.Decision.Unsupported)
            assertEquals(tool, (d as SshWorkspaceRouter.Decision.Unsupported).tool)
        }
    }

    @Test
    fun `host-only su_exec is refused in an ssh workspace`() {
        val d = SshWorkspaceRouter.decide("su_exec", "prod-box")
        assertTrue(d is SshWorkspaceRouter.Decision.Unsupported)
    }

    @Test
    fun `local terminal pty is refused in an ssh workspace`() {
        // The PTY would open on the phone's sandbox while the agent believes
        // it is on the remote host — the silent mismatch this router exists
        // to prevent.
        val d = SshWorkspaceRouter.decide("agent_terminal", "prod-box")
        assertTrue(d is SshWorkspaceRouter.Decision.Unsupported)
        assertEquals("agent_terminal", (d as SshWorkspaceRouter.Decision.Unsupported).tool)
        // Unbound sessions keep the local terminal.
        assertEquals(
            SshWorkspaceRouter.Decision.Local,
            SshWorkspaceRouter.decide("agent_terminal", null),
        )
    }

    @Test
    fun `non-shell non-file tools stay local even when bound`() {
        for (tool in listOf("web_search", "memory_write", "browser_use", "find_tools")) {
            assertEquals(
                "tool=$tool has no remote semantics and must stay local",
                SshWorkspaceRouter.Decision.Local,
                SshWorkspaceRouter.decide(tool, "prod-box"),
            )
        }
    }

    // ---------------------------------------------------------- bindings

    @Test
    fun `bind lookup unbind round trip`() {
        val store = InMemorySshWorkspaceStore()
        val bindings = SshWorkspaceBindings(store)

        assertNull(bindings.hostIdFor("session-1"))
        bindings.bind("session-1", "prod-box")
        assertEquals("prod-box", bindings.hostIdFor("session-1"))
        // Rebinding replaces.
        bindings.bind("session-1", "staging-box")
        assertEquals("staging-box", bindings.hostIdFor("session-1"))
        bindings.unbind("session-1")
        assertNull(bindings.hostIdFor("session-1"))
    }

    @Test
    fun `bindings are keyed per session`() {
        val bindings = SshWorkspaceBindings(InMemorySshWorkspaceStore())
        bindings.bind("s1", "h1")
        bindings.bind("s2", "h2")
        assertEquals("h1", bindings.hostIdFor("s1"))
        assertEquals("h2", bindings.hostIdFor("s2"))
        bindings.unbind("s1")
        assertNull(bindings.hostIdFor("s1"))
        assertEquals("h2", bindings.hostIdFor("s2"))
    }

    @Test
    fun `all returns only binding entries`() {
        val store = InMemorySshWorkspaceStore()
        store.putString("unrelated.pref", "keep")
        val bindings = SshWorkspaceBindings(store)
        bindings.bind("s1", "h1")
        bindings.bind("s2", "h2")
        assertEquals(mapOf("s1" to "h1", "s2" to "h2"), bindings.all())
    }

    @Test
    fun `blank or unsafe inputs are ignored`() {
        val bindings = SshWorkspaceBindings(InMemorySshWorkspaceStore())
        bindings.bind("", "h1")
        bindings.bind("s1", "")
        bindings.bind("  ", "h1")
        // Path-traversal-looking owner id must never enter the store.
        bindings.bind("../escape", "h1")
        assertEquals(emptyMap<String, String>(), bindings.all())
        assertNull(bindings.hostIdFor("../escape"))
        // Unknown owner lookup is null, not an exception.
        assertNull(bindings.hostIdFor("never-bound"))
    }

    @Test
    fun `owner session id resolution for subagent lanes`() {
        // The interceptor resolves sub-agent lane ids to the owning chat
        // session before the binding lookup — this is the documented
        // inheritance behavior, exercised through the same pure helper.
        assertEquals(
            "chat-1",
            com.openminis.app.sandbox.SessionWorkspace.ownerSessionId("subagent:chat-1:lane-2"),
        )
        assertEquals("chat-1", com.openminis.app.sandbox.SessionWorkspace.ownerSessionId("chat-1"))
    }

    @Test
    fun `binding key layout is stable`() {
        assertEquals("bind.owner-1", SshWorkspaceBindings.bindingKey("owner-1"))
    }

    @Test
    fun `in memory store isolates snapshots`() {
        val store = InMemorySshWorkspaceStore()
        store.putString("k", "v")
        val snap = store.all()
        store.putString("k2", "v2")
        assertFalse(snap.containsKey("k2"))
        assertTrue(store.all().containsKey("k2"))
        store.remove("k")
        assertNull(store.getString("k"))
    }
}
