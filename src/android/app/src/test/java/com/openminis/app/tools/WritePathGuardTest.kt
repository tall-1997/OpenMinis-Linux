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

    // ——— [T-p2-writepathguard-dotdot] 中段 .. 消解 ———

    @Test
    fun midPathDotDotEscapeIsDenied() {
        val prev = WritePathGuard.swap(listOf("/ws/reports"))
        try {
            // 旧实现字符串前缀匹配会放行：/ws/reports/../../shared/x 以 /ws/reports 开头
            val denied = WritePathGuard.denyReason("/ws/reports/../../shared/x")
            assertTrue("中段 .. 消解后越界必须拒", denied!!.contains("write_paths"))
            assertNull(WritePathGuard.denyReason("/ws/reports/q3/final.md"))
        } finally {
            WritePathGuard.restore(prev)
        }
    }

    @Test
    fun dotDotThatEscapesRootIsRejectedExplicitly() {
        val prev = WritePathGuard.swap(listOf("/ws/reports"))
        try {
            val denied = WritePathGuard.denyReason("/../etc/passwd")
            assertTrue(denied!!.contains("escapes the root"))
        } finally {
            WritePathGuard.restore(prev)
        }
    }

    // ——— [T-p2-writelease-relative-scope] 相对声明按工作区根解析 ———

    @Test
    fun relativeScopesResolveAgainstWorkspaceRoot() {
        val paths = WritePathGuard.parse("workspace/reports, .")
        assertEquals(
            listOf("/var/minis/workspace/workspace/reports", "/var/minis/workspace"),
            paths,
        )
    }
}
