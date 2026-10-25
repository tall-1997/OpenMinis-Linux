package com.openminis.app.data

import com.openminis.app.sandbox.SessionWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionForkCopyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun ungroupedCopyKeepsWorkspaceAndMemory() {
        val filesDir = tmp.root
        val note = File(SessionWorkspace.base(filesDir, "src"), "workspace/note.txt")
        note.parentFile!!.mkdirs()
        note.writeText("private-note")
        val mem = File(SessionWorkspace.base(filesDir, "src"), "memory/2026-09-24.md")
        mem.parentFile!!.mkdirs()
        mem.writeText("diary")

        copyEffectiveSessionFiles(filesDir, "src", null, "dst")

        assertEquals(
            "private-note",
            File(SessionWorkspace.base(filesDir, "dst"), "workspace/note.txt").readText(),
        )
        assertEquals(
            "diary",
            File(SessionWorkspace.base(filesDir, "dst"), "memory/2026-09-24.md").readText(),
        )
    }

    @Test
    fun filedCopyTakesProjectFilesAndLeavesProjectUntouched() {
        val filesDir = tmp.root
        val project = File(SessionWorkspace.projectBase(filesDir, "folder"), "workspace/shared.txt")
        project.parentFile!!.mkdirs()
        project.writeText("from-project")
        val mem = File(SessionWorkspace.base(filesDir, "src"), "memory/day.md")
        mem.parentFile!!.mkdirs()
        mem.writeText("only-this-session")

        copyEffectiveSessionFiles(filesDir, "src", "folder", "dst")

        assertEquals(
            "from-project",
            File(SessionWorkspace.base(filesDir, "dst"), "workspace/shared.txt").readText(),
        )
        assertEquals("from-project", project.readText())
        assertEquals(
            "only-this-session",
            File(SessionWorkspace.base(filesDir, "dst"), "memory/day.md").readText(),
        )
        assertFalse(File(SessionWorkspace.projectBase(filesDir, "folder"), "memory").exists())
    }
}
