package com.openminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * [T-android-present-tool] JVM tests for the pure file layer of PresentTool:
 * name sanitizing, collision numbering, copy semantics (original untouched),
 * the 50 MB cap and the error branches. Android types stay out of
 * [PresentTool.executeCore], so no Robolectric is needed here.
 */
class PresentToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sharedDir get() = File(tmp.root, "shared")

    // ── sanitizeFileName ──

    @Test
    fun sanitizeKeepsPathTailAndControlCharsOut() {
        assertEquals("report.md", PresentTool.sanitizeFileName("report.md"))
        assertEquals("passwd", PresentTool.sanitizeFileName("../../etc/passwd"))
        assertEquals("a_b", PresentTool.sanitizeFileName("a\nb"))
        assertEquals("deliverable", PresentTool.sanitizeFileName(".."))
        assertEquals("deliverable", PresentTool.sanitizeFileName("   "))
    }

    // ── allocateTarget collision numbering ──

    @Test
    fun collisionNumbersAppendBeforeExtension() {
        assertTrue(sharedDir.mkdirs())
        assertEquals("report.md" to 1, PresentTool.allocateTarget(sharedDir, "report.md").let { it.first.name to it.second })
        assertTrue(File(sharedDir, "report.md").createNewFile())
        assertEquals("report (2).md" to 2, PresentTool.allocateTarget(sharedDir, "report.md").let { it.first.name to it.second })
        assertTrue(File(sharedDir, "report (2).md").createNewFile())
        assertEquals("report (3).md" to 3, PresentTool.allocateTarget(sharedDir, "report.md").let { it.first.name to it.second })
        // Extensionless names number the whole stem.
        assertEquals("notes" to 1, PresentTool.allocateTarget(sharedDir, "notes").let { it.first.name to it.second })
        assertTrue(File(sharedDir, "notes").createNewFile())
        assertEquals("notes (2)" to 2, PresentTool.allocateTarget(sharedDir, "notes").let { it.first.name to it.second })
    }

    // ── happy path ──

    @Test
    fun presentCopiesAndKeepsOriginalIntact() {
        val src = tmp.newFile("report.md").apply { writeText("hello deliverable") }
        val result = PresentTool.executeCore(
            JSONObject().put("path", "/var/minis/workspace/report.md").put("title", "Q3 Report"),
            sharedDir,
        ) { src }
        assertTrue(result.success)
        val copy = File(sharedDir, "report.md")
        assertTrue(copy.isFile)
        assertEquals("hello deliverable", copy.readText())
        // Copy, not move.
        assertTrue(src.isFile)
        assertEquals("hello deliverable", src.readText())
        assertTrue(result.output.contains("Path: /var/minis/shared/report.md"))
        assertTrue(result.output.contains("Minis Ultra > shared"))
        // The card's machine-readable channel round-trips.
        assertEquals("/var/minis/shared/report.md", PresentTool.parseSharedGuestPath(result.output))
    }

    @Test
    fun duplicatePresentArchivesNumberedCopy() {
        val src = tmp.newFile("out.csv").apply { writeText("a,b") }
        val core = { PresentTool.executeCore(JSONObject().put("path", "/var/minis/workspace/out.csv"), sharedDir) { src } }
        val first = core()
        val second = core()
        assertTrue(first.success)
        assertTrue(second.success)
        assertTrue(File(sharedDir, "out.csv").isFile)
        assertEquals("a,b", File(sharedDir, "out (2).csv").readText())
        assertTrue(second.output.contains("copy #2"))
        assertEquals("/var/minis/shared/out (2).csv", PresentTool.parseSharedGuestPath(second.output))
    }

    @Test
    fun titleFallsBackToFileNameAndNoteIsAppended() {
        val src = tmp.newFile("chart.png").apply { writeText("png-bytes") }
        val result = PresentTool.executeCore(
            JSONObject().put("path", "/var/minis/workspace/chart.png").put("note", "daily revenue"),
            sharedDir,
        ) { src }
        assertTrue(result.success)
        assertTrue(result.output.contains("archived as chart.png"))
        assertTrue(result.output.contains("Note: daily revenue"))
        // No title arg -> the pill label falls back to the tool name.
        assertEquals("present_file", result.toolTitle)
    }

    // ── error branches ──

    @Test
    fun rejectsMissingPathArg() {
        val result = PresentTool.executeCore(JSONObject(), sharedDir) { null }
        assertFalse(result.success)
        assertTrue(result.output.contains("'path' is required"))
    }

    @Test
    fun reportsUnresolvableSource() {
        val result = PresentTool.executeCore(
            JSONObject().put("path", "/etc/shadow"),
            sharedDir,
        ) { null }
        assertFalse(result.success)
        assertTrue(result.output.contains("cannot resolve"))
        assertEquals(ToolErrorCode.NOT_FOUND, result.errorCode)
    }

    @Test
    fun rejectsDirectoryAndMissingFile() {
        val dir = tmp.newFolder("docs")
        val asDir = PresentTool.executeCore(
            JSONObject().put("path", "/var/minis/workspace/docs"),
            sharedDir,
        ) { dir }
        assertFalse(asDir.success)
        assertTrue(asDir.output.contains("is a directory"))

        val ghost = File(tmp.root, "ghost.md")
        val asMissing = PresentTool.executeCore(
            JSONObject().put("path", "/var/minis/workspace/ghost.md"),
            sharedDir,
        ) { ghost }
        assertFalse(asMissing.success)
        assertTrue(asMissing.output.contains("file not found"))
    }

    @Test
    fun rejectsOversizedFile() {
        val big = File(tmp.root, "big.bin")
        RandomAccessFile(big, "rw").use { it.setLength(PresentTool.MAX_FILE_BYTES + 1) }
        val result = PresentTool.executeCore(
            JSONObject().put("path", "/var/minis/workspace/big.bin"),
            sharedDir,
        ) { big }
        assertFalse(result.success)
        assertTrue(result.output.contains("50 MB"))
    }

    @Test
    fun rejectsEmptySourceCopy() {
        // The copy layer alone would verify 0 == 0 as INTACT, so PresentTool
        // refuses empty sources up front (same 0-byte-husks doctrine as
        // VerifiedCopy.copyFileToDocument).
        val empty = tmp.newFile("empty.txt")
        val result = PresentTool.executeCore(
            JSONObject().put("path", "/var/minis/workspace/empty.txt"),
            sharedDir,
        ) { empty }
        assertFalse(result.success)
        assertTrue(result.output.contains("source is empty"))
    }

    // ── definition + parser hygiene ──

    @Test
    fun definitionRequiresPathAndParserIgnoresOtherText() {
        val definition = PresentTool.definition()
        assertEquals("present_file", definition.name)
        assertTrue("path" in definition.required)
        assertTrue(definition.parameters.containsKey("title"))
        assertTrue(definition.parameters.containsKey("note"))
        assertNull(PresentTool.parseSharedGuestPath("no path line here"))
        assertNull(PresentTool.parseSharedGuestPath("Path: /var/minis/workspace/tool-spill/x.txt"))
        assertNull(PresentTool.parseSharedGuestPath("Path: /var/minis/shared/../secret"))
    }
}
