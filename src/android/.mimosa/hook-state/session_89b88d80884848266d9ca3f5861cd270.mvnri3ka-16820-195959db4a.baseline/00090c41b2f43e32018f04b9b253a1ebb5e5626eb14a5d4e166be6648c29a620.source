package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaPromptLibraryTest {

    @Test
    fun `sanitize strips path and illegal characters`() {
        assertEquals("persona.md", PersonaPromptLogic.sanitizeFileName(""))
        assertEquals("notes.md", PersonaPromptLogic.sanitizeFileName("/sdcard/Download/notes.md"))
        assertEquals("a_b.txt", PersonaPromptLogic.sanitizeFileName("a:b.txt"))
        assertEquals("persona.md", PersonaPromptLogic.sanitizeFileName(".."))
    }

    @Test
    fun `unique names get a numeric suffix`() {
        val existing = setOf("voice.md", "voice (2).md")
        assertEquals("other.md", PersonaPromptLogic.uniqueDisplayName("other.md", existing))
        assertEquals("voice (3).md", PersonaPromptLogic.uniqueDisplayName("voice.md", existing))
    }

    @Test
    fun `only md txt markdown are importable names`() {
        assertTrue(PersonaPromptLogic.isImportableName("a.md"))
        assertTrue(PersonaPromptLogic.isImportableName("a.TXT"))
        assertTrue(PersonaPromptLogic.isImportableName("a.markdown"))
        assertFalse(PersonaPromptLogic.isImportableName("a.pdf"))
        assertFalse(PersonaPromptLogic.isImportableName("a"))
        assertTrue(PersonaPromptLogic.isImportableMime("text/plain"))
        assertTrue(PersonaPromptLogic.isImportableMime("text/markdown"))
        assertFalse(PersonaPromptLogic.isImportableMime(null))
        assertFalse(PersonaPromptLogic.isImportableMime("application/pdf"))
        assertFalse(PersonaPromptLogic.isImportableMime("application/octet-stream"))
    }

    @Test
    fun `frontmatter is stripped on import`() {
        val raw = """
            ---
            name: "Minis"
            lang: "auto"
            ---
            Be terse.
        """.trimIndent()
        assertEquals("Be terse.", PersonaPromptLogic.extractImportedBody(raw).trim())
    }

    @Test
    fun `plain text import keeps the whole body`() {
        assertEquals("hello", PersonaPromptLogic.extractImportedBody("hello"))
    }

    @Test
    fun `bom is stripped`() {
        assertEquals("hi", PersonaPromptLogic.extractImportedBody("\uFEFFhi"))
    }

    @Test
    fun `provider mapping wins then selected then builtin`() {
        val custom = PersonaPromptEntry("c1", "custom.md", "c1.md", builtin = false)
        val index = PersonaPromptIndex(
            prompts = listOf(PersonaPromptLogic.builtinEntry(), custom),
            selectedId = "c1",
            providerSelections = mapOf("p1" to PersonaPromptLogic.BUILTIN_ID),
        )
        assertEquals(PersonaPromptLogic.BUILTIN_ID, PersonaPromptLogic.resolveId(index, "p1"))
        assertEquals("c1", PersonaPromptLogic.resolveId(index, "p-missing"))
        assertEquals("c1", PersonaPromptLogic.resolveId(index, null))
    }

    @Test
    fun `stale provider mapping falls back`() {
        val index = PersonaPromptIndex(
            prompts = listOf(PersonaPromptLogic.builtinEntry()),
            selectedId = PersonaPromptLogic.BUILTIN_ID,
            providerSelections = mapOf("p1" to "gone"),
        )
        assertEquals(PersonaPromptLogic.BUILTIN_ID, PersonaPromptLogic.resolveId(index, "p1"))
    }

    @Test
    fun `codec round trips and injects builtin`() {
        val json = PersonaPromptCodec.serialize(
            PersonaPromptIndex(
                prompts = listOf(
                    PersonaPromptEntry("c1", "a.md", "c1.md", builtin = false),
                ),
                selectedId = "c1",
                providerSelections = mapOf("prov" to "c1"),
            ),
        )
        val back = PersonaPromptCodec.parse(json)
        assertTrue(back.prompts.any { it.id == PersonaPromptLogic.BUILTIN_ID && it.builtin })
        assertEquals("c1", back.selectedId)
        assertEquals("c1", back.providerSelections["prov"])
        assertEquals("a.md", back.prompts.first { it.id == "c1" }.fileName)
    }

    @Test
    fun `normalize drops unknown selected id`() {
        val index = PersonaPromptLogic.normalizeIndex(
            PersonaPromptIndex(
                prompts = emptyList(),
                selectedId = "nope",
                providerSelections = mapOf("p" to "nope"),
            ),
        )
        assertEquals(PersonaPromptLogic.BUILTIN_ID, index.selectedId)
        assertTrue(index.providerSelections.isEmpty())
        assertEquals(1, index.prompts.size)
    }

    @Test
    fun `history steering skipped on first user turn`() {
        val history = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "hi"))
        assertSame(history, PersonaPromptLogic.applyHistorySteering(history))
    }

    @Test
    fun `history steering prefixes latest user text after assistant turns`() {
        val history = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "old"),
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "generic"),
            LLMMessage(role = LLMMessage.Role.USER, content = "now speak as a pirate"),
        )
        val out = PersonaPromptLogic.applyHistorySteering(history)
        assertEquals("old", out[0].content)
        assertTrue(out[2].content.contains(PersonaPromptLogic.HISTORY_STEERING_PREFIX))
        assertTrue(out[2].content.contains("now speak as a pirate"))
        assertFalse(out[2].content.contains("old"))
    }

    @Test
    fun `history steering skips tool-result-only user turns`() {
        val history = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "do it"),
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "ok"),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult(id = "t1", name = "bash", content = "done"),
                ),
            ),
        )
        val out = PersonaPromptLogic.applyHistorySteering(history)
        assertTrue(out[0].content.contains(PersonaPromptLogic.HISTORY_STEERING_PREFIX))
        assertEquals("", out[2].content)
        assertTrue(out[2].contentParts.single() is AgentContentPart.ToolResult)
    }

    @Test
    fun `history steering is idempotent`() {
        val history = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "a"),
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "b"),
            LLMMessage(role = LLMMessage.Role.USER, content = "c"),
        )
        val once = PersonaPromptLogic.applyHistorySteering(history)
        val twice = PersonaPromptLogic.applyHistorySteering(once)
        assertEquals(1, twice[2].content.split(PersonaPromptLogic.HISTORY_STEERING_PREFIX).size - 1)
    }

    @Test
    fun `import conflict same body never overwrites`() {
        val existing = listOf(
            PersonaPromptFingerprint("voice.md", "Be terse.", builtin = false),
            PersonaPromptFingerprint("SOUL.md", "builtin soul", builtin = true),
        )
        val sameName = PersonaPromptLogic.classifyImportConflict("other.md", "Be terse.", existing)
        assertEquals(PersonaImportConflictKind.SAME_CONTENT, sameName.kind)
        assertEquals("voice.md", sameName.existingName)
        assertFalse(sameName.canOverwrite)

        val bothSame = PersonaPromptLogic.classifyImportConflict("voice.md", "Be terse.\r\n", existing)
        assertEquals(PersonaImportConflictKind.SAME_CONTENT, bothSame.kind)
        assertFalse(bothSame.canOverwrite)
    }

    @Test
    fun `import conflict same name different body can overwrite only private files`() {
        val existing = listOf(
            PersonaPromptFingerprint("voice.md", "old", builtin = false),
            PersonaPromptFingerprint("SOUL.md", "builtin soul", builtin = true),
        )
        val privateHit = PersonaPromptLogic.classifyImportConflict("voice.md", "new body", existing)
        assertEquals(PersonaImportConflictKind.SAME_NAME, privateHit.kind)
        assertTrue(privateHit.canOverwrite)

        val builtinHit = PersonaPromptLogic.classifyImportConflict("SOUL.md", "rewritten", existing)
        assertEquals(PersonaImportConflictKind.SAME_NAME, builtinHit.kind)
        assertFalse(builtinHit.canOverwrite)

        val none = PersonaPromptLogic.classifyImportConflict("fresh.md", "new body", existing)
        assertEquals(PersonaImportConflictKind.NONE, none.kind)
    }
}
