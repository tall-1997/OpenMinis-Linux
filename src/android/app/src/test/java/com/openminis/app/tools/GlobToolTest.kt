package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

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

    // -- [T-glob-coverage-note] cap coverage reporting -----------------------

    private fun tempDir(): Path = Files.createTempDirectory("glob-test")

    @Test
    fun underTheCapNoCoverageNote() {
        val dir = tempDir()
        repeat(3) { Files.createFile(dir.resolve("f$it.txt")) }
        val report = GlobTool.walkAndReport(dir.toFile(), "**/*.txt", "/tmp/ws")
        assertTrue(report.startsWith("3 files for **/*.txt under /tmp/ws\n"))
        assertFalse(report.contains("match cap reached"))
        assertFalse(report.contains("walk budget"))
    }

    @Test
    fun matchCapSurfacesTheRealTotal() {
        val dir = tempDir()
        // MAX_HITS + 5 matches: the walk keeps counting past the collection cap.
        repeat(GlobTool.MAX_HITS + 5) { Files.createFile(dir.resolve("f$it.txt")) }
        val report = GlobTool.walkAndReport(dir.toFile(), "**/*.txt", "/tmp/ws")
        assertTrue(
            report.contains(
                "(match cap reached — showing first ${GlobTool.MAX_HITS} of ${GlobTool.MAX_HITS + 5} " +
                    "matches; sort/filter or narrow the query for the rest)",
            ),
        )
        // Exactly the capped number of path lines follows the header.
        val lines = report.trim().lines()
        assertEquals(GlobTool.MAX_HITS + 1, lines.size)
    }

    @Test
    fun walkBudgetStopIsReportedAsPartialCoverage() {
        val dir = tempDir()
        // More entries than the walk budget: the walk stops early, so even the
        // match total is a lower bound and the result must say so.
        repeat(GlobTool.MAX_WALK + 12) { Files.createFile(dir.resolve("f$it.txt")) }
        val report = GlobTool.walkAndReport(dir.toFile(), "**/*.txt", "/tmp/ws")
        assertTrue(report.contains("(walk budget of ${GlobTool.MAX_WALK} entries reached"))
        assertTrue(report.contains("counts and coverage are partial"))
        assertTrue(report.contains("(match cap reached"))
    }
}
