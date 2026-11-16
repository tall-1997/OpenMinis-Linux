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

    // ── drop-log sanitization (review P3: log-injection surface) ──

    private fun sanitized(key: String): String =
        SshExecutionEnvironment(untouchedBackend, "host-ref").sanitizeEnvKeyForLog(key)

    @Test
    fun `sanitized keys keep their printable bytes`() {
        // Rejected shapes the log will actually render (leading digit, hyphen,
        // space) must stay verbatim: stripping only removes control characters.
        assertEquals("1BAD", sanitized("1BAD"))
        assertEquals("A-B", sanitized("A-B"))
        assertEquals(" spaced ", sanitized(" spaced "))
    }

    @Test
    fun `control characters are stripped from logged keys`() {
        // A newline would forge an extra log line; CR could rewrite one.
        assertEquals("BADKEYX", sanitized("BAD\nKEY\rX"))
        // DEL (0x7F) is stripped too; everything below 0x20 (tab, ESC, NUL…).
        assertEquals("AB", sanitized("A\u007FB"))
        assertEquals("TABESC", sanitized("TA\tB\u001bESC"))
        // Printable non-ASCII is caller context, not injection surface: kept.
        assertEquals("caf\u00e9", sanitized("caf\u00e9"))
    }

    @Test
    fun `logged keys are capped at 64 characters`() {
        val longKey = "k".repeat(200)
        assertEquals(64, sanitized(longKey).length)
        assertEquals("k".repeat(64), sanitized(longKey))
        // Control characters are stripped BEFORE the cap: a 70-char key with
        // 10 control chars fits inside the cap afterwards.
        assertEquals("x".repeat(60), sanitized("x".repeat(60) + "\n".repeat(10)))
    }

    @Test
    fun `newline keys are still dropped from the prefix unchanged`() {
        // The sanitization is a LOG-ONLY transform: the drop decision itself
        // keeps matching the raw key, and the export prefix stays empty.
        assertEquals("", prefix(mapOf("A\nB" to "x")))
        assertEquals("export A='1'; ", prefix(mapOf("A" to "1", "A\nB" to "x")))
    }
}
