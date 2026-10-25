package com.openminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionWorkspaceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun deleteEntireRemovesMemoryAndWorkspace() {
        val filesDir = tmp.root
        val sid = "sess-a"
        SessionWorkspace.ensureDirs(filesDir, sid)
        File(SessionWorkspace.memoryDir(filesDir, sid), "2026-09-21.md").writeText("note")
        File(SessionWorkspace.base(filesDir, sid), "workspace/a.txt").apply {
            parentFile?.mkdirs()
            writeText("ws")
        }
        assertTrue(SessionWorkspace.deleteEntire(filesDir, sid))
        assertFalse(SessionWorkspace.base(filesDir, sid).exists())
    }

    @Test
    fun deleteDoesNotTouchOtherSessionsOrGlobalSkills() {
        val filesDir = tmp.root
        SessionWorkspace.ensureDirs(filesDir, "a")
        SessionWorkspace.ensureDirs(filesDir, "b")
        File(SessionWorkspace.memoryDir(filesDir, "b"), "keep.md").writeText("b")
        val skills = File(filesDir, "${SessionWorkspace.GLOBAL_DIR}/skills/foo")
        skills.mkdirs()
        File(skills, "SKILL.md").writeText("tool")

        SessionWorkspace.deleteEntire(filesDir, "a")
        assertFalse(SessionWorkspace.base(filesDir, "a").exists())
        assertTrue(File(SessionWorkspace.memoryDir(filesDir, "b"), "keep.md").exists())
        assertTrue(File(skills, "SKILL.md").exists())
    }

    @Test
    fun rejectsPathTraversalIds() {
        val filesDir = tmp.root
        assertFalse(SessionWorkspace.deleteEntire(filesDir, "../escape"))
        assertFalse(SessionWorkspace.deleteEntire(filesDir, ""))
    }

    @Test
    fun filedSessionsShareProjectWorkspaceAndKeepOwnMemory() {
        val filesDir = tmp.root
        SessionWorkspace.rememberFolder("a", "proj")
        SessionWorkspace.rememberFolder("b", "proj")
        try {
            SessionWorkspace.ensureDirs(filesDir, "a")
            SessionWorkspace.ensureDirs(filesDir, "b")
            val sharedA = SessionWorkspace.hostDir(filesDir, "a", "workspace")
            val sharedB = SessionWorkspace.hostDir(filesDir, "b", "workspace")
            assertTrue(sharedA.absolutePath == sharedB.absolutePath)
            File(sharedA, "note.txt").writeText("hi")
            File(SessionWorkspace.memoryDir(filesDir, "a"), "a.md").writeText("a")
            File(SessionWorkspace.memoryDir(filesDir, "b"), "b.md").writeText("b")

            assertTrue(SessionWorkspace.deleteEntire(filesDir, "a"))
            assertTrue(File(sharedB, "note.txt").exists())
            assertFalse(SessionWorkspace.base(filesDir, "a").exists())
            assertTrue(File(SessionWorkspace.memoryDir(filesDir, "b"), "b.md").exists())
        } finally {
            SessionWorkspace.rememberFolder("a", null)
            SessionWorkspace.rememberFolder("b", null)
        }
    }

    @Test
    fun parseDraftFolderIdFromEncodedSessionId() {
        assertTrue(
            SessionWorkspace.parseDraftFolderId("__new__abc__fld__default-workspace")
                == "default-workspace",
        )
        assertTrue(
            SessionWorkspace.parseDraftFolderId("__new__abc__grp__g1__fld__f1") == "f1",
        )
        assertTrue(SessionWorkspace.parseDraftFolderId("plain-session") == null)
    }

    @Test
    fun dotDotCannotEnterAnotherSessionPrivateTree() {
        val filesDir = tmp.root
        SessionWorkspace.ensureDirs(filesDir, "sess-a")
        SessionWorkspace.ensureDirs(filesDir, "sess-b")
        val secret = File(SessionWorkspace.memoryDir(filesDir, "sess-b"), "secret.md")
        secret.writeText("nope")
        val escaped = File(SessionWorkspace.hostDir(filesDir, "sess-a", "workspace"), "../../sess-b/memory/secret.md")
        assertFalse(SessionWorkspace.acceptsResolved(filesDir, "sess-a", escaped))
        assertFalse(SessionWorkspace.staysInside(SessionWorkspace.hostDir(filesDir, "sess-a", "workspace"), escaped))
        assertTrue(SessionWorkspace.acceptsResolved(filesDir, "sess-b", secret))
    }

    @Test
    fun filedSessionMayReadOwnProjectNotAnother() {
        val filesDir = tmp.root
        SessionWorkspace.rememberFolder("sess-a", "folder-a")
        SessionWorkspace.ensureDirs(filesDir, "sess-a")
        try {
            val own = File(SessionWorkspace.projectBase(filesDir, "folder-a"), "workspace/ok.txt")
            own.parentFile.mkdirs()
            own.writeText("ok")
            val other = File(SessionWorkspace.projectBase(filesDir, "folder-b"), "workspace/no.txt")
            other.parentFile.mkdirs()
            other.writeText("no")
            assertTrue(SessionWorkspace.acceptsResolved(filesDir, "sess-a", own))
            assertFalse(SessionWorkspace.acceptsResolved(filesDir, "sess-a", other))
            val memory = File(SessionWorkspace.memoryDir(filesDir, "sess-a"), "note.md")
            memory.writeText("mine")
            assertTrue(SessionWorkspace.acceptsResolved(filesDir, "sess-a", memory))
        } finally {
            SessionWorkspace.rememberFolder("sess-a", null)
        }
    }
}
