package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-tool-output-offload-loop GH#343] JVM round-trip: raw output → spill file →
 * read-back must converge (one file, bounded previews), never grow a chain of
 * spill files. Also pins the read-back resolver for both `file_read` and the
 * shell `cat/head/tail … tool-spill/…` spelling — #343 was reported because the
 * old source-path fix knew only `file_read`.
 */
class ToolOutputSpillRoundTripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val raw = "H".repeat(8_000) + "M".repeat(20_000) + "T".repeat(5_000)

    @Test
    fun `raw output plans a spill and writes the full body to the session dir`() {
        // The OLD execution-entry pre-cap turned this into a ~10k preview before
        // this decision ran, so it always chose PASSTHROUGH (no file). The plan
        // is now made on the raw body.
        assertEquals(SpillPlan.WRITE, ToolOutputSpill.planFor(raw, hasSession = true, sourcePath = null))

        val dir = tmp.newFolder("tool-spill")
        val preview = ToolOutputSpill.writeSpill(dir, "shell_execute", "call-1", raw)

        assertTrue(preview.startsWith("[tool-output-spill]"))
        assertTrue(preview.contains("/var/minis/workspace/tool-spill/call-1.txt"))
        assertTrue(preview.contains("chars omitted"))
        assertTrue(preview.length < raw.length)
        val file = File(dir, "call-1.txt")
        assertTrue("spill file must exist", file.isFile)
        assertEquals("spill file keeps the RAW body", raw, file.readText())
        // Feeding the preview back through the policy is a no-op: a placeholder
        // is never wrapped in another placeholder.
        assertEquals(SpillPlan.PASSTHROUGH, ToolOutputSpill.planFor(preview, true, null))
    }

    @Test
    fun `reading a spill file back never writes a second spill`() {
        val dir = tmp.newFolder("tool-spill")
        val guest = "/var/minis/workspace/tool-spill/call-2.txt"
        ToolOutputSpill.writeSpill(dir, "shell_execute", "call-2", raw)

        // file_read / cat of the spill file: still > LIMIT, but the SOURCE is
        // already the offload target → answer from it, do not spill again.
        assertEquals(SpillPlan.ALREADY_SPILLED, ToolOutputSpill.planFor(raw, true, guest))
        val readback = ToolOutputSpill.alreadySpilledPreview("shell_execute", guest, raw)
        assertTrue(readback.contains("NOT being offloaded again"))
        assertTrue(readback.contains(guest))

        // The read-back answer is itself a placeholder: no further hop.
        assertEquals(SpillPlan.PASSTHROUGH, ToolOutputSpill.planFor(readback, true, guest))
        assertTrue(readback.startsWith("[tool-output-spill]"))

        // Exactly the original file exists — the read-back wrote nothing.
        assertEquals(1, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `no session still caps on the raw body instead of returning it`() {
        assertEquals(SpillPlan.CAPPED, ToolOutputSpill.planFor(raw, hasSession = false, sourcePath = null))
        val capped = ToolOutputSpill.cappedPreview("shell_execute", raw)
        assertTrue(capped.contains("could not be saved"))
        assertTrue(capped.length < raw.length)
    }

    @Test
    fun `read-back resolver covers file_read and shell read verbs`() {
        val guest = "/var/minis/workspace/tool-spill/a.txt"
        assertEquals(
            guest,
            ToolOutputSpill.sourcePathForTool("file_read", """{"path":"$guest"}"""),
        )
        // Workspace-relative spelling from the model is normalised.
        assertEquals(
            guest,
            ToolOutputSpill.sourcePathForTool("file_read", """{"path":"tool-spill/a.txt"}"""),
        )
        assertEquals(
            guest,
            ToolOutputSpill.sourcePathForTool(
                "shell_execute",
                """{"command":"cat /var/minis/workspace/tool-spill/a.txt"}""",
            ),
        )
        // Historical alias must resolve to shell_execute.
        assertEquals(
            guest,
            ToolOutputSpill.sourcePathForTool(
                "shell_exec",
                """{"command":"head -n 20 /var/minis/workspace/tool-spill/a.txt"}""",
            ),
        )
        assertEquals(
            guest,
            ToolOutputSpill.sourcePathForTool(
                "su_exec",
                """{"command":"tail -n 5 tool-spill/a.txt"}""",
            ),
        )

        // Not reads of a spill file → no source path, so normal spill still runs.
        assertNull(ToolOutputSpill.sourcePathForTool("file_read", """{"path":"/var/minis/workspace/report.txt"}"""))
        assertNull(ToolOutputSpill.sourcePathForTool("shell_execute", """{"command":"echo /var/minis/workspace/tool-spill/a.txt"}"""))
        assertNull(ToolOutputSpill.sourcePathForTool("shell_execute", """{"command":"ls /var/minis/workspace/tool-spill"}"""))
        assertNull(ToolOutputSpill.sourcePathForTool("grep", """{"pattern":"tool-spill/a.txt"}"""))
    }

    @Test
    fun `write tool set covers every persisting tool and its aliases`() {
        for (name in listOf(
            "file_write", "file_edit", "multi_edit", "memory_write",
            "file_checkpoint", "skill_manage",
            // alias labels routed by ToolAliases.canonical()
            "save_memory", "checkpoint", "rollback", "undo",
        )) {
            assertTrue("$name must be treated as a write tool", WriteTools.isWriteTool(name))
        }
        // Read-only / command tools keep the documented repair-and-run behaviour.
        for (name in listOf("file_read", "shell_execute", "grep", "list_dir", "web_search")) {
            assertFalse("$name must not be a write tool", WriteTools.isWriteTool(name))
        }
    }

    // ── [F1 GH#343] spill filename identity: no cross-call overwrite ──

    private fun guestName(preview: String): String =
        ToolOutputSpill.parseGuestPath(preview)?.substringAfterLast('/')
            ?: error("preview carries no spill path: $preview")

    @Test
    fun `two different call ids never overwrite each other's spill`() {
        val dir = tmp.newFolder("tool-spill")
        val first = raw
        val second = "A".repeat(17_000)

        val nameA = guestName(ToolOutputSpill.writeSpill(dir, "shell_execute", "call-a", first))
        val nameB = guestName(ToolOutputSpill.writeSpill(dir, "shell_execute", "call-b", second))

        assertNotEquals(nameA, nameB)
        assertEquals(first, File(dir, nameA).readText())
        assertEquals(second, File(dir, nameB).readText())
    }

    @Test
    fun `blank call id produces a unique file per call`() {
        val dir = tmp.newFolder("tool-spill")
        val first = raw
        val second = "B".repeat(19_000)

        // "" is what group chat / supervisor passed before F1: every call wrote
        // `tool.txt` and the last writer silently won.
        val nameA = guestName(ToolOutputSpill.writeSpill(dir, "shell_execute", "", first))
        val nameB = guestName(ToolOutputSpill.writeSpill(dir, "shell_execute", "", second))

        assertNotEquals(nameA, nameB)
        assertTrue("blank-id spill must be unique-suffixed, got $nameA", nameA.startsWith("tool-"))
        assertTrue("blank-id spill must be unique-suffixed, got $nameB", nameB.startsWith("tool-"))
        assertEquals(first, File(dir, nameA).readText())
        assertEquals(second, File(dir, nameB).readText())

        // The generated name must still be a recognisable read-back target: the
        // model will `file_read` this exact guest path later (#343 de-loop).
        val guestA = ToolOutputSpill.parseGuestPath(
            ToolOutputSpill.writeSpill(dir, "shell_execute", "readback-1", first),
        )!!
        assertTrue(ToolOutputSpill.isSpillGuestPath(guestA))
        assertEquals(SpillPlan.ALREADY_SPILLED, ToolOutputSpill.planFor(first, true, guestA))
        assertEquals(
            guestA,
            ToolOutputSpill.sourcePathForTool("file_read", """{"path":"$guestA"}"""),
        )
        assertTrue(ToolOutputSpill.alreadySpilledPreview("file_read", guestA, first).contains(guestA))
    }

    @Test
    fun `a reused call id appends a suffix instead of overwriting`() {
        val dir = tmp.newFolder("tool-spill")
        val first = raw
        val second = "C".repeat(18_000)

        val nameA = guestName(ToolOutputSpill.writeSpill(dir, "shell_execute", "same-call", first))
        val nameB = guestName(ToolOutputSpill.writeSpill(dir, "shell_execute", "same-call", second))

        assertEquals("same-call.txt", nameA)
        assertNotEquals("reused id must not overwrite the first spill", nameA, nameB)
        assertEquals("first spill content must survive", first, File(dir, nameA).readText())
        assertEquals(second, File(dir, nameB).readText())
    }

    @Test
    fun `concurrent spills with the same reused id are unique and uncorrupted`() {
        val dir = tmp.newFolder("tool-spill")
        val bodies = (1..8).map { idx -> "call-$idx\n" + idx.toString().repeat(30_000) }
        val names = java.util.Collections.synchronizedList(mutableListOf<String>())

        val threads = bodies.map { body ->
            Thread {
                val preview = ToolOutputSpill.writeSpill(dir, "shell_execute", "shared-call", body)
                names.add(guestName(preview))
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }

        assertEquals("every writer must produce a preview", bodies.size, names.size)
        assertEquals("each writer must own a distinct file", bodies.size, names.toSet().size)
        assertTrue("names must stay inside the guest charset", names.all { it.matches(Regex("[A-Za-z0-9._-]+\\.txt")) })

        val files = dir.listFiles()!!.filter { it.isFile && it.name.endsWith(".txt") }
        assertEquals("one file per writer", bodies.size, files.size)
        for (f in files) {
            // Atomic write + verified content: no interleaving, no half body.
            assertTrue("corrupt spill file ${f.name}", f.readText() in bodies)
        }
        assertTrue(
            "atomic writes must not leave temp artifacts behind",
            dir.listFiles()!!.none { it.name.endsWith(".minis-tmp") },
        )
    }
}
