package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pure-logic tests for the in-app gateway log viewer. [T-android-a2a-login-capture] */
class A2aGatewayLogViewTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun logFile(content: String): File =
        tmp.newFile("gateway.log").apply { writeText(content) }

    @Test
    fun `missing file yields no lines`() {
        assertEquals(emptyList<String>(), tailLogLines(File(tmp.root, "absent.log"), 10))
    }

    @Test
    fun `takes the last N non-blank lines`() {
        val f = logFile((1..10).joinToString("\n") { "line$it" } + "\n\n\n")
        assertEquals(listOf("line8", "line9", "line10"), tailLogLines(f, 3))
    }

    @Test
    fun `byte window drops the torn first line`() {
        val f = logFile((1..50).joinToString("\n") { "line-$it-xxxxxxxx" })
        val lines = tailLogLines(f, 100, maxBytes = 60)
        assertTrue(lines.isNotEmpty())
        // No torn fragment leaks through.
        assertTrue(lines.all { it.matches(Regex("line-\\d+-xxxxxxxx")) })
    }

    @Test
    fun `login relevant filter selects login security and session tags`() {
        assertTrue(loginRelevantLine("[Login] 发起登录失败"))
        assertTrue(loginRelevantLine("[Security] 面板会话无效"))
        assertTrue(loginRelevantLine("[Session] refresh"))
        assertFalse(loginRelevantLine("[Accounts] usage refreshed"))
        assertFalse(loginRelevantLine("plain text"))
    }
}
