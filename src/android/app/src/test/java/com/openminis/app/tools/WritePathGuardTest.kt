package com.openminis.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WritePathGuardTest {

    @Test
    fun parseSplitsAndNormalizes() {
        val paths = WritePathGuard.parse("/var/minis/workspace/a/, /tmp/out; /var/minis/workspace/a")
        assertEquals(listOf("/var/minis/workspace/a", "/tmp/out"), paths)
    }

    @Test
    fun denyOutsidePrefix() {
        val prev = WritePathGuard.swap(listOf("/var/minis/workspace/proj"))
        try {
            assertNull(WritePathGuard.denyReason("/var/minis/workspace/proj/src/Main.kt"))
            val denied = WritePathGuard.denyReason("/var/minis/workspace/other/x")
            assertTrue(denied!!.contains("write_paths"))
        } finally {
            WritePathGuard.restore(prev)
        }
    }

    @Test
    fun swapNoneStillDeniesWrites() {
        val prev = WritePathGuard.swap(listOf("none"))
        try {
            val denied = WritePathGuard.denyReason("/tmp/out")
            assertTrue(denied!!.contains("none"))
        } finally {
            WritePathGuard.restore(prev)
        }
    }

    @Test
    fun unrestrictedWhenEmpty() {
        val prev = WritePathGuard.swap(emptyList())
        try {
            assertNull(WritePathGuard.denyReason("/etc/passwd"))
        } finally {
            WritePathGuard.restore(prev)
        }
    }

    @Test
    fun wrapShellExportsAllowList() {
        val prev = WritePathGuard.swap(listOf("/tmp/out"))
        try {
            val wrapped = WritePathGuard.wrapShellCommand("echo hi")
            assertTrue(wrapped.contains("MINIS_WRITE_PATHS='/tmp/out'"))
            assertTrue(wrapped.contains("echo hi"))
        } finally {
            WritePathGuard.restore(prev)
        }
        assertEquals("echo hi", WritePathGuard.wrapShellCommand("echo hi"))
    }

    @Test
    fun withPathsSurvivesDispatcherHop() = runBlocking {
        WritePathGuard.withPaths(listOf("/tmp/out")) {
            withContext(Dispatchers.Default) {
                assertNull(WritePathGuard.denyReason("/tmp/out/a.txt"))
                assertTrue(WritePathGuard.denyReason("/etc/passwd")!!.contains("write_paths"))
                val wrapped = WritePathGuard.wrapShellCommand("true")
                assertTrue(wrapped.contains("MINIS_WRITE_PATHS='/tmp/out'"))
            }
        }
        assertNull(WritePathGuard.denyReason("/etc/passwd"))
    }
}
