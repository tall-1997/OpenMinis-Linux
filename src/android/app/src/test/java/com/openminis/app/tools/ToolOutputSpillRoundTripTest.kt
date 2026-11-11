package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
