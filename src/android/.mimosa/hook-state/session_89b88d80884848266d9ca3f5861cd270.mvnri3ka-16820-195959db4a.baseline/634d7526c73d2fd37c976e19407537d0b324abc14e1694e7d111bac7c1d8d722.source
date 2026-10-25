package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReplacersTest {

    @Test
    fun exactMatchSucceedsWithSingleMatch() {
        val content = """fun main() {
    println("hello")
}"""
        val result = TextReplacers.replace(
            content = content,
            oldPattern = "println(\"hello\")",
            newText = "println(\"world\")"
        )
        assertTrue("Expected success", result is TextReplacers.Result.Success)
        val success = result as TextReplacers.Result.Success
        assertTrue(success.newContent.contains("println(\"world\")"))
        assertEquals(1, success.count)
    }

    @Test
    fun exactMatchSucceedsWithReplaceAll() {
        val content = """println("a")
println("b")
println("a")"""
        val result = TextReplacers.replace(
            content = content,
            oldPattern = "\"a\"",
            newText = "\"replacement\"",
            replaceAll = true
        )
        assertTrue("Expected success", result is TextReplacers.Result.Success)
        val success = result as TextReplacers.Result.Success
        assertEquals("2 replacements expected", 2, success.count)
        assertTrue(success.newContent.contains("\"replacement\""))
    }

    @Test
    fun exactMatchAmbiguityRejection() {
        val content = """line A
line B
line A"""
        val result = TextReplacers.replace(
            content = content,
            oldPattern = "line A",
            newText = "line C"
        )
        assertTrue("Expected failure", result is TextReplacers.Result.Failure)
        val failure = result as TextReplacers.Result.Failure
        assertTrue("Should mention multiple occurrences", failure.message.contains("2 matching locations"))
    }

    @Test
    fun exactMatchNotFound() {
        val content = "hello world"
        val result = TextReplacers.replace(
            content = content,
            oldPattern = "not found",
            newText = "replacement"
        )
        assertTrue("Expected failure", result is TextReplacers.Result.Failure)
    }

    @Test
    fun whitespaceFallbackMatch() {
        val content = """fun greet() {
  println("hello")
}"""
        val result = TextReplacers.replace(
            content = content,
            oldPattern = """  println("hello")   """,
            newText = """  println("world")"""
        )
        // oldPattern 是单行模式（不含换行），内容行 trim 后相等 →
        // line-trimmed 层级匹配成功。
        assertTrue("Expected success via whitespace fallback", result is TextReplacers.Result.Success)
        val success = result as TextReplacers.Result.Success
        assertEquals(1, success.count)
    }

    @Test
    fun lineTrimmedFallbackMatch() {
        val content = """fun add(a: Int, b: Int): Int {
    return a + b
}"""
        val result = TextReplacers.replace(
            content = content,
            oldPattern = """fun add(a: Int, b: Int): Int {
    return a + b
}""",
            newText = """fun add(a: Int, b: Int): Int {
    return a + b + 1
}"""
        )
        // 精确匹配直接命中。
        assertTrue("Expected success", result is TextReplacers.Result.Success)
    }

    @Test
    fun replaceAllWithMultipleMatches() {
        val content = """vaL xa = 1
vaL y = 2
vaL xa = 3"""
        val result = TextReplacers.replace(
            content = content,
            oldPattern = "xa",
            newText = "XZ",
            replaceAll = true
        )
        assertTrue("Expected success", result is TextReplacers.Result.Success)
        val success = result as TextReplacers.Result.Success
        assertEquals(2, success.count)
        val lines = success.newContent.lines()
        assertTrue(lines.any { it == "vaL XZ = 1" || it == "vaL XZ = 3" })
    }

    @Test
    fun emptyOldPatternFails() {
        val content = "some content"
        try {
            TextReplacers.replace(content, "", "replacement")
            assertTrue("Should have thrown", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("empty") ?: false)
        }
    }

    @Test
    fun deleteWithEmptyReplacement() {
        val content = "Hello world"
        val result = TextReplacers.replace(
            content = content,
            oldPattern = " world",
            newText = ""
        )
        assertTrue("Expected success", result is TextReplacers.Result.Success)
        val success = result as TextReplacers.Result.Success
        assertEquals("Hello", success.newContent)
        assertEquals(1, success.count)
    }

    @Test
    fun blockAnchorFallbackUnique() {
        // 块锚替换：锚线之间内容被整体替换（保留两侧锚线）。
        val content = """Some code here
###
val important = true
###
More code"""
        val result = TextReplacers.replace(
            content = content,
            oldPattern = "###\nval important = true",
            newText = "###\nval important = false"
        )
        assertTrue("Expected success", result is TextReplacers.Result.Success)
        val success = result as TextReplacers.Result.Success
        assertTrue(success.newContent.contains("###\nval important = false"))
    }

    @Test
    fun replacesAllOccurrencesCorrectly() {
        val content = """first line
second line
third line"""
        val result = TextReplacers.replace(
            content = content,
            oldPattern = "line",
            newText = "row",
            replaceAll = true
        )
        assertTrue("Expected success", result is TextReplacers.Result.Success)
        val success = result as TextReplacers.Result.Success
        assertEquals(3, success.count)
        val newLines = success.newContent.lines()
        assertEquals("first row", newLines[0])
        assertEquals("second row", newLines[1])
        assertEquals("third row", newLines[2])
    }

    @Test
    fun singleMatchPreservesContent() {
        val content = """class MyClass {
    fun method() {
        return 42
    }
}"""
        val result = TextReplacers.replace(
            content = content,
            oldPattern = "return 42",
            newText = "return 24"
        )
        assertTrue("Expected success", result is TextReplacers.Result.Success)
        val success = result as TextReplacers.Result.Success
        assertEquals(content.lines().size, success.newContent.lines().size)
        assertTrue(success.newContent.contains("return 24"))
    }

    @Test
    fun multipleDifferentOccurrencesAllReplaced() {
        val content = """val x = 1
val y = 2
val z = 1"""
        val result = TextReplacers.replace(
            content = content,
            oldPattern = "val",
            newText = "var"
        )
        assertTrue("Expected failure (multiple non-unique matches)", result is TextReplacers.Result.Failure)
    }

    // ── [T-crlf-cross] CRLF files edited with LF patterns ──

    @Test
    fun crlfFileMatchesMultiLineLfPatternAtLineTrimmedLevel() {
        // Windows files store CRLF; models emit old_string with LF. The exact
        // tier cannot match, and the line-trimmed tier must catch it instead
        // of reporting not-found (splitLines strips the CR, matchesBlock
        // compares trimmed text). Replacing within the matched lines must
        // leave the untouched lines' CRLF endings intact — the replacement
        // range stops before the line terminator.
        val content = "first\r\nsecond\r\nthird\r\n"
        val result = TextReplacers.replace(content, "first\nsecond", "REPLACED")
        assertTrue("CRLF content must match an LF pattern", result is TextReplacers.Result.Success)
        val success = result as TextReplacers.Result.Success
        assertEquals(1, success.count)
        assertEquals("REPLACED\r\nthird\r\n", success.newContent)
    }

    @Test
    fun crlfFileMatchesSingleLineLfNeedleAtExactLevel() {
        // A single-line LF needle is a plain substring of the CRLF file, so
        // the exact tier still fires and the terminator is untouched.
        val content = "first\r\nsecond\r\nthird\r\n"
        val result = TextReplacers.replace(content, "second", "REPLACED")
        assertTrue(result is TextReplacers.Result.Success)
        assertEquals("first\r\nREPLACED\r\nthird\r\n", (result as TextReplacers.Result.Success).newContent)
    }
}