package com.openminis.app.ssh

import com.openminis.app.sandbox.ExecOptions
import com.openminis.app.sandbox.ExecResult
import com.openminis.app.sandbox.ExecutionEnvironment
import com.openminis.app.sandbox.EnvironmentInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-ssh-exec-env] Registry lifecycle for the SSH session's
 * [com.openminis.app.sandbox.SshExecutionEnvironment] registration: the
 * teardown half (unbind / host removal sweep) is pure-JVM verifiable here —
 * [SshWorkspaceBindings] is the ONE choke point every unbind call site (the
 * binding sheet, the hosts manager, ssh remove_host) goes through, so a test
 * against it covers all of them. The registration half lives in
 * SshWorkspaceOps.intercept (needs an Android Context for the backend), so
 * this file pins its CONTRACT side: the keys it registers under are the owner
 * session ids the teardown paths remove.
 */
class SshWorkspaceRegistryLifecycleTest {

    /** Minimal fake: the registry stores references, nothing executes here. */
    private class FakeEnvironment(private val host: String) : ExecutionEnvironment {
        override val id: String = "ssh:$host"
        override val workingDir: String = "~"
        override suspend fun exec(command: String, options: ExecOptions) =
            ExecResult(0, "", "", 0)
        override fun environmentInfo() = EnvironmentInfo(
            kind = "ssh", os = "linux", shell = "/bin/sh", pathSeparator = "/",
            mergesStderr = false, supportsPerCallEnv = true, supportsStdin = false,
        )
    }

    private fun bindings(): SshWorkspaceBindings = SshWorkspaceBindings(InMemorySshWorkspaceStore())

    /** Registered keys this test must clean up (the registry is process-wide). */
    private val registeredKeys = mutableListOf<String>()

    private fun registerTracked(sessionId: String, host: String) {
        com.openminis.app.sandbox.ExecutionEnvironments.register(sessionId, FakeEnvironment(host))
        registeredKeys.add(sessionId)
    }

    @After
    fun tearDown() {
        registeredKeys.forEach {
            com.openminis.app.sandbox.ExecutionEnvironments.unregister(it)
        }
        registeredKeys.clear()
    }

    @Test
    fun unbindClearsTheRegisteredEnvironmentForTheSession() {
        val b = bindings()
        b.bind("chat-1", "host-a")
        registerTracked("chat-1", "host-a")
        assertNotNull(com.openminis.app.sandbox.ExecutionEnvironments.overrideFor("chat-1"))

        // The single teardown choke point every UI/agent unbind goes through.
        b.unbind("chat-1")

        assertNull(
            "leaving SSH must unregister the session's ExecutionEnvironment",
            com.openminis.app.sandbox.ExecutionEnvironments.overrideFor("chat-1"),
        )
        assertNull(b.hostIdFor("chat-1"))
    }

    @Test
    fun unbindAllForHostSweepsEveryOwnerBoundToThatHostOnly() {
        val b = bindings()
        b.bind("chat-1", "host-a")
        b.bind("chat-2", "host-a")
        b.bind("chat-3", "host-b")
        listOf("chat-1" to "host-a", "chat-2" to "host-a", "chat-3" to "host-b")
            .forEach { (session, host) -> registerTracked(session, host) }

        val cleared = b.unbindAllForHost("host-a")

        assertEquals(2, cleared)
        assertNull(com.openminis.app.sandbox.ExecutionEnvironments.overrideFor("chat-1"))
        assertNull(com.openminis.app.sandbox.ExecutionEnvironments.overrideFor("chat-2"))
        // A host removal must not tear down sessions bound to OTHER hosts.
        assertNotNull(
            "chat-3 is bound to a different host and keeps its environment",
            com.openminis.app.sandbox.ExecutionEnvironments.overrideFor("chat-3"),
        )
    }

    @Test
    fun bindAloneDoesNotCreateARegistryEntry() {
        // Registration happens at the first remote-routed call (intercept),
        // not at bind time: bind() has no backend access and the binding alone
        // is not an execution environment. The teardown paths above still
        // guarantee the entry cannot outlive the binding once it exists.
        val b = bindings()
        b.bind("chat-1", "host-a")
        assertNull(com.openminis.app.sandbox.ExecutionEnvironments.overrideFor("chat-1"))
    }

    @Test
    fun rebindReplacesTheEntryTheNextInterceptWouldRefresh() {
        // The intercept registers with an id guard (ssh:<hostId>), so a rebind
        // to another host replaces the stale environment. This pins the id
        // shape the guard compares against.
        val env = FakeEnvironment("host-a")
        assertEquals("ssh:host-a", env.id)
        registerTracked("chat-1", "host-a")
        val replaced = com.openminis.app.sandbox.ExecutionEnvironments
            .register("chat-1", FakeEnvironment("host-b"))
        assertNotNull(replaced)
        assertEquals("ssh:host-b", com.openminis.app.sandbox.ExecutionEnvironments
            .overrideFor("chat-1")?.id)
    }

    @Test
    fun unregisterOfUnknownSessionIsANoOp() {
        assertNull(com.openminis.app.sandbox.ExecutionEnvironments.unregister("never-registered"))
    }
}
