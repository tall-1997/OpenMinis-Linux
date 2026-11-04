package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertEquals
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
