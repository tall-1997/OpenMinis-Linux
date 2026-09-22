package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceMoverTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun moveIntoEmptyProjectPlaceholderKeepsBytes() {
        val filesDir = tmp.root
        val sid = "sess-move"
        val folder = "folder-move"
        SessionWorkspace.rememberFolder(sid, folder)
        try {
            SessionWorkspace.ensureDirs(filesDir, sid)
            val note = File(SessionWorkspace.base(filesDir, sid), "workspace/note.txt")
            note.parentFile!!.mkdirs()
            note.writeText("keep-me")
            SessionWorkspace.ensureProjectDirs(filesDir, folder)

            val result = WorkspaceMover.moveSessionIntoProject(filesDir, sid, folder)

            assertTrue(result.movedSubdirs.contains("workspace"))
            val moved = File(SessionWorkspace.projectBase(filesDir, folder), "workspace/note.txt")
            assertEquals("keep-me", moved.readText())
            assertFalse(note.exists())
            assertEquals(
                moved.parentFile!!.canonicalPath,
                SessionWorkspace.hostDir(filesDir, sid, "workspace").canonicalPath,
            )
        } finally {
            SessionWorkspace.rememberFolder(sid, null)
        }
    }

    @Test
    fun emptyProjectPlaceholderDoesNotHidePrivateFiles() {
        val filesDir = tmp.root
        val sid = "sess-hide"
        val folder = "folder-hide"
        SessionWorkspace.rememberFolder(sid, folder)
        try {
            SessionWorkspace.ensureProjectDirs(filesDir, folder)
            val note = File(SessionWorkspace.base(filesDir, sid), "workspace/note.txt")
            note.parentFile!!.mkdirs()
            note.writeText("still-here")
            assertEquals(
                note.parentFile!!.canonicalPath,
                SessionWorkspace.hostDir(filesDir, sid, "workspace").canonicalPath,
            )
        } finally {
            SessionWorkspace.rememberFolder(sid, null)
        }
    }

    @Test
    fun nonEmptyProjectStillWinsOverPrivateCopy() {
        val filesDir = tmp.root
        val sid = "sess-win"
        val folder = "folder-win"
        SessionWorkspace.rememberFolder(sid, folder)
        try {
            val projectNote = File(SessionWorkspace.projectBase(filesDir, folder), "workspace/shared.txt")
            projectNote.parentFile!!.mkdirs()
            projectNote.writeText("project")
            val privateNote = File(SessionWorkspace.base(filesDir, sid), "workspace/private.txt")
            privateNote.parentFile!!.mkdirs()
            privateNote.writeText("private")
            assertEquals(
                projectNote.parentFile!!.canonicalPath,
                SessionWorkspace.hostDir(filesDir, sid, "workspace").canonicalPath,
            )
        } finally {
            SessionWorkspace.rememberFolder(sid, null)
        }
    }

    @Test
    fun recoverReplacesEmptyPlaceholderWithStaging() {
        val filesDir = tmp.root
        val folder = "folder-recover"
        SessionWorkspace.ensureProjectDirs(filesDir, folder)
        val project = SessionWorkspace.projectBase(filesDir, folder)
        val staging = File(project, "workspace.staging")
        staging.mkdirs()
        File(staging, "note.txt").writeText("recovered")

        WorkspaceMover.recoverInterrupted(filesDir)

        assertEquals("recovered", File(project, "workspace/note.txt").readText())
        assertFalse(staging.exists())
    }

    @Test
    fun nonEmptyDestinationIsNotDeleted() {
        val filesDir = tmp.root
        val sid = "sess-keep"
        val folder = "folder-keep"
        SessionWorkspace.rememberFolder(sid, folder)
        try {
            SessionWorkspace.ensureDirs(filesDir, sid)
            val note = File(SessionWorkspace.base(filesDir, sid), "workspace/note.txt")
            note.parentFile!!.mkdirs()
            note.writeText("private")
            val existing = File(SessionWorkspace.projectBase(filesDir, folder), "workspace/existing.txt")
            existing.parentFile!!.mkdirs()
            existing.writeText("project")

            val result = WorkspaceMover.moveSessionIntoProject(filesDir, sid, folder)

            assertFalse(result.movedSubdirs.contains("workspace"))
            assertEquals("private", note.readText())
            assertEquals("project", existing.readText())
        } finally {
            SessionWorkspace.rememberFolder(sid, null)
        }
    }
}
