package com.openminis.app.sandbox

import com.openminis.app.ssh.InMemorySecretBox
import com.openminis.app.ssh.SshBackend
import com.openminis.app.ssh.SshConfigStore
import com.openminis.app.ssh.SshExecResult
import com.openminis.app.ssh.SshFailure
import com.openminis.app.ssh.SshKnownHosts
import com.openminis.app.ssh.SshLsEntry
import com.openminis.app.tools.ToolErrorCode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [pi-port §2.6] ExecutionEnvironment contract, pinned with fakes (no real
 * PRoot, no real SSH): interface shape, PRoot delegation mapping, SSH
 * delegation mapping, and the override registry the dispatcher consults.
 */
class ExecutionEnvironmentTest {

    // ── interface contract (fake impl) ──

    /** Minimal fake: records the request, answers from a canned result. */
    private class FakeEnvironment(
        var result: ExecResult = ExecResult(0, "ok", "", 5),
        var failure: Exception? = null,
    ) : ExecutionEnvironment {
        var lastCommand: String? = null
        var lastOptions: ExecOptions? = null

        override val id: String = "fake:test"
        override val workingDir: String = "/tmp"

        override suspend fun exec(command: String, options: ExecOptions): ExecResult {
            lastCommand = command
            lastOptions = options
            failure?.let { throw it }
            return result
        }

        override fun environmentInfo(): EnvironmentInfo = EnvironmentInfo(
            kind = "fake", os = "linux", shell = "/bin/sh", pathSeparator = "/",
            mergesStderr = false, supportsPerCallEnv = true, supportsStdin = false,
        )
    }

    @Test
    fun fakeImplPinsTheInterfaceContract() = runBlocking {
        val env = FakeEnvironment(ExecResult(exitCode = 3, stdout = "out", stderr = "err", durationMs = 42))
        val r = env.exec("echo hi", ExecOptions(timeoutMs = 5000, env = mapOf("K" to "V")))
        assertEquals("echo hi", env.lastCommand)
        assertEquals(5000L, env.lastOptions?.timeoutMs)
        assertEquals("V", env.lastOptions?.env?.get("K"))
        assertEquals(3, r.exitCode)
        assertEquals("out", r.stdout)
        assertEquals("err", r.stderr)
        assertEquals(42L, r.durationMs)
        assertEquals("/tmp", env.workingDir)
        assertEquals("fake", env.environmentInfo().kind)
    }

    // ── PRoot impl delegation (the coordinator faked behind the seam) ──

    private class FakeCoordinatorDelegate : PRootExecutionEnvironment.Delegate {
        var lastCommand: String? = null
        var lastTimeoutMs: Long = -1
        var lastResourceClass: SandboxResourceGate.ResourceClass? = null
        var answer: ExecutionCoordinator.CommandResult =
            ExecutionCoordinator.CommandResult(output = "done", exitCode = 0, durationMs = 7)

        override suspend fun execute(
            command: String,
            timeoutMs: Long,
            resourceClass: SandboxResourceGate.ResourceClass,
            lineCallback: ((String) -> Unit)?,
        ): ExecutionCoordinator.CommandResult {
            lastCommand = command
            lastTimeoutMs = timeoutMs
            lastResourceClass = resourceClass
            return answer
        }
    }

    @Test
    fun prootImplDelegatesAndMapsCommandResult() = runBlocking {
        val delegate = FakeCoordinatorDelegate()
        val env = PRootExecutionEnvironment("session-1", delegate)
        val r = env.exec("ls -la", ExecOptions(timeoutMs = 123_000))
        assertEquals("ls -la", delegate.lastCommand)
        assertEquals(123_000L, delegate.lastTimeoutMs)
        assertEquals(SandboxResourceGate.ResourceClass.AUTO, delegate.lastResourceClass)
        // Merged-stream semantics: the coordinator's combined output is stdout.
        assertEquals(0, r.exitCode)
        assertEquals("done", r.stdout)
        assertEquals("", r.stderr)
        assertEquals(7L, r.durationMs)
        assertFalse(r.timedOut)
    }

    @Test
    fun prootImplMapsTimedOutVerdictNotExitCodeGuess() = runBlocking {
        val delegate = FakeCoordinatorDelegate().apply {
            // A user command may legitimately exit 124 without being a timeout.
            answer = ExecutionCoordinator.CommandResult(output = "partial", exitCode = 124, durationMs = 900, timedOut = false)
        }
        val env = PRootExecutionEnvironment("s", delegate)
        assertFalse(env.exec("timeout 5 sleep 10").timedOut)

        delegate.answer = ExecutionCoordinator.CommandResult(output = "", exitCode = 0, durationMs = 1, timedOut = true)
        assertTrue(env.exec("x").timedOut)
    }

    @Test
    fun prootImplNonZeroTimeoutFallsBackToDefault() = runBlocking {
        val delegate = FakeCoordinatorDelegate()
        val env = PRootExecutionEnvironment("s", delegate)
        env.exec("x", ExecOptions(timeoutMs = 0))
        assertEquals(ExecOptions().timeoutMs, delegate.lastTimeoutMs)
    }

    @Test
    fun prootEnvironmentInfoDeclaresMergedStreamsAndNoPerCallEnv() {
        val info = PRootExecutionEnvironment("s") { _, _, _, _ ->
            ExecutionCoordinator.CommandResult("", 0, 0)
        }.environmentInfo()
        assertEquals("proot", info.kind)
        assertEquals("linux", info.os)
        assertTrue(info.mergesStderr)
        assertFalse(info.supportsPerCallEnv)
        assertFalse(info.supportsStdin)
        assertEquals("/", info.pathSeparator)
    }

    // ── SSH impl delegation (fake backend, the SshToolTest pattern) ──

    private class FakeSshBackend(override val store: SshConfigStore) : SshBackend {
        override val knownHosts = SshKnownHosts(null)
        var execResult = SshExecResult(0, "remote out", "remote err", false, 88, "u@h:22")
        var failure: SshFailure? = null
        var lastCommand: String? = null
        var lastTimeoutMs: Long = -1
        var lastCwd: String? = null

        override fun test(hostRef: String): String = "connected"
        override fun exec(hostRef: String, command: String, timeoutMs: Long, cwd: String?): SshExecResult {
            failure?.let { throw it }
            lastCommand = command
            lastTimeoutMs = timeoutMs
            lastCwd = cwd
            return execResult
        }
        override fun ls(hostRef: String, path: String): List<SshLsEntry> = emptyList()
        override fun get(hostRef: String, remotePath: String, localFile: File): Long = 0
        override fun put(hostRef: String, localFile: File, remotePath: String): Long = 0
        override fun forgetHostKey(hostRef: String) = Unit
        override fun removeHost(hostRef: String): Boolean = true
        override fun close() = Unit
    }

    private fun fakeBackend(): FakeSshBackend {
        val dir = File(System.getProperty("java.io.tmpdir"), "exec-env-test-${System.nanoTime()}").apply { mkdirs() }
        dir.deleteOnExit()
        return FakeSshBackend(SshConfigStore(File(dir, "hosts.json"), InMemorySecretBox()))
    }

    @Test
    fun sshImplDelegatesAndKeepsStreamsSeparate() = runBlocking {
        val backend = fakeBackend()
        val env = SshExecutionEnvironment(backend, hostRef = "web-1", cwd = "/srv")
        val r = env.exec("uname -a", ExecOptions(timeoutMs = 60_000))
        assertEquals("uname -a", backend.lastCommand)
        assertEquals(60_000L, backend.lastTimeoutMs)
        assertEquals("/srv", backend.lastCwd)
        // Split-stream semantics: stdout and stderr stay separate.
        assertEquals(0, r.exitCode)
        assertEquals("remote out", r.stdout)
        assertEquals("remote err", r.stderr)
        assertEquals(88L, r.durationMs)
        assertFalse(r.timedOut)
    }

    @Test
    fun sshImplAppliesPerCallEnvAsExportPrefix() = runBlocking {
        val backend = fakeBackend()
        val env = SshExecutionEnvironment(backend, "h")
        env.exec("echo done", ExecOptions(env = mapOf("FOO" to "it's bar")))
        // POSIX single-quote escaping keeps the value intact inside the export.
        assertEquals("export FOO='it'\\''s bar'; echo done", backend.lastCommand)
    }

    @Test
    fun sshImplLeavesCommandUnchangedWithoutEnv() = runBlocking {
        val backend = fakeBackend()
        val env = SshExecutionEnvironment(backend, "h")
        env.exec("systemctl status nginx")
        assertEquals("systemctl status nginx", backend.lastCommand)
    }

    @Test
    fun sshImplTransportFailureThrowsAndNeverFabricatesAnExitCode() = runBlocking {
        val backend = fakeBackend().apply { failure = SshFailure.Auth("u@h:22", "denied") }
        val env = SshExecutionEnvironment(backend, "h")
        var thrown = false
        try {
            env.exec("ls")
        } catch (e: SshFailure) {
            thrown = true
        }
        assertTrue("SshFailure must propagate per the interface contract", thrown)
    }

    @Test
    fun sshEnvironmentInfoDeclaresSplitStreamsAndPerCallEnv() {
        val backend = fakeBackend()
        val info = SshExecutionEnvironment(backend, "h").environmentInfo()
        assertEquals("ssh", info.kind)
        assertEquals("linux", info.os)
        assertFalse(info.mergesStderr)
        assertTrue(info.supportsPerCallEnv)
        assertFalse(info.supportsStdin)
    }

    // ── override registry + shell mapping ──

    @Test
    fun registryRegisterOverrideForUnregisterRoundTrip() {
        assertNull(ExecutionEnvironments.overrideFor("sess-A"))
        val env = FakeEnvironment()
        ExecutionEnvironments.register("sess-A", env)
        assertEquals(env, ExecutionEnvironments.overrideFor("sess-A"))
        assertEquals(env, ExecutionEnvironments.unregister("sess-A"))
        assertNull(ExecutionEnvironments.overrideFor("sess-A"))
    }

    @Test
    fun executeShellMapsExitCodeSuffixAndSuccessLikeTheLegacyPath() = runBlocking {
        val env = FakeEnvironment(ExecResult(2, "partial\noutput", "", 10))
        val r = ExecutionEnvironments.executeShell(env, """{"command":"boom","tool_title":"T"}""")
        assertEquals("T", r.toolTitle)
        assertFalse(r.success)
        assertEquals("partial\noutput (exit code 2)", r.output)
        assertFalse(r.timedOut)
        assertNull(r.errorCode)
        assertEquals("boom", env.lastCommand)
    }

    @Test
    fun executeShellMapsTimeoutVerdictToTimedOutStatus() = runBlocking {
        val env = FakeEnvironment(ExecResult(124, "", "", 600_000, timedOut = true))
        val r = ExecutionEnvironments.executeShell(env, """{"command":"slow"}""")
        assertFalse(r.success)
        assertTrue(r.timedOut)
        assertEquals(ToolErrorCode.TIMEOUT, r.errorCode)
    }

    @Test
    fun executeShellFeedsTimeoutSecondsCappedAtTheRemoteCeiling() = runBlocking {
        val env = FakeEnvironment()
        ExecutionEnvironments.executeShell(env, """{"command":"x","timeout":30}""")
        assertEquals(30_000L, env.lastOptions?.timeoutMs)
        ExecutionEnvironments.executeShell(env, """{"command":"x","timeout":100000}""")
        assertEquals(900_000L, env.lastOptions?.timeoutMs)
    }

    @Test
    fun executeShellBlankCommandFailsBeforeTouchingTheEnvironment() = runBlocking {
        val env = FakeEnvironment()
        val r = ExecutionEnvironments.executeShell(env, """{"command":"   "}""")
        assertFalse(r.success)
        assertEquals("shell_execute", r.toolTitle)
        assertNull(env.lastCommand)
    }

    @Test
    fun executeShellTransportFailureIsAnHonestFailureNotACommandResult() = runBlocking {
        val env = FakeEnvironment(failure = IllegalStateException("sandbox not booted"))
        val r = ExecutionEnvironments.executeShell(env, """{"command":"ls"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.EXECUTION_FAILED, r.errorCode)
        assertTrue(r.output.contains("sandbox not booted"))
    }

    @Test
    fun executeShellZeroExitHasNoSuffix() = runBlocking {
        val env = FakeEnvironment(ExecResult(0, "hello", "", 3))
        val r = ExecutionEnvironments.executeShell(env, """{"command":"echo hello"}""")
        assertTrue(r.success)
        assertEquals("hello", r.output)
    }
}
