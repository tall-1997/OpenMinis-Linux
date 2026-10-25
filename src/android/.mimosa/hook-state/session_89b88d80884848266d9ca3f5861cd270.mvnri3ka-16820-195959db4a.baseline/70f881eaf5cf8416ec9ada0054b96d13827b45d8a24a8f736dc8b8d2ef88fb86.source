package com.openminis.app.sandbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device tests for ExecutionCoordinator.
 * Tests session mount switching, command serialization, and lifecycle.
 *
 * Run on real device: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ExecutionCoordinatorInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resetKernel()
        ExecutionCoordinator.init(context)

        if (canBoot()) {
            PRootKernel.boot(context)
        }
    }

    @After
    fun tearDown() {
        PRootKernel.clearBindMounts()
        PRootKernel.customEnvironment.clear()
        cleanupSessionDirs()
    }

    // ==================== Session mounting ====================

    @Test
    fun executeCreatesSessionOwnedShell() = runBlocking {
        skipIfNoBoot()

        val sessionId = "session-mount-test"
        ExecutionCoordinator.execute(sessionId, "echo hello")

        assertTrue(ExecutionCoordinator.hasLiveShell(sessionId))
    }

    @Test
    fun executeKeepsIndependentSessionShells() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute("session-A", "echo a")
        assertTrue(ExecutionCoordinator.hasLiveShell("session-A"))

        ExecutionCoordinator.execute("session-B", "echo b")
        assertTrue(ExecutionCoordinator.hasLiveShell("session-B"))
    }

    @Test
    fun executeSameSessionDoesNotRemount() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute("session-X", "echo first")

        // Get current bind mount count
        val mountCount = PRootKernel.bindMounts.size

        ExecutionCoordinator.execute("session-X", "echo second")

        // Mounts should remain the same (not cleared and re-added)
        assertEquals(mountCount, PRootKernel.bindMounts.size)
        assertTrue(ExecutionCoordinator.hasLiveShell("session-X"))
    }

    @Test
    fun executeCreatesSessionBindMounts() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute("session-mounts", "echo test")

        assertTrue(PRootKernel.bindMounts.containsKey("/var/minis/attachments"))
        assertTrue(PRootKernel.bindMounts.containsKey("/var/minis/offloads"))
        assertTrue(PRootKernel.bindMounts.containsKey("/var/minis/workspace"))
        assertTrue(PRootKernel.bindMounts.containsKey("/var/minis/browser"))
        assertTrue(PRootKernel.bindMounts.containsKey("/var/minis/memory"))
        assertTrue(PRootKernel.bindMounts.containsKey("/var/minis/skills"))
        assertTrue(PRootKernel.bindMounts.containsKey("/var/minis/shared"))
    }

    @Test
    fun executeCreatesHostDirectories() = runBlocking {
        skipIfNoBoot()

        val sessionId = "session-dirs-test"
        ExecutionCoordinator.execute(sessionId, "echo test")

        // Verify session host directories exist
        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
        for (subdir in listOf("attachments", "offloads", "workspace", "browser", "memory")) {
            assertTrue("$subdir should exist", File(sessionBase, subdir).isDirectory)
        }

        // Verify global host directories exist
        val globalBase = File(context.filesDir, "minis-global")
        for (subdir in listOf("skills", "shared")) {
            assertTrue("$subdir should exist", File(globalBase, subdir).isDirectory)
        }
    }

    @Test
    fun sessionBindMountsPointToCorrectHostDirs() = runBlocking {
        skipIfNoBoot()

        val sessionId = "session-verify-paths"
        ExecutionCoordinator.execute(sessionId, "echo test")

        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
        assertEquals(
            File(sessionBase, "workspace").absolutePath,
            PRootKernel.bindMounts["/var/minis/workspace"]
        )

        assertEquals(
            File(sessionBase, "memory").absolutePath,
            PRootKernel.bindMounts["/var/minis/memory"]
        )
        val globalBase = File(context.filesDir, "minis-global")
        assertEquals(
            File(globalBase, "skills").absolutePath,
            PRootKernel.bindMounts["/var/minis/skills"]
        )
    }

    // ==================== Command execution ====================

    @Test
    fun executeReturnsCorrectOutput() = runBlocking {
        skipIfNoBoot()

        val result = ExecutionCoordinator.execute("session-output", "echo hello world")
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("hello world"))
    }

    @Test
    fun executeAppendsExitCodeOnFailure() = runBlocking {
        skipIfNoBoot()

        val result = ExecutionCoordinator.execute("session-fail", "exit 1")
        assertEquals(1, result.exitCode)
        assertTrue("Should append exit code", result.output.contains("(exit code: 1)"))
    }

    @Test
    fun executeDoesNotAppendExitCodeOnSuccess() = runBlocking {
        skipIfNoBoot()

        val result = ExecutionCoordinator.execute("session-ok", "echo ok")
        assertEquals(0, result.exitCode)
        assertFalse("Should not contain exit code marker", result.output.contains("(exit code:"))
    }

    @Test
    fun executeDoesNotAppendExitCodeOnTimeout() = runBlocking {
        skipIfNoBoot()

        val result = ExecutionCoordinator.execute("session-timeout", "sleep 60", timeout = 2_000)
        assertEquals(124, result.exitCode)
        assertFalse("Should not double-append exit code", result.output.contains("(exit code: 124)"))
    }

    @Test
    fun executeWithLineCallback() = runBlocking {
        skipIfNoBoot()

        val lines = mutableListOf<String>()
        ExecutionCoordinator.execute(
            "session-callback",
            "echo one; echo two",
            lineCallback = { lines.add(it) }
        )

        assertTrue(lines.size >= 2)
        assertTrue(lines.contains("one"))
        assertTrue(lines.contains("two"))
    }

    // ==================== Serialization ====================

    @Test
    fun executeSerializesConcurrentCalls() = runBlocking {
        skipIfNoBoot()

        // Launch two commands concurrently — they should serialize
        val startTime = System.currentTimeMillis()

        val deferred1 = async {
            ExecutionCoordinator.execute("session-serial", "sleep 1; echo first")
        }
        val deferred2 = async {
            // Small delay to ensure ordering
            delay(50)
            ExecutionCoordinator.execute("session-serial", "echo second")
        }

        val result1 = deferred1.await()
        val result2 = deferred2.await()

        // Both should succeed
        assertEquals(0, result1.exitCode)
        assertEquals(0, result2.exitCode)

        // Total time should be > 1s (serialized, not parallel)
        val elapsed = System.currentTimeMillis() - startTime
        assertTrue("Commands should be serialized (elapsed: ${elapsed}ms)", elapsed >= 900)
    }

    // ==================== sessionDidTerminate ====================

    @Test
    fun sessionDidTerminateClearsMountsForMatchingSession() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute("session-terminate", "echo test")
        assertTrue(ExecutionCoordinator.hasLiveShell("session-terminate"))
        assertTrue(PRootKernel.bindMounts.isNotEmpty())

        ExecutionCoordinator.sessionDidTerminate("session-terminate")

        assertFalse(ExecutionCoordinator.hasLiveShell("session-terminate"))
    }

    @Test
    fun sessionDidTerminateIgnoresMismatchedSession() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute("session-keep", "echo test")
        val mountsBefore = PRootKernel.bindMounts.size

        ExecutionCoordinator.sessionDidTerminate("session-other")

        assertTrue(ExecutionCoordinator.hasLiveShell("session-keep"))
        assertEquals(mountsBefore, PRootKernel.bindMounts.size)
    }

    @Test
    fun sessionDidTerminateIsNoOpWhenNoSession() {
        ExecutionCoordinator.sessionDidTerminate("any-session")
        assertFalse(ExecutionCoordinator.hasLiveShell("any-session"))
    }

    // ==================== stopCurrentCommand ====================

    @Test
    fun stopCurrentCommandIsNoOpWhenIdle() {
        ExecutionCoordinator.stopCurrentCommand() // Should not throw
    }

    // ==================== Cross-session file isolation ====================

    @Test
    fun differentSessionsHaveIsolatedWorkspaces() = runBlocking {
        skipIfNoBoot()

        // Write file in session A's workspace
        ExecutionCoordinator.execute(
            "session-iso-A",
            "echo 'from A' > /var/minis/workspace/test.txt"
        )

        // Check it exists in session A
        val resultA = ExecutionCoordinator.execute(
            "session-iso-A",
            "cat /var/minis/workspace/test.txt"
        )
        assertTrue(resultA.output.contains("from A"))

        // Switch to session B — workspace should be empty
        val resultB = ExecutionCoordinator.execute(
            "session-iso-B",
            "ls /var/minis/workspace/"
        )
        assertFalse("Session B should not see session A's file", resultB.output.contains("test.txt"))

        // Switch back to A — file should still be there
        val resultA2 = ExecutionCoordinator.execute(
            "session-iso-A",
            "cat /var/minis/workspace/test.txt"
        )
        assertTrue(resultA2.output.contains("from A"))
    }

    @Test
    fun sessionMemoryIsIsolatedSkillsAreShared() = runBlocking {
        skipIfNoBoot()

        ExecutionCoordinator.execute(
            "session-mem-A",
            "echo 'from A' > /var/minis/memory/note.txt && echo 'skill' > /var/minis/skills/shared-tool.txt"
        )
        val memB = ExecutionCoordinator.execute(
            "session-mem-B",
            "cat /var/minis/memory/note.txt"
        )
        assertFalse("Session B must not see A's memory", memB.output.contains("from A"))
        val skillB = ExecutionCoordinator.execute(
            "session-mem-B",
            "cat /var/minis/skills/shared-tool.txt"
        )
        assertTrue("Skills stay shared", skillB.output.contains("skill"))
    }

    // ==================== Helpers ====================

    private fun canBoot(): Boolean {
        return try {
            context.assets.open("ubuntu-base.tar").use { }
            context.assets.open("proot-aarch64").use { }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun skipIfNoBoot() {
        assumeTrue("PRoot rootfs assets are not available on this device", PRootKernel.isBooted)
    }

    private fun resetKernel() {
        try {
            val field = PRootKernel::class.java.getDeclaredField("isBooted")
            field.isAccessible = true
            field.set(PRootKernel, false)
        } catch (_: Exception) { }
        PRootKernel.clearBindMounts()
        PRootKernel.customEnvironment.clear()

        // Tear down every per-session persistent shell before each test.
        ExecutionCoordinator.stopCurrentCommand()
    }

    private fun cleanupSessionDirs() {
        listOf("session-mount-test", "session-A", "session-B", "session-X",
            "session-mounts", "session-dirs-test", "session-verify-paths",
            "session-output", "session-fail", "session-ok", "session-timeout",
            "session-callback", "session-serial", "session-terminate",
            "session-keep", "session-iso-A", "session-iso-B",
            "session-global-A", "session-global-B").forEach { id ->
            File(context.filesDir, "minis-sessions/$id").deleteRecursively()
        }
        File(context.filesDir, "minis-global").deleteRecursively()
    }
}
