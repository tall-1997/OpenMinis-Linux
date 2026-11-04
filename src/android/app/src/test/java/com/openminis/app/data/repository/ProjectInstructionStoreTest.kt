package com.openminis.app.data.repository

import com.openminis.app.sandbox.SessionWorkspace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-memory-project-rules] The project layer codex gets from `AGENTS.md`:
 * discovery by project folder, override precedence, and an empty override that
 * must not shadow a populated file.
 */
class ProjectInstructionStoreTest {

    private lateinit var filesDir: File
    private val sessionId = "s-project-rules"
    private val folderId = "f-project-rules"

    @Before
    fun setUp() {
        ProjectInstructionStore.clearCacheForTest()
        filesDir = Files.createTempDirectory("project-rules").toFile()
        SessionWorkspace.rememberFolder(sessionId, folderId)
        SessionWorkspace.ensureProjectDirs(filesDir, folderId)
    }

    @After
    fun tearDown() {
        SessionWorkspace.rememberFolder(sessionId, null)
        ProjectInstructionStore.clearCacheForTest()
        filesDir.deleteRecursively()
    }

    private fun projectFile(name: String): File =
        File(SessionWorkspace.projectBase(filesDir, folderId), name)

    @Test
    fun unfiledSessionHasNoProjectRules() {
        SessionWorkspace.rememberFolder("s-unfiled-project-test", null)
        assertNull(ProjectInstructionStore.resolve(filesDir, "s-unfiled-project-test"))
        assertNull(ProjectInstructionStore.fragment(filesDir, "s-unfiled-project-test"))
    }

    @Test
    fun missingFileYieldsNothing() {
        assertNull(ProjectInstructionStore.resolve(filesDir, sessionId))
    }

    @Test
    fun agentsMdIsDiscovered() {
        projectFile(ProjectInstructionStore.PROJECT_RULES_FILE).writeText("use tabs\n")
        ProjectInstructionStore.clearCacheForTest()
        val resolved = ProjectInstructionStore.resolve(filesDir, sessionId)
        assertTrue(resolved != null && resolved.content.contains("use tabs"))
    }

    @Test
    fun overrideWinsOverTheBaseFile() {
        projectFile(ProjectInstructionStore.PROJECT_RULES_FILE).writeText("base rule\n")
        projectFile(ProjectInstructionStore.PROJECT_OVERRIDE_FILE).writeText("override rule\n")
        ProjectInstructionStore.clearCacheForTest()
        val resolved = ProjectInstructionStore.resolve(filesDir, sessionId)!!
        assertTrue(resolved.content.contains("override rule"))
        assertFalse(resolved.content.contains("base rule"))
        assertTrue(resolved.name == ProjectInstructionStore.PROJECT_OVERRIDE_FILE)
    }

    @Test
    fun anEmptyOverrideDoesNotShadowTheBaseFile() {
        projectFile(ProjectInstructionStore.PROJECT_RULES_FILE).writeText("base rule\n")
        projectFile(ProjectInstructionStore.PROJECT_OVERRIDE_FILE).writeText("")
        ProjectInstructionStore.clearCacheForTest()
        val resolved = ProjectInstructionStore.resolve(filesDir, sessionId)!!
        assertTrue(resolved.content.contains("base rule"))
    }

    @Test
    fun fragmentCarriesTheAuthorityEnvelopeAndPrecedenceHeader() {
        projectFile(ProjectInstructionStore.PROJECT_RULES_FILE).writeText("use tabs\n")
        ProjectInstructionStore.clearCacheForTest()
        val fragment = ProjectInstructionStore.fragment(filesDir, sessionId)!!
        assertTrue(fragment.startsWith("[MEMORY_REFERENCE"))
        assertTrue(fragment.contains("instruction_authority:conditional"))
        assertTrue(fragment.contains("defer to the user's latest message"))
        assertTrue(fragment.contains("use tabs"))
    }

    @Test
    fun oversizedFileIsCapped() {
        projectFile(ProjectInstructionStore.PROJECT_RULES_FILE)
            .writeText("x".repeat(ProjectInstructionStore.MAX_CHARS + 500))
        ProjectInstructionStore.clearCacheForTest()
        assertEquals(
            ProjectInstructionStore.MAX_CHARS,
            ProjectInstructionStore.resolve(filesDir, sessionId)!!.content.length,
        )
    }

    // ── [T-memory-project-rules-lastgood] unobservable file ─────────────────

    @Test
    fun unreadableFileKeepsTheLastGoodRender() {
        val file = projectFile(ProjectInstructionStore.PROJECT_RULES_FILE)
        file.writeText("use tabs\n")
        ProjectInstructionStore.clearCacheForTest()
        val good = ProjectInstructionStore.resolve(filesDir, sessionId)!!

        // Change the file so the fingerprint no longer matches, then fail the read.
        file.writeText("use tabs and spaces\n")
        val transient = ProjectInstructionStore.resolve(filesDir, sessionId) {
            throw java.io.IOException("simulated read failure")
        }

        assertEquals(
            "a transient read failure must not drop the rules the model was following",
            good.content,
            transient?.content,
        )
    }

    @Test
    fun aFileThatNeverResolvedDoesNotInventAValue() {
        projectFile(ProjectInstructionStore.PROJECT_RULES_FILE).writeText("use tabs\n")
        ProjectInstructionStore.clearCacheForTest()
        val failed = ProjectInstructionStore.resolve(filesDir, sessionId) {
            throw java.io.IOException("simulated read failure")
        }
        assertNull("no last-good exists, so there is nothing to serve", failed)
    }

    @Test
    fun deletingTheFileClearsTheMemo() {
        val file = projectFile(ProjectInstructionStore.PROJECT_RULES_FILE)
        file.writeText("use tabs\n")
        ProjectInstructionStore.clearCacheForTest()
        assertTrue(ProjectInstructionStore.resolve(filesDir, sessionId) != null)

        file.delete()
        assertNull(
            "legitimate absence is not a read failure — the rules are simply gone",
            ProjectInstructionStore.resolve(filesDir, sessionId),
        )
    }
}
