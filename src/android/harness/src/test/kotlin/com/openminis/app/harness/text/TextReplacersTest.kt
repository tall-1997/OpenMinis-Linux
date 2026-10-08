package com.openminis.app.harness.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReplacersTest {

    @Test
    fun `exact replacement hits and reports strategy`() {
        val content = "fun main() {\n    println(1)\n}\n"
        val result = TextReplacerEngine.replace(content, "    println(1)", "    println(2)")
        assertEquals("exact", result.strategy)
        assertEquals(1, result.replacements)
        assertEquals("fun main() {\n    println(2)\n}\n", result.updated)
    }

    @Test
    fun `exact multiple matches are rejected`() {
        val content = "a\nb\na\nb\n"
        val error = runCatching { TextReplacerEngine.replace(content, "a", "c") }.exceptionOrNull()
        assertTrue(error is TextReplacementException)
        assertTrue(error!!.message.orEmpty().contains("匹配 2 处"))
    }

    @Test
    fun `line trimmed reindents replacement to real file indentation`() {
        val content = "class A {\n        fun b() {\n            return 1\n        }\n}\n"
        val oldText = "    fun b() {\n        return 1\n    }"
        val newText = "    fun b() {\n        return 2\n    }"
        val result = TextReplacerEngine.replace(content, oldText, newText)
        assertEquals("line_trimmed", result.strategy)
        assertEquals(
            "class A {\n        fun b() {\n            return 2\n        }\n}\n",
            result.updated,
        )
    }

    @Test
    fun `block anchor tolerates differing middle lines`() {
        val content = "def f():\n    a = 1\n    b = 2\n    return a + b\n"
        val oldText = "def f():\n    a = 1\n    b = 999\n    return a + b"
        val newText = "def f():\n    a = 3\n    b = 4\n    return a + b"
        val result = TextReplacerEngine.replace(content, oldText, newText)
        assertEquals("block_anchor", result.strategy)
        assertEquals("def f():\n    a = 3\n    b = 4\n    return a + b\n", result.updated)
    }

    @Test
    fun `not found reports all attempted strategies`() {
        val error = runCatching {
            TextReplacerEngine.replace("hello\n", "goodbye", "hi")
        }.exceptionOrNull()
        assertTrue(error is TextReplacementException)
        assertTrue(error!!.message.orEmpty().contains("exact/line_trimmed/block_anchor"))
    }

    @Test
    fun `crlf files keep crlf line endings`() {
        val content = "a\r\nb\r\nc\r\n"
        val result = TextReplacerEngine.replace(content, "a\nb", "a\nB")
        assertEquals("a\r\nB\r\nc\r\n", result.updated)
    }

    @Test
    fun `trailing newline in old text is tolerated`() {
        val content = "line1\nline2\nline3\n"
        val result = TextReplacerEngine.replace(content, "line2\n", "LINE2\n")
        assertEquals("line1\nLINE2\nline3\n", result.updated)
    }

    @Test
    fun `line trimmed rejects ambiguous matches`() {
        val content = "  a\n  a\n"
        val error = runCatching {
            TextReplacerEngine.replace(content, "\ta", "\tb")
        }.exceptionOrNull()
        assertTrue(error is TextReplacementException)
        assertTrue(error!!.message.orEmpty().contains("匹配 2 处"))
    }
}