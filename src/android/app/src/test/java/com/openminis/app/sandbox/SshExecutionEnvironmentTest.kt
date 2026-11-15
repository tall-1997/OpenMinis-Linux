package com.openminis.app.sandbox

import com.openminis.app.ssh.SshBackend
import com.openminis.app.ssh.SshConfigStore
import com.openminis.app.ssh.SshExecResult
import com.openminis.app.ssh.SshKnownHosts
import com.openminis.app.ssh.SshLsEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * review P2-15: shellExportPrefix must keep POSIX-name-valid keys and drop
 * invalid ones. A poisoned key makes the whole `export ...` line a remote
 * shell syntax error, which breaks the ENTIRE command line — not one
 * variable — so such keys are dropped with a log line, never silently kept
 * (and never thrown: the caller is environment negotiation).
 */
class SshExecutionEnvironmentTest {

    /** Backend that must never be touched: shellExportPrefix never uses it. */
    private val untouchedBackend = object : SshBackend {
        override val store: SshConfigStore get() = throw IllegalStateException("not used in this test")
        override val knownHosts: SshKnownHosts get() = throw IllegalStateException("not used in this test")
        override fun test(hostRef: String): String = throw IllegalStateException("not used")
        override fun exec(hostRef: String, command: String, timeoutMs: Long, cwd: String?): SshExecResult =
            throw IllegalStateException("not used")
        override fun ls(hostRef: String, path: String): List<SshLsEntry> = throw IllegalStateException("not used")
        override fun get(hostRef: String, remotePath: String, localFile: File): Long = throw IllegalStateException("not used")
        override fun put(hostRef: String, localFile: File, remotePath: String): Long = throw IllegalStateException("not used")
        override fun forgetHostKey(hostRef: String) = throw IllegalStateException("not used")
        override fun removeHost(hostRef: String): Boolean = throw IllegalStateException("not used")
        override fun close() = throw IllegalStateException("not used")
    }

    private fun prefix(env: Map<String, String>): String =
        SshExecutionEnvironment(untouchedBackend, "host-ref").shellExportPrefix(env)

    @Test
    fun `valid keys produce a standard export prefix`() {
        // Values are always single-quoted (the original behavior): the
        // assignments prefix is `K='V'` joined by spaces.
        assertEquals("export A='1' B='v2'; ", prefix(mapOf("A" to "1", "B" to "v2")))
    }

    @Test
    fun `quote escaping is preserved`() {
        assertEquals("export K='it'\\''s'; ", prefix(mapOf("K" to "it's")))
    }

    @Test
    fun `underscore-leading names stay valid`() {
        // [A-Za-z_] at the front is legal for a shell name.
        assertEquals("export _A1='2'; ", prefix(mapOf("_A1" to "2")))
    }

    @Test
    fun `invalid keys are dropped not poisoned into the prefix`() {
        // A leading digit, a hyphen and a space are all invalid shell names;
        // keeping them would turn the whole prefix into a syntax error.
        val r = prefix(mapOf("A" to "1", "1BAD" to "x", "A-B" to "x", " spaced " to "x", "C" to "3"))
        assertEquals("export A='1' C='3'; ", r)
    }

    @Test
    fun `all invalid keys yield an empty prefix not a syntax error`() {
        assertEquals("", prefix(mapOf("1BAD" to "x")))
        assertEquals("", prefix(mapOf("A-B" to "x", "K SPACE" to "x")))
    }

    @Test
    fun `empty env yields no prefix`() {
        assertEquals("", prefix(emptyMap()))
    }
}
