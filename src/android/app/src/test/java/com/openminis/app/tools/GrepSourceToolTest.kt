package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for the bounded streaming grep engine ([GrepStreamScan]) that
 * powers `grep_source` / `grep` (P2 2-1 / 2-2 / 2-4).
 *
 * The engine is deliberately Android-free so these tests run on the plain
 * JVM: `GrepSourceTool.execute` needs a Context + PRoot path resolution and is
 * covered by the dispatch layers instead; everything observable in the
 * model-visible report is asserted here against the engine + formatter.
 */
class GrepSourceToolTest {

    // ------------------------------------------------------------ helpers --

    private fun tempRoot(): File = Files.createTempDirectory("grep-stream-scan").toFile()

    private fun newFile(parent: File, name: String, content: String): File =
        File(parent, name).apply { writeText(content) }

    private fun newDir(parent: File, name: String): File =
        File(parent, name).apply { mkdirs() }

    private fun scan(root: File, options: GrepStreamScan.Options = GrepStreamScan.Options()) =
        GrepStreamScan.scan(root, { line -> line.contains("needle") }, options)

    // ------------------------------------------------- matching semantics --

    @Test
    fun `single file scan reports match with context window`() {
        val root = tempRoot()
        newFile(root, "a.txt", "line0\nneedle here\nline2\n")

        val stats = scan(root)

        assertEquals(1, stats.totalMatches)
        assertEquals(1, stats.hitFiles)
        assertEquals(1, stats.filesScanned)
        assertEquals(0, stats.filesSkippedIO)
        assertEquals(0, stats.filesSkippedLarge)
        assertFalse(stats.truncated)
        // Context line above the hit carries a space marker, the hit itself
        // the ">" marker — the pre-refactor body shape.
        assertTrue(stats.output.contains(" 1: line0\n"))
        assertTrue(stats.output.contains(">2: needle here\n"))
    }

    @Test
    fun `noise dirs are pruned but the start directory is always searched`() {
        // Root itself is literally named "build" — it must still be searched.
        val root = newDir(tempRoot(), "build")
        newFile(root, "hit.txt", "needle\n")
        val nestedBuild = newDir(root, "build")
        newFile(nestedBuild, "nested.txt", "needle\n")
        val gitDir = newDir(root, ".git")
        newFile(gitDir, "config", "needle\n")
        val srcDir = newDir(root, "src")
        newFile(srcDir, "kept.txt", "needle\n")

        val stats = scan(root)

        // Only the root file and src/ file survive the prune.
        assertEquals(2, stats.hitFiles)
        assertTrue(stats.output.contains("hit.txt"))
        assertTrue(stats.output.contains("kept.txt"))
        assertFalse(stats.output.contains("nested.txt"))
        assertFalse(stats.output.contains("config"))
    }

    @Test
    fun `zero byte files are skipped silently and oversized files are counted`() {
        val root = tempRoot()
        newFile(root, "empty.txt", "")
        File(root, "huge.txt").writeText(String(CharArray(GrepStreamScan.MAX_FILE_BYTES + 1) { 'x' }))
        newFile(root, "normal.txt", "needle\n")

        val stats = scan(root)

        assertEquals(1, stats.filesScanned)
        assertEquals(1, stats.filesSkippedLarge)
        assertEquals(0, stats.filesSkippedIO)
        val report = GrepStreamScan.formatReport("needle", "/w", stats, 50)
        assertTrue(report.contains("[skipped: 1 >512KB"))
        assertFalse(report.contains("unreadable"))
    }

    @Test
    fun `unreadable files are counted as skipped IO blind spots`() {
        val root = tempRoot()
        newFile(root, "locked.txt", "needle\n")
        newFile(root, "fine.txt", "nothing\n")

        val stats = scan(
            root,
            GrepStreamScan.Options(
                readLines = { f -> if (f.name == "locked.txt") null else f.readLines() },
            ),
        )

        assertEquals(1, stats.filesSkippedIO)
        assertEquals(0, stats.totalMatches)
        val report = GrepStreamScan.formatReport("needle", "/w", stats, 50)
        assertTrue(report.contains("(1 unreadable)"))
        assertTrue(report.contains("scanned 2 file(s)"))
    }

    // -------------------------------------------------------- walk safety --

    @Test
    fun `deep tree is walked without blowing the call stack`() {
        val root = tempRoot()
        var dir = root
        // Deep enough that a recursive visitor on a constrained stack would
        // struggle, shallow enough that every filesystem allows the path.
        repeat(80) { dir = newDir(dir, "d") }
        newFile(dir, "bottom.txt", "needle\n")

        val stats = scan(root)

        assertEquals(1, stats.totalMatches)
        assertEquals(1, stats.filesScanned)
        assertFalse(stats.stoppedByWalkGuard)
        assertFalse(stats.stoppedByTimeout)
    }

    @Test
    fun `walk depth guard trips and is reported instead of hanging`() {
        var dir = tempRoot()
        repeat(10) { dir = newDir(dir, "d") }
        newFile(dir, "bottom.txt", "needle\n")
        var top: File = dir
        repeat(10) { top = top.parentFile }

        val stats = scan(top, GrepStreamScan.Options(maxWalkDepth = 5))

        assertTrue(stats.stoppedByWalkGuard)
        assertEquals(0, stats.totalMatches)
        val report = GrepStreamScan.formatReport("needle", "/w", stats, 50)
        assertTrue(report.contains("(walk guard hit)"))
    }

    @Test
    fun `directory count guard trips and is reported`() {
        val root = tempRoot()
        repeat(10) { i -> newFile(newDir(root, "d$i"), "f.txt", "nothing\n") }

        val stats = scan(root, GrepStreamScan.Options(maxDirsVisited = 3))

        assertTrue(stats.stoppedByWalkGuard)
    }

    @Test
    fun `symlink cycle terminates instead of hanging the scan`() {
        val root = tempRoot()
        val dirA = newDir(root, "a")
        val dirB = newDir(root, "b")
        newFile(dirA, "a.txt", "needle\n")
        newFile(dirB, "b.txt", "needle\n")
        // a -> b -> a cycle: FileTreeWalk would spin on this forever.
        val created = try {
            Files.createSymbolicLink(Path.of(dirB.path, "backToA"), dirA.toPath())
            Files.createSymbolicLink(Path.of(dirA.path, "toB"), dirB.toPath())
            true
        } catch (_: Exception) {
            false
        }
        assumeTrue("filesystem does not support symlink creation — cycle test skipped", created)

        val stats = scan(root, GrepStreamScan.Options(deadlineMs = 5_000L))

        // Both real files matched exactly once, the cycle links never
        // duplicated them, and the deadline never had to rescue the walk.
        assertEquals(2, stats.hitFiles)
        assertEquals(2, stats.totalMatches)
        assertFalse("visited-set cycle breaker failed — deadline had to stop the walk", stats.stoppedByTimeout)
        assertFalse(stats.stoppedByWalkGuard)
    }

    // ----------------------------------------------------------- deadline --

    @Test
    fun `deadline trips inside the line loop and stops the whole scan`() {
        // review P2-9: a regex line with catastrophic backtracking can burn
        // minutes inside ONE line — the deadline must be checked per line,
        // not only between files. The matcher itself advances the fake clock,
        // simulating that cost inside a single line; without a per-line check
        // the line loop would finish before the next file-level check ran.
        // Order-independence: whichever of the two files the filesystem
        // lists first, exactly ONE gets read, its line loop trips mid-file,
        // and the walk stops before the other is ever reached.
        val root = tempRoot()
        newFile(root, "f1.txt", (1..1000).joinToString("\n") { "nothing $it" })
        newFile(root, "f2.txt", (1..1000).joinToString("\n") { "nothing $it" })
        var now = 0L
        val opts = GrepStreamScan.Options(clock = { now }, deadlineMs = 45_000L)

        val stats = GrepStreamScan.scan(root, { line -> now += 50L; false }, opts)

        // The trip lands mid-file: the remainder of the scanned file was
        // dropped, the other file was never reached, and the walk stops.
        assertTrue(stats.stoppedByTimeout)
        assertEquals(1, stats.filesScanned)
        assertEquals("the rest of the scanned file was dropped when the deadline tripped", 0, stats.totalMatches)
        assertTrue(stats.output.isEmpty())
        val report = GrepStreamScan.formatReport("needle", "/w", stats, 50)
        assertTrue(report.contains("(scan deadline hit)"))
    }

    @Test
    fun `deadline stops the scan and the report says partial`() {
        val root = tempRoot()
        newFile(root, "f1.txt", "nothing\n")
        newFile(root, "f2.txt", "nothing\n")
        var now = 0L
        val opts = GrepStreamScan.Options(
            clock = { now },
            readLines = { f ->
                now += 50_000L
                f.readLines()
            },
        )

        val stats = scan(root, opts)

        // First file scanned (clock 0), second file's deadline check trips.
        assertEquals(1, stats.filesScanned)
        assertTrue(stats.stoppedByTimeout)
        val report = GrepStreamScan.formatReport("needle", "/w", stats, 50)
        assertTrue(report.contains("(scan deadline hit)"))
    }

    @Test
    fun `deadline with matches keeps the match header and marks partial coverage`() {
        val root = tempRoot()
        // Two matching files; the clock advances only on the SECOND read, so
        // whichever the filesystem lists first, the first file is read fully
        // (clock 0) and its match stands, while the second file's line loop
        // trips on its first line once its read pushed the clock past the
        // deadline (deadlineMs defaults to 45s). Deterministic in any listing
        // order.
        newFile(root, "a.txt", "needle a\n")
        newFile(root, "z.txt", "needle z\n")
        var now = 0L
        var reads = 0
        val opts = GrepStreamScan.Options(
            clock = { now },
            readLines = { f ->
                if (reads++ >= 1) now += 100_000L
                f.readLines()
            },
        )

        val stats = scan(root, opts)

        assertEquals(2, stats.filesScanned)
        assertEquals(1, stats.totalMatches)
        assertTrue(stats.stoppedByTimeout)
        val report = GrepStreamScan.formatReport("needle", "/w", stats, 50)
        assertTrue(report.contains("1 match(es) across 1 file(s)"))
        assertTrue(report.contains("[scan deadline hit — partial coverage]"))
    }

    // ----------------------------------------------- remainder estimate ----

    @Test
    fun `match cap reports the unscanned remainder`() {
        val root = tempRoot()
        // 12 files, one match each; the cap is 3.
        repeat(12) { i -> newFile(root, "f%02d.txt".format(i), "needle $i\n") }

        val stats = scan(root, GrepStreamScan.Options(maxMatches = 3))

        assertTrue(stats.stoppedByMatchCap)
        assertEquals(3, stats.totalMatches)
        assertEquals(3, stats.filesScanned)
        assertEquals(9L, stats.approxRemainingUnscanned)
        val report = GrepStreamScan.formatReport("needle", "/w", stats, 3)
        assertTrue(report.contains("(match cap reached)"))
        assertTrue(report.contains("[~9 more file(s) unscanned]"))
    }

    @Test
    fun `deadline during the remainder count reports unknown instead of a partial figure`() {
        val root = tempRoot()
        // Timeline with +5_000 per clock call: startedAt=5k, deadline=50k;
        // the root child loop (5 dirs) burns 15k-35k, d0's file match-line
        // check at 45k completes the match and trips the cap, then the next
        // walk top at 55k trips the deadline with the count still unstarted
        // → remainingKnown=false → the remainder is reported unknown.
        repeat(5) { i ->
            val d = newDir(root, "d$i")
            newFile(d, "f$i.txt", "needle $i\n")
        }
        var now = 0L
        val opts = GrepStreamScan.Options(
            maxMatches = 1,
            clock = { now += 5_000L; now },
            readLines = { f -> f.readLines() },
        )

        val stats = scan(root, opts)
        val dbg = "matchCap=${stats.stoppedByMatchCap} timeout=${stats.stoppedByTimeout} remaining=${stats.approxRemainingUnscanned} scanned=${stats.filesScanned} remainingKnown-hint"

        assertTrue("$dbg — cap should trip", stats.stoppedByMatchCap)
        assertTrue("$dbg — deadline should trip mid-count", stats.stoppedByTimeout)
        assertNull("$dbg — remainder must be unknown", stats.approxRemainingUnscanned)
        val report = GrepStreamScan.formatReport("needle", "/w", stats, 1)
        assertTrue(report.contains("[scan deadline hit — partial coverage]"))
        assertFalse(report.contains("more file(s) unscanned"))
    }

    @Test
    fun `no remainder bracket when the walk finishes the whole tree`() {
        val root = tempRoot()
        repeat(3) { i -> newFile(root, "f$i.txt", "needle $i\n") }

        val stats = scan(root, GrepStreamScan.Options(maxMatches = 50))

        assertFalse(stats.truncated)
        // Walk completed: nothing is unscanned, and the report stays silent
        // about a zero remainder.
        assertEquals(0L, stats.approxRemainingUnscanned)
        val report = GrepStreamScan.formatReport("needle", "/w", stats, 50)
        assertFalse(report.contains("more file(s) unscanned"))
    }

    @Test
    fun `walk guard makes the remainder estimate unknown not an underestimate`() {
        // review P2-10: the walk guard abandons whole subtrees (depth /
        // directory-count guard), so any "K more files" figure would be a
        // LOWER BOUND — report unknown and let the walk-guard bracket carry
        // the honest partial-coverage signal instead of a silently
        // underestimated remainder.
        val root = tempRoot()
        repeat(10) { i ->
            val d = newDir(root, "d$i")
            newFile(d, "f.txt", "needle $i\n")
        }

        val stats = scan(root, GrepStreamScan.Options(maxMatches = 1, maxDirsVisited = 4))

        assertTrue(stats.stoppedByMatchCap)
        assertTrue(stats.stoppedByWalkGuard)
        assertNull(stats.approxRemainingUnscanned)
        val report = GrepStreamScan.formatReport("needle", "/w", stats, 1)
        assertTrue(report.contains("(match cap reached)"))
        assertTrue(report.contains("[walk guard hit — partial coverage]"))
        assertFalse(report.contains("more file(s) unscanned"))
    }

    // ---------------------------------------------------- report format ----

    @Test
    fun `report header keeps the pre-refactor shape byte for byte`() {
        val stats = GrepStreamScan.ScanStats(
            totalMatches = 2,
            hitFiles = 1,
            filesScanned = 5,
            filesSkippedLarge = 0,
            filesSkippedIO = 0,
            approxRemainingUnscanned = null,
            stoppedByMatchCap = false,
            stoppedByOutputCap = false,
            stoppedByTimeout = false,
            stoppedByWalkGuard = false,
            output = "=== /w/a.txt ===\n>1: needle one\n 2: needle two\n",
        )

        val report = GrepStreamScan.formatReport("needle", "/w", stats, 50)

        assertEquals(
            "[grep_source: \"needle\" in /w — 2 match(es) across 1 file(s), scanned 5]\n\n" +
                "=== /w/a.txt ===\n>1: needle one\n 2: needle two",
            report,
        )
    }

    @Test
    fun `no matches report keeps the pre-refactor shape`() {
        val stats = GrepStreamScan.ScanStats(
            totalMatches = 0,
            hitFiles = 0,
            filesScanned = 7,
            filesSkippedLarge = 2,
            filesSkippedIO = 1,
            approxRemainingUnscanned = null,
            stoppedByMatchCap = false,
            stoppedByOutputCap = false,
            stoppedByTimeout = false,
            stoppedByWalkGuard = false,
            output = "",
        )

        val report = GrepStreamScan.formatReport("zzz", "/var/minis/workspace", stats, 50)

        assertEquals(
            "No matches for \"zzz\" in /var/minis/workspace (scanned 7 file(s)) " +
                "(2 skipped >512 KB) (1 unreadable)",
            report,
        )
    }

    @Test
    fun `match cap mid file appends the per-file cap note`() {
        val root = tempRoot()
        newFile(root, "multi.txt", "needle a\nneedle b\nneedle c\n")

        val stats = scan(root, GrepStreamScan.Options(maxMatches = 2))

        assertTrue(stats.output.contains("… more matches in this file (cap) …"))
        assertEquals(2, stats.totalMatches)
    }
}
