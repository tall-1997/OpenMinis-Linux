package com.openminis.app.agent

import com.openminis.app.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The persona priority chain the session menu relies on:
 * session override > per-provider selection > global selection > default.
 *
 * The session level is file-presence based (PersonaPromptLibrary.resolve reads
 * PERSONA.md from the session memory dir before anything else); the three
 * shared levels are pure and are pinned here. The global-rules half of the
 * same requirement — a session-menu edit must win for this chat only — is the
 * sessionScoped header on the injected fragment.
 */
class PersonaScopePriorityTest {
    private fun index() = PersonaPromptIndex(
        prompts = listOf(
            PersonaPromptLogic.builtinEntry(),
            PersonaPromptEntry("c1", "custom.md", "c1.md", builtin = false),
            PersonaPromptEntry("c2", "other.md", "c2.md", builtin = false),
        ),
        selectedId = "c1",
        providerSelections = mapOf("p1" to "c2"),
    )

    @Test
    fun providerSelectionReportsProviderScope() {
        val (id, scope) = PersonaPromptLogic.resolveIdWithSource(index(), "p1")
        assertEquals("c2", id)
        assertEquals(ResolvedPersonaPrompt.SCOPE_PROVIDER, scope)
    }

    @Test
    fun globalSelectionReportsGlobalScope() {
        val (id, scope) = PersonaPromptLogic.resolveIdWithSource(index(), null)
        assertEquals("c1", id)
        assertEquals(ResolvedPersonaPrompt.SCOPE_GLOBAL, scope)
    }

    @Test
    fun unknownFallsBackToBuiltinScope() {
        val empty = PersonaPromptIndex(emptyList(), "nope", emptyMap())
        val (id, scope) = PersonaPromptLogic.resolveIdWithSource(empty, "p1")
        assertEquals(PersonaPromptLogic.BUILTIN_ID, id)
        assertEquals(ResolvedPersonaPrompt.SCOPE_BUILTIN, scope)
    }

    @Test
    fun resolveIdStillMatchesSourcelessCallers() {
        assertEquals("c2", PersonaPromptLogic.resolveId(index(), "p1"))
        assertEquals("c1", PersonaPromptLogic.resolveId(index(), null))
    }

    @Test
    fun sessionScopedGlobalHeaderStatesOverrideSemantics() {
        val dir = Files.createTempDirectory("memscope").toFile()
        val repo = MemoryRepository(dir)
        repo.saveGlobalMd("rule A\n")
        val app = repo.loadGlobalMemoryFragment(sessionScoped = false)!!
        val session = repo.loadGlobalMemoryFragment(sessionScoped = true)!!
        assertTrue(session.contains("Session-scoped standing rules"))
        assertTrue(session.contains("THESE win"))
        assertFalse(app.contains("Session-scoped standing rules"))
        assertTrue(app.endsWith("rule A\n"))
        assertTrue(session.endsWith("rule A\n"))
    }
}
