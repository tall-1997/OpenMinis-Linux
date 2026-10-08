package com.openminis.app.harness.text

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDiffGeneratorTest {

    @Test
    fun `produces standard unified diff header and changed lines`() {
        val diff = UnifiedDiffGenerator.diff("a\nb\nc\n", "a\nB\nc\n", "f.txt")
        assertNotNull(diff)
        assertTrue(diff!!.startsWith("--- a/f.txt\n+++ b/f.txt\n"))
        assertTrue(diff.contains("@@ -1,3 +1,3 @@"))
        assertTrue(diff.contains("\n-b\n"))
        assertTrue(diff.contains("\n+B\n"))
    }

    @Test
    fun `returns null when content unchanged`() {
        assertNull(UnifiedDiffGenerator.diff("x\ny\n", "x\ny\n", "f.txt"))
    }

    @Test
    fun `insertion produces addition line`() {
        val diff = UnifiedDiffGenerator.diff("a\nc\n", "a\nb\nc\n", "f.txt")!!
        assertTrue(diff.contains("+b"))
        assertTrue(diff.contains("@@"))
    }

    @Test
    fun `deletion produces removal line`() {
        val diff = UnifiedDiffGenerator.diff("a\nb\nc\n", "a\nc\n", "f.txt")!!
        assertTrue(diff.contains("-b"))
    }

    @Test
    fun `crlf and lf are treated as equivalent line content`() {
        val diff = UnifiedDiffGenerator.diff("a\r\nb\r\n", "a\nb\n", "f.txt")
        assertNull(diff)
    }
}