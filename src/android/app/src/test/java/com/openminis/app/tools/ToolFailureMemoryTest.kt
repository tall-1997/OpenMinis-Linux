package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-known-failure] The gate is the structural half of "stop repeating the same
 * mistake": the second identical call is refused by code, not by the model
 * remembering. These pin the four properties that make it safe to leave on —
 * argument sensitivity, the failure threshold, success clearing, and expiry.
 */
class ToolFailureMemoryTest {

    private lateinit var dir: File
    private lateinit var ledger: File

    private val now = 1_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tool-failures").toFile()
        ledger = File(dir, ToolFailureMemory.FILE_NAME)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun fingerprintIsStableAndArgumentSensitive() {
        assertEquals(
            ToolFailureMemory.fingerprint("shell_execute", "{\"command\":\"ls\"}"),
            ToolFailureMemory.fingerprint("shell_execute", "  {\"command\":\"ls\"}  "),
        )
        assertTrue(
            ToolFailureMemory.fingerprint("shell_execute", "{\"command\":\"ls\"}") !=
                ToolFailureMemory.fingerprint("shell_execute", "{\"command\":\"ls -la\"}"),
        )
    }

    @Test
    fun oneFailureDoesNotArmTheGate() {
        ToolFailureMemory.recordFailure(ledger, "shell_execute", "{}", "boom", now)
        assertNull(ToolFailureMemory.lookup(ledger, "shell_execute", "{}", now))
    }

    @Test
    fun twoIdenticalFailuresArmTheGate() {
        ToolFailureMemory.recordFailure(ledger, "shell_execute", "{}", "oom", now)
        val entry = ToolFailureMemory.recordFailure(ledger, "shell_execute", "{}", "oom", now + 1)
        assertEquals(2, entry.failures)
        assertNotNull(ToolFailureMemory.lookup(ledger, "shell_execute", "{}", now + 2))
    }

    @Test
    fun differentArgumentsAreNeverBlocked() {
        repeat(3) { ToolFailureMemory.recordFailure(ledger, "shell_execute", "{\"a\":1}", "boom", now) }
        assertNull(ToolFailureMemory.lookup(ledger, "shell_execute", "{\"a\":2}", now))
        assertNull(ToolFailureMemory.lookup(ledger, "other_tool", "{\"a\":1}", now))
    }

    @Test
    fun aSuccessClearsTheRecord() {
        repeat(2) { ToolFailureMemory.recordFailure(ledger, "shell_execute", "{}", "boom", now) }
        assertNotNull(ToolFailureMemory.lookup(ledger, "shell_execute", "{}", now))
        ToolFailureMemory.clear(ledger, "shell_execute", "{}")
        assertNull(ToolFailureMemory.lookup(ledger, "shell_execute", "{}", now))
    }

    @Test
    fun theRecordExpiresSoAFixedEnvironmentIsNotBlockedForever() {
        repeat(2) { ToolFailureMemory.recordFailure(ledger, "shell_execute", "{}", "boom", now) }
        assertNotNull(ToolFailureMemory.lookup(ledger, "shell_execute", "{}", now + 1))
        assertNull(
            ToolFailureMemory.lookup(
                ledger, "shell_execute", "{}", now + ToolFailureMemory.WINDOW_MS + 1,
            ),
        )
    }

    // ── near-argument clustering ──────────────────────────────────────────

    @Test
    fun `a near-identical call is refused after the shape failed twice`() {
        val original = """{"command":"cargo build --release"}"""
        repeat(2) { ToolFailureMemory.recordFailure(ledger, "shell_execute", original, "linker error", now) }
        val near = """{"command":"cargo build --release --offline"}"""
        val entry = ToolFailureMemory.lookup(ledger, "shell_execute", near, now)
        assertNotNull(entry)
        assertTrue(entry!!.nearMatch)
        val text = ToolFailureMemory.refusalText(entry, ToolFailureMemory.argsExcerpt(near))
        assertTrue(text.contains("very close"))
        assertTrue(text.contains("--offline"))
    }

    @Test
    fun `a different file in the same directory is not the same shape`() {
        repeat(2) {
            ToolFailureMemory.recordFailure(
                ledger, "file_read", """{"path":"/src/A.kt"}""", "not found", now,
            )
        }
        assertNull(ToolFailureMemory.lookup(ledger, "file_read", """{"path":"/src/B.kt"}""", now))
        // A path-prefix-only change is the same file.
        assertNotNull(ToolFailureMemory.lookup(ledger, "file_read", """{"path":"./src/A.kt"}""", now))
    }

    @Test
    fun `a success clears the near cluster too`() {
        val original = """{"command":"make test"}"""
        repeat(2) { ToolFailureMemory.recordFailure(ledger, "shell_execute", original, "boom", now) }
        val near = """{"command":"./make test"}"""
        assertNotNull(ToolFailureMemory.lookup(ledger, "shell_execute", near, now))
        ToolFailureMemory.clear(ledger, "shell_execute", near)
        assertNull(ToolFailureMemory.lookup(ledger, "shell_execute", near, now))
        assertNull(ToolFailureMemory.lookup(ledger, "shell_execute", original, now))
    }

    /**
     * [T-known-failure-payload] The payload is the fix. Two failed `file_edit`
     * calls on the same path used to cluster (their `old_string` was dropped
     * from the comparison), so the third call — the one carrying the corrected
     * `old_string` — was refused as "the small difference did not change the
     * failure cause" and the model could never repair the file.
     */
    @Test
    fun `a corrected edit payload is a new attempt and must run`() {
        val broken = """{"path":"/src/A.kt","old_string":"foo("}"""
        repeat(2) {
            ToolFailureMemory.recordFailure(ledger, "file_edit", broken, "Error: old_string not found", now)
        }
        // The fix lives in the payload: it must not be treated as the same call.
        assertNull(ToolFailureMemory.lookup(ledger, "file_edit", """{"path":"/src/A.kt","old_string":"foo()"}""", now))
        // Same broken payload, different spelling of the path: still the same rake.
        assertNotNull(ToolFailureMemory.lookup(ledger, "file_edit", """{"path":"./src/A.kt","old_string":"foo("}""", now))
    }

    /** `multi_edit.edits` / `mcp.arguments` are non-scalar, so they never reached the identity at all. */
    @Test
    fun `a changed structured payload is a new attempt`() {
        val one = """{"action":"call","server":"s","tool":"t","arguments":{"q":"one"}}"""
        repeat(2) { ToolFailureMemory.recordFailure(ledger, "mcp", one, "server error", now) }
        assertNull(
            ToolFailureMemory.lookup(
                ledger, "mcp", """{"action":"call","server":"s","tool":"t","arguments":{"q":"two"}}""", now,
            ),
        )
        assertNotNull(
            ToolFailureMemory.lookup(
                ledger, "mcp",
                """{"action":"call","server":"s","tool":"t","arguments":{"q":"one"},"tool_title":"retry"}""", now,
            ),
        )
    }

    @Test
    fun refusalExplainsWhatFailedAndHowToProceed() {
        repeat(2) { ToolFailureMemory.recordFailure(ledger, "shell_execute", "{}", "exit 137", now) }
        val entry = ToolFailureMemory.lookup(ledger, "shell_execute", "{}", now)!!
        val text = ToolFailureMemory.refusalText(entry)
        assertTrue(text.startsWith("[known-failure]"))
        assertTrue(text.contains("shell_execute"))
        assertTrue(text.contains("exit 137"))
        assertTrue(text.contains("different arguments"))
    }

    @Test
    fun refusalNamesTheArgumentsThatWereBlocked() {
        // [T-known-failure-readable] "these exact arguments" without naming
        // them left the model unable to change the right field.
        val args = """{"command":"cargo build --release","cwd":"/src"}"""
        repeat(2) { ToolFailureMemory.recordFailure(ledger, "shell_execute", args, "OOM", now) }
        val entry = ToolFailureMemory.lookup(ledger, "shell_execute", args, now)!!
        val text = ToolFailureMemory.refusalText(entry)
        assertTrue(text.contains("cargo build --release"))
        val excerpt = ToolFailureMemory.argsExcerpt(args)
        assertTrue(excerpt.contains("cargo build --release"))
        assertTrue(excerpt.contains("cwd=/src"))
    }

    @Test
    fun argsExcerptIsOneLineAndBounded() {
        val long = "x".repeat(500)
        val excerpt = ToolFailureMemory.argsExcerpt("""{"path":"/a/$long"}""")
        assertTrue(excerpt.length <= 141)
        assertTrue(!excerpt.contains("\n"))
        assertTrue(ToolFailureMemory.argsExcerpt("not json\nrest").startsWith("not json rest"))
    }

    @Test
    fun argsExcerptMasksCredentials() {
        val excerpt = ToolFailureMemory.argsExcerpt(
            """{"command":"curl -H 'Authorization: opaque-token-123' https://x?token=abcdef123&id=42"}""",
        )
        assertTrue(!excerpt.contains("opaque-token-123"))
        assertTrue(!excerpt.contains("abcdef123"))
        assertTrue(excerpt.contains("[redacted]"))
        assertTrue("URL tail survives masking", excerpt.contains("id=42"))
    }

    @Test
    fun argsExcerptNeverSplitsASurrogatePair() {
        val excerpt = ToolFailureMemory.argsExcerpt("""{"command":"${"x".repeat(139)}😀"}""")
        assertTrue(excerpt.isNotEmpty())
        assertTrue(!Character.isHighSurrogate(excerpt.last()))
    }

    @Test
    fun failedErrorTextIsMaskedBeforePersistingAndReplay() {
        val error = "HTTP 401: Authorization: opaque-token-123 at https://x?token=abcdef123"
        repeat(2) { ToolFailureMemory.recordFailure(ledger, "shell_execute", "{}", error, now) }
        assertFalse(ledger.readText().contains("opaque-token-123"))
        assertFalse(ledger.readText().contains("abcdef123"))
        val entry = ToolFailureMemory.lookup(ledger, "shell_execute", "{}", now)!!
        assertFalse(ToolFailureMemory.refusalText(entry).contains("opaque-token-123"))
    }

    @Test
    fun aCorruptLedgerIsTreatedAsEmpty() {
        ledger.writeText("{ not json")
        assertNull(ToolFailureMemory.lookup(ledger, "shell_execute", "{}", now))
        // And it can be repaired by recording again.
        repeat(2) { ToolFailureMemory.recordFailure(ledger, "shell_execute", "{}", "boom", now) }
        assertNotNull(ToolFailureMemory.lookup(ledger, "shell_execute", "{}", now))
    }

    @Test
    fun theLedgerIsBounded() {
        for (i in 0 until ToolFailureMemory.MAX_ENTRIES + 20) {
            ToolFailureMemory.recordFailure(ledger, "shell_execute", "{\"i\":$i}", "boom", now + i)
        }
        val root = org.json.JSONObject(ledger.readText())
        assertTrue(root.length() <= ToolFailureMemory.MAX_ENTRIES)
    }
}
