package com.openminis.app.tools

import com.openminis.app.ssh.InMemorySecretBox
import com.openminis.app.ssh.SshAuthType
import com.openminis.app.ssh.SshBackend
import com.openminis.app.ssh.SshCommandWrap
import com.openminis.app.ssh.SshConfigStore
import com.openminis.app.ssh.SshExecResult
import com.openminis.app.ssh.SshFailure
import com.openminis.app.ssh.SshKnownHosts
import com.openminis.app.ssh.SshLsEntry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-ssh-backend] Routing/formatting tests for the ssh_exec tool surface.
 *
 * A FakeBackend replaces JSch; a real (temp-file) SshConfigStore stands in
 * for the host registry, so add_host / list_hosts are exercised end-to-end —
 * including the secret-hygiene invariant: credentials never appear in tool
 * output, and `env:VAR` references are resolved through the injected
 * resolver (keeping values out of chat history).
 */
class SshToolTest {

    private class FakeBackend(
        override val store: SshConfigStore,
        override val knownHosts: SshKnownHosts = SshKnownHosts(null),
    ) : SshBackend {
        var execResult: SshExecResult = SshExecResult(0, "ok\n", "", false, 12, "u@h:22")
        var failure: SshFailure? = null
        var genericError: RuntimeException? = null
        var lastCommand: String? = null
        var lastTimeoutMs: Long = -1
        var lastCwd: String? = null
        var lsResult: List<SshLsEntry> = emptyList()
        var lastLsPath: String? = null
        var getBytes: Long = 42
        var putBytes: Long = 7
        var lastGetFile: File? = null
        var lastPutFile: File? = null
        var removeResult: Boolean = true
        val removedRefs = mutableListOf<String>()
        val forgotRefs = mutableListOf<String>()
        var testSummary: String = "connected to u@h:22 · server=SSH-2.0-Fake · hostKey=SHA256:fake"

        private fun failIfSet() {
            genericError?.let { throw it }
            failure?.let { throw it }
        }

        override fun test(hostRef: String): String {
            failIfSet()
            return testSummary
        }

        override fun exec(hostRef: String, command: String, timeoutMs: Long, cwd: String?): SshExecResult {
            failIfSet()
            lastCommand = command
            lastTimeoutMs = timeoutMs
            lastCwd = cwd
            return execResult
        }

        override fun ls(hostRef: String, path: String): List<SshLsEntry> {
            failIfSet()
            lastLsPath = path
            return lsResult
        }

        override fun get(hostRef: String, remotePath: String, localFile: File): Long {
            failIfSet()
            lastGetFile = localFile
            localFile.parentFile?.mkdirs()
            localFile.writeText("data")
            return getBytes
        }

        override fun put(hostRef: String, localFile: File, remotePath: String): Long {
            failIfSet()
            lastPutFile = localFile
            return putBytes
        }

        override fun forgetHostKey(hostRef: String) {
            failIfSet()
            forgotRefs += hostRef
        }

        override fun removeHost(hostRef: String): Boolean {
            failIfSet()
            removedRefs += hostRef
            return removeResult
        }

        override fun close() = Unit
    }

    private lateinit var tmp: File
    private lateinit var metaFile: File
    private lateinit var fake: FakeBackend

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "ssh-tool-test-${System.nanoTime()}").apply { mkdirs() }
        metaFile = File(tmp, "hosts.json")
        fake = FakeBackend(SshConfigStore(metaFile, InMemorySecretBox()))
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun route(
        argsJson: String,
        pathResolver: (String) -> File? = { null },
        envResolver: (String) -> String? = { null },
    ): ToolExecutionResult = SshTool.route(argsJson, fake, pathResolver, envResolver)

    // ------------------------------------------------------------ arguments

    @Test
    fun badJsonIsInvalid() {
        val r = route("not json")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
    }

    @Test
    fun unknownActionIsInvalid() {
        val r = route("""{"action":"dance"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
        assertTrue(r.output.contains("list_hosts"))
    }

    // ------------------------------------------------------------ hosts

    @Test
    fun listHostsEmptyGuides() {
        val r = route("""{"action":"list_hosts"}""")
        assertTrue(r.success)
        assertTrue(r.output.contains("No SSH hosts configured"))
        assertTrue(r.output.contains("add_host"))
    }

    @Test
    fun addHostPasswordStoresWithoutEchoing() {
        val r = route(
            """{"action":"add_host","name":"web","hostname":"10.0.0.5","username":"root","auth_type":"password","password":"hunter2"}""",
        )
        assertTrue(r.output, r.success)
        assertFalse("secret must never be echoed", r.output.contains("hunter2"))
        val h = fake.store.find("web")
        assertNotNull(h)
        assertEquals(SshAuthType.PASSWORD, h!!.authType)
        assertEquals("hunter2", fake.store.credentialsFor("web")?.password)
        // metadata file stays clean
        assertFalse(metaFile.readText().contains("hunter2"))
    }

    @Test
    fun addHostResolvesEnvReference() {
        val r = route(
            """{"action":"add_host","name":"web","hostname":"h","username":"u","auth_type":"password","password":"env:MY_PW"}""",
            envResolver = { if (it == "MY_PW") "s3cret" else null },
        )
        assertTrue(r.success)
        assertEquals("s3cret", fake.store.credentialsFor("web")?.password)
        assertFalse(r.output.contains("s3cret"))
    }

    @Test
    fun addHostUnsetEnvVarIsInvalid() {
        val r = route(
            """{"action":"add_host","name":"web","hostname":"h","username":"u","auth_type":"password","password":"env:NOPE"}""",
        )
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
    }

    @Test
    fun addHostKeyPathImportsPem() {
        val keyFile = File(tmp, "id_test").apply {
            writeText("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n")
        }
        val r = route(
            """{"action":"add_host","name":"web","hostname":"h","username":"u","auth_type":"private_key","private_key_path":"/var/minis/workspace/id_test"}""",
            pathResolver = { p -> if (p == "/var/minis/workspace/id_test") keyFile else null },
        )
        assertTrue(r.output, r.success)
        assertTrue(fake.store.credentialsFor("web")!!.privateKeyPem!!.contains("PRIVATE KEY"))
    }

    @Test
    fun addHostUnresolvableKeyPathIsInvalid() {
        val r = route(
            """{"action":"add_host","name":"web","hostname":"h","username":"u","auth_type":"private_key","private_key_path":"/etc/shadow"}""",
        )
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
    }

    @Test
    fun addHostNonPemKeyIsInvalid() {
        val r = route(
            """{"action":"add_host","name":"web","hostname":"h","username":"u","auth_type":"private_key","private_key":"garbage"}""",
        )
        assertFalse(r.success)
        assertTrue(r.output.contains("PRIVATE KEY"))
    }

    @Test
    fun addHostBadNameSurfacesConfigFailure() {
        val r = route(
            """{"action":"add_host","name":"bad name!","hostname":"h","username":"u","auth_type":"password","password":"p"}""",
        )
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
    }

    @Test
    fun listHostsShowsAddedHost() {
        route("""{"action":"add_host","name":"web","hostname":"10.0.0.5","username":"root","auth_type":"password","password":"p"}""")
        val r = route("""{"action":"list_hosts"}""")
        assertTrue(r.output.contains("web"))
        assertTrue(r.output.contains("root@10.0.0.5:22"))
        assertFalse(r.output.contains("\"p\""))
    }

    @Test
    fun removeHostRoutes() {
        val ok = route("""{"action":"remove_host","host":"web"}""")
        assertTrue(ok.success)
        assertEquals(listOf("web"), fake.removedRefs)
        fake.removeResult = false
        val miss = route("""{"action":"remove_host","host":"ghost"}""")
        assertFalse(miss.success)
        assertEquals(ToolErrorCode.NOT_FOUND, miss.errorCode)
    }

    @Test
    fun forgetHostKeyRoutes() {
        val r = route("""{"action":"forget_host_key","host":"web"}""")
        assertTrue(r.success)
        assertEquals(listOf("web"), fake.forgotRefs)
        assertTrue(r.output.contains("Forgot the pinned host key"))
    }

    @Test
    fun testActionReturnsSummary() {
        val r = route("""{"action":"test","host":"web"}""")
        assertTrue(r.success)
        assertEquals(fake.testSummary, r.output)
    }

    // ------------------------------------------------------------ exec

    @Test
    fun execFormatsExitAndStreams() {
        fake.execResult = SshExecResult(0, "hello", "warn", false, 25, "u@h:22")
        val r = route("""{"action":"exec","host":"web","command":"echo hi"}""")
        assertTrue(r.success)
        assertTrue(r.output.contains("[exit 0] u@h:22 · 25ms"))
        assertTrue(r.output.contains("hello"))
        assertTrue(r.output.contains("--- stderr ---"))
        assertTrue(r.output.contains("warn"))
    }

    @Test
    fun execNonZeroExitIsDataNotFailure() {
        fake.execResult = SshExecResult(3, "", "boom", false, 5, "u@h:22")
        val r = route("""{"action":"exec","host":"web","command":"false"}""")
        assertTrue("non-zero exit is data, like shell_execute", r.success)
        assertTrue(r.output.contains("[exit 3]"))
        assertTrue(r.output.contains("boom"))
    }

    @Test
    fun execEmptyOutputMarker() {
        fake.execResult = SshExecResult(0, "", "", false, 5, "u@h:22")
        val r = route("""{"action":"exec","host":"web","command":"true"}""")
        assertTrue(r.output.contains("(no output)"))
    }

    @Test
    fun execTruncationAnnotated() {
        fake.execResult = SshExecResult(0, "x", "", false, 5, "u@h:22", truncated = true)
        val r = route("""{"action":"exec","host":"web","command":"yes"}""")
        assertTrue(r.output.contains("output truncated at 200KB per stream"))
    }

    @Test
    fun execTimeoutSurfacesPartialOutput() {
        fake.execResult = SshExecResult(-1, "partial", "", true, 30_000, "u@h:22")
        val r = route("""{"action":"exec","host":"web","command":"sleep 999","timeout":30}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.TIMEOUT, r.errorCode)
        assertTrue(r.timedOut)
        assertTrue(r.output.contains("partial"))
    }

    @Test
    fun execPassesTimeoutAndCwd() {
        route("""{"action":"exec","host":"web","command":"ls","timeout":30,"cwd":"/srv"}""")
        assertEquals(30_000L, fake.lastTimeoutMs)
        assertEquals("/srv", fake.lastCwd)
        assertEquals("ls", fake.lastCommand)
    }

    @Test
    fun execDefaultTimeoutIs60s() {
        route("""{"action":"exec","host":"web","command":"ls"}""")
        assertEquals(60_000L, fake.lastTimeoutMs)
        assertNull(fake.lastCwd)
    }

    @Test
    fun execTimeoutClampedTo900s() {
        route("""{"action":"exec","host":"web","command":"ls","timeout":99999}""")
        assertEquals(900_000L, fake.lastTimeoutMs)
    }

    @Test
    fun execRequiresCommand() {
        val r = route("""{"action":"exec","host":"web"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
    }

    @Test
    fun execRequiresHost() {
        val r = route("""{"action":"exec","command":"ls"}""")
        assertFalse(r.success)
        assertTrue(r.output.contains("'host'"))
    }

    // ------------------------------------------------------------ sftp

    @Test
    fun lsFormatsEntries() {
        fake.lsResult = listOf(
            SshLsEntry("src", true, 4096, 0),
            SshLsEntry("a.txt", false, 100, 1_700_000_000),
        )
        val r = route("""{"action":"ls","host":"web","remote_path":"/srv"}""")
        assertTrue(r.success)
        assertEquals("/srv", fake.lastLsPath)
        assertTrue(r.output.contains("web:/srv (2 entries)"))
        assertTrue(r.output.contains("d src"))
        assertTrue(r.output.contains("- a.txt 100B 1700000000"))
    }

    @Test
    fun lsDefaultsToDotAndShowsEmpty() {
        val r = route("""{"action":"ls","host":"web"}""")
        assertEquals(".", fake.lastLsPath)
        assertTrue(r.output.contains("(empty) web:."))
    }

    @Test
    fun getDownloadsToResolvedPath() {
        val dest = File(tmp, "out.bin")
        val r = route(
            """{"action":"get","host":"web","remote_path":"/remote/f.bin","local_path":"/var/minis/workspace/out.bin"}""",
            pathResolver = { p -> if (p == "/var/minis/workspace/out.bin") dest else null },
        )
        assertTrue(r.output, r.success)
        assertEquals(42L, fake.getBytes)
        assertTrue(r.output.contains("Downloaded 42 bytes"))
        assertTrue(dest.isFile)
    }

    @Test
    fun getUnresolvableLocalPathIsInvalid() {
        val r = route("""{"action":"get","host":"web","remote_path":"/r","local_path":"/etc/passwd"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
    }

    @Test
    fun getRequiresRemotePath() {
        val r = route("""{"action":"get","host":"web","local_path":"/var/minis/workspace/x"}""")
        assertFalse(r.success)
        assertTrue(r.output.contains("'remote_path'"))
    }

    @Test
    fun putUploadsExistingFile() {
        val src = File(tmp, "up.bin").apply { writeText("payload") }
        val r = route(
            """{"action":"put","host":"web","local_path":"/var/minis/workspace/up.bin","remote_path":"/remote/up.bin"}""",
            pathResolver = { p -> if (p == "/var/minis/workspace/up.bin") src else null },
        )
        assertTrue(r.output, r.success)
        assertTrue(r.output.contains("Uploaded 7 bytes"))
        assertEquals(src, fake.lastPutFile)
    }

    @Test
    fun putMissingLocalFileIsInvalid() {
        val ghost = File(tmp, "ghost.bin")
        val r = route(
            """{"action":"put","host":"web","local_path":"/var/minis/workspace/ghost.bin","remote_path":"/r"}""",
            pathResolver = { p -> if (p.endsWith("ghost.bin")) ghost else null },
        )
        assertFalse(r.success)
        assertTrue(r.output.contains("not a readable file"))
    }

    // ------------------------------------------------------------ failures

    @Test
    fun failureMappingIsStable() {
        fun codeFor(f: SshFailure): ToolErrorCode? {
            fake.failure = f
            val r = route("""{"action":"test","host":"web"}""")
            assertFalse(r.success)
            return r.errorCode
        }
        assertEquals(ToolErrorCode.NOT_FOUND, codeFor(SshFailure.UnknownHost("web")))
        assertEquals(ToolErrorCode.AUTH_REQUIRED, codeFor(SshFailure.Auth("u@h:22", "Auth fail")))
        assertEquals(ToolErrorCode.PERMISSION_DENIED, codeFor(SshFailure.HostKeyMismatch("u@h:22", "SHA256:a", "SHA256:b")))
        assertEquals(ToolErrorCode.NETWORK_ERROR, codeFor(SshFailure.Network("u@h:22", "refused")))
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, codeFor(SshFailure.Config("bad creds")))
        assertEquals(ToolErrorCode.EXECUTION_FAILED, codeFor(SshFailure.Protocol("channel broke")))
        fake.failure = SshFailure.Timeout("exec", 60_000)
        val t = route("""{"action":"test","host":"web"}""")
        assertEquals(ToolErrorCode.TIMEOUT, t.errorCode)
        assertTrue(t.timedOut)
    }

    @Test
    fun genericExceptionBecomesExecutionFailed() {
        fake.genericError = RuntimeException("boom")
        val r = route("""{"action":"exec","host":"web","command":"x"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.EXECUTION_FAILED, r.errorCode)
        assertTrue(r.output.contains("RuntimeException"))
        assertTrue(r.output.contains("boom"))
    }

    // ------------------------------------------------------------ misc

    @Test
    fun commandWrapQuoting() {
        assertEquals("ls", SshCommandWrap.wrapCwd(null, "ls"))
        assertEquals("ls", SshCommandWrap.wrapCwd("   ", "ls"))
        assertEquals("cd '/srv/app' && make", SshCommandWrap.wrapCwd("/srv/app", "make"))
        assertEquals("cd '/it'\\''s' && ls", SshCommandWrap.wrapCwd("/it's", "ls"))
    }

    @Test
    fun definitionShape() {
        val d = SshTool.definition()
        assertEquals("ssh_exec", d.name)
        assertEquals(listOf("action"), d.required)
        val actionParam = d.parameters["action"]
        assertNotNull(actionParam)
        val enums = actionParam!!.enumValues!!
        for (a in listOf("list_hosts", "add_host", "remove_host", "forget_host_key", "test", "exec", "ls", "get", "put")) {
            assertTrue("missing action $a", a in enums)
        }
    }
}
