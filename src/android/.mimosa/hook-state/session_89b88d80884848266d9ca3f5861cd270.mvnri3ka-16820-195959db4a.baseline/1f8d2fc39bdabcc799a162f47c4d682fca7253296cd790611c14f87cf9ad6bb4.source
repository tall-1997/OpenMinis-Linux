package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobToolTest {
    @Test
    fun starDoesNotCrossSlash() {
        val r = GlobTool.globToRegex("src/*.kt")
        assertTrue(r.matches("src/Foo.kt"))
        assertFalse(r.matches("src/nested/Foo.kt"))
    }

    @Test
    fun doubleStarCrossesDirs() {
        val r = GlobTool.globToRegex("**/*.kt")
        assertTrue(r.matches("Foo.kt"))
        assertTrue(r.matches("src/Foo.kt"))
        assertTrue(r.matches("a/b/c/Foo.kt"))
        assertFalse(r.matches("Foo.java"))
    }

    @Test
    fun questionMarkOneSegmentChar() {
        val r = GlobTool.globToRegex("a?c")
        assertTrue(r.matches("abc"))
        assertFalse(r.matches("ac"))
        assertFalse(r.matches("a/c"))
    }
}
