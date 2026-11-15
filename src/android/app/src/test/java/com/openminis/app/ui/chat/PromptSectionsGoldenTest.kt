package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prompt-sections] Behaviour-conservation goldens for the section-ized
 * system prompt (pi coding-agent src/core/system-prompt.ts:128-228 port).
 *
 * The oracle in [legacyAssembly] / [legacyStablePrefixLength] is transcribed
 * VERBATIM from the pre-section buildString in
 * ChatViewModel.buildSystemPromptWithSections — the sections rewrite of that
 * builder is only allowed to ship while these tests prove byte equality on a
 * matrix of fragment combinations (including the non-null BLANK fragment edge
 * that leaves a bare separator behind).
 */
class PromptSectionsGoldenTest {

    // ------------------------------------------------------------------
    // Legacy oracles (pre-section code, transcribed verbatim)
    // ------------------------------------------------------------------

    private fun legacyAssembly(
        base: String,
        skill: String?,
        mcp: String?,
        memory: String?,
        tail: String,
    ): String = buildString {
        append(base)
        if (skill != null) {
            append("\n\n")
            append(skill)
        }
        if (mcp != null) {
            append("\n\n")
            append(mcp)
        }
        if (memory != null) {
            append("\n\n")
            append(memory)
        }
        append(tail)
    }

    private fun legacyStablePrefixLength(base: String, skill: String?, mcp: String?, memory: String?): Int {
        var len = base.length
        if (skill != null) len += 2 + skill.length
        if (mcp != null) len += 2 + mcp.length
        if (memory != null) len += 2 + memory.length
        return len
    }

    // ------------------------------------------------------------------
    // Fragment fixtures exercising the separator shapes
    // ------------------------------------------------------------------

    private val BASE =
        "IDENTITY-SENTENCE\n" +
            "Available tools:\n- shell_execute: run commands\n- file_read: read files\n\n" +
            "Tone and style:\n- Be concise."

    private val SKILL = "## Skills\n- deploy-check: verify release steps"
    private val MCP = "MCP servers (top 20 enabled):\n- github: code search"
    private val MEMORY = "Standing rules (GLOBAL.md):\n- Keep answers in the user's language"
    private val TAIL =
        "\n\nRuntime context:\n" +
            "- Current date: 2026-11-15 (Asia/Shanghai)\n" +
            "- Device language: zh-CN\n" +
            "- minis-model-use models available: 3" +
            "\n\nPersona reminder (SOUL.md): stay in the configured persona for every reply this session."

    private fun sections(
        skill: String? = SKILL,
        mcp: String? = MCP,
        memory: String? = MEMORY,
        base: String = BASE,
        tail: String = TAIL,
    ) = PromptSections.buildSections(
        base = base,
        skillFragment = skill,
        mcpFragment = mcp,
        globalMemoryFragment = memory,
        dynamicTail = tail,
    )

    // ------------------------------------------------------------------
    // Golden 1: join() is byte-identical to the legacy buildString
    // ------------------------------------------------------------------

    @Test
    fun `join is byte-identical to legacy assembly - all fragments present`() {
        val built = PromptSections.join(sections())
        assertEquals(legacyAssembly(BASE, SKILL, MCP, MEMORY, TAIL), built)
    }

    @Test
    fun `join is byte-identical - null fragments leave no separators`() {
        val built = PromptSections.join(sections(skill = null, mcp = null, memory = null))
        // The byte-shape: base flows straight into the tail's own leading separator.
        assertEquals(BASE + TAIL, built)
    }

    @Test
    fun `join is byte-identical - each fragment alone`() {
        for (combo in listOf(
            Triple<String?, String?, String?>(SKILL, null, null),
            Triple<String?, String?, String?>(null, MCP, null),
            Triple<String?, String?, String?>(null, null, MEMORY),
            Triple<String?, String?, String?>(SKILL, null, MEMORY),
            Triple<String?, String?, String?>(null, MCP, MEMORY),
            Triple<String?, String?, String?>(SKILL, MCP, null),
        )) {
            val (skill, mcp, memory) = combo
            val built = PromptSections.join(sections(skill = skill, mcp = mcp, memory = memory))
            val expected = legacyAssembly(BASE, skill, mcp, memory, TAIL)
            assertEquals("combo $combo", expected, built)
        }
    }

    @Test
    fun `join is byte-identical - non-null blank fragment keeps its bare separator`() {
        // A blank-but-nonnull skill fragment appends "\n\n" + "" in the legacy
        // code — a double separator byte the sections view MUST reproduce, not
        // normalize away (skillFragment can be an empty string from upstream
        // fragment builders).
        val built = PromptSections.join(sections(skill = "", mcp = "  ", memory = null))
        assertEquals(legacyAssembly(BASE, "", "  ", null, TAIL), built)
        assertTrue(built.contains(BASE + "\n\n\n\n" + "  " + TAIL))
    }

    @Test
    fun `full-prompt snapshot golden`() {
        // A pinned end-to-end snapshot of the fully-populated assembly.
        val expected =
            "IDENTITY-SENTENCE\n" +
                "Available tools:\n- shell_execute: run commands\n- file_read: read files\n\n" +
                "Tone and style:\n- Be concise.\n\n" +
                "## Skills\n- deploy-check: verify release steps\n\n" +
                "MCP servers (top 20 enabled):\n- github: code search\n\n" +
                "Standing rules (GLOBAL.md):\n- Keep answers in the user's language" +
                "\n\nRuntime context:\n" +
                "- Current date: 2026-11-15 (Asia/Shanghai)\n" +
                "- Device language: zh-CN\n" +
                "- minis-model-use models available: 3" +
                "\n\nPersona reminder (SOUL.md): stay in the configured persona for every reply this session."
        assertEquals(expected, PromptSections.join(sections()))
    }

    // ------------------------------------------------------------------
    // Golden 2: the stable-prefix boundary cannot drift from the assembly
    // ------------------------------------------------------------------

    @Test
    fun `stablePrefixLength equals legacy boundary for every combo`() {
        for (combo in listOf(
            listOf(SKILL, MCP, MEMORY),
            listOf(SKILL, null, MEMORY),
            listOf(null, MCP, null),
            listOf(null, null, null),
            listOf("", MCP, MEMORY),
        )) {
            val skill = combo[0] as String?
            val mcp = combo[1] as String?
            val memory = combo[2] as String?
            val built = sections(skill = skill, mcp = mcp, memory = memory)
            assertEquals(
                "combo $combo",
                legacyStablePrefixLength(BASE, skill, mcp, memory),
                PromptSections.stablePrefixLength(built),
            )
        }
    }

    // ------------------------------------------------------------------
    // diffSections (pi diffSystemPromptSections semantics)
    // ------------------------------------------------------------------

    @Test
    fun `diff returns only changed sections`() {
        val old = sections()
        val newSkills = sections(skill = "## Skills\n- NEW-SKILL")
        val patches = PromptSections.diffSections(old, newSkills)
        assertEquals(listOf(PromptSections.SectionPatch(PromptSections.SKILLS, "\n\n## Skills\n- NEW-SKILL")), patches)
    }

    @Test
    fun `identical sections produce no patch`() {
        val patches = PromptSections.diffSections(sections(), sections())
        assertTrue(patches.isEmpty())
    }

    @Test
    fun `reordering same-content sections produces no patch`() {
        val reordered = sections().reversed()
        val patches = PromptSections.diffSections(sections(), reordered)
        assertTrue("reorder must not diff (diff is name-keyed)", patches.isEmpty())
    }

    @Test
    fun `removed sections are reported with null content`() {
        val patches = PromptSections.diffSections(sections(), sections(memory = null))
        assertEquals(listOf(PromptSections.SectionPatch(PromptSections.MEMORY_STANDING, null)), patches)
    }

    @Test
    fun `added sections are reported as new content`() {
        val patches = PromptSections.diffSections(sections(memory = null), sections())
        assertEquals(listOf(PromptSections.SectionPatch(PromptSections.MEMORY_STANDING, "\n\n$MEMORY")), patches)
    }

    // ------------------------------------------------------------------
    // publishAndDiff (stable-region cache observability)
    // ------------------------------------------------------------------

    @Test
    fun `publishAndDiff ignores runtime tail churn and reports stable changes`() {
        PromptSections.clearPublished()
        // First build: nothing to diff against.
        assertNull(PromptSections.publishAndDiff("sess-a", sections()))
        // Runtime-tail-only change (date / language churn): no signal.
        assertNull(PromptSections.publishAndDiff("sess-a", sections(tail = TAIL + "-date-drift")))
        // Stable change: reported, naming the section.
        val line = PromptSections.publishAndDiff("sess-a", sections(mcp = "MCP servers v2"))
        assertTrue(line!!.contains(PromptSections.MCP))
        // Same state again: silent.
        assertNull(PromptSections.publishAndDiff("sess-a", sections(mcp = "MCP servers v2")))
    }

    @Test
    fun `publishAndDiff reports removed stable sections and is session-scoped`() {
        PromptSections.clearPublished()
        assertNull(PromptSections.publishAndDiff("sess-b", sections()))
        val line = PromptSections.publishAndDiff("sess-b", sections(memory = null))
        assertTrue(line!!.contains("removed"))
        assertTrue(line.contains(PromptSections.MEMORY_STANDING))
        // A different session is independent (no cross-session bleed).
        assertNull(PromptSections.publishAndDiff("sess-c", sections()))
        // Blank session id never publishes.
        assertNull(PromptSections.publishAndDiff("", sections()))
    }

    @Test
    fun `hash-resident snapshot keeps the diff behaviour identical`() {
        // P2-5: the per-session snapshot now stores per-section content hashes
        // instead of the whole section text. The observable contract (which
        // stable sections changed / were removed) must not move — pinned here
        // against the same scenarios the full-content implementation handled.
        PromptSections.clearPublished()
        assertNull(PromptSections.publishAndDiff("sess-h", sections()))
        // Tail-only churn: silent (stable hashes unchanged).
        assertNull(PromptSections.publishAndDiff("sess-h", sections(tail = TAIL + " drift")))
        // Section content change: named.
        val changed = PromptSections.publishAndDiff("sess-h", sections(skill = "## Skills v2"))
        assertTrue(changed!!.contains(PromptSections.SKILLS))
        assertFalse(changed.contains("removed"))
        // Section removal: reported with the removal marker.
        val removed = PromptSections.publishAndDiff("sess-h", sections(skill = "## Skills v2", memory = null))
        assertTrue(removed!!.contains("removed"))
        assertTrue(removed.contains(PromptSections.MEMORY_STANDING))
        // Re-adding the section: named again (the hash map tracks absence).
        val reAdded = PromptSections.publishAndDiff("sess-h", sections(skill = "## Skills v2"))
        assertTrue(reAdded!!.contains(PromptSections.MEMORY_STANDING))
        assertFalse(reAdded.contains("removed"))
        // Same state again: silent.
        assertNull(PromptSections.publishAndDiff("sess-h", sections(skill = "## Skills v2")))
    }

    @Test
    fun `section names map one-to-one onto ContextBlockKey regions`() {
        val built = sections()
        assertEquals(
            listOf(
                PromptSections.SYSTEM_TOOLS,
                PromptSections.SKILLS,
                PromptSections.MCP,
                PromptSections.MEMORY_STANDING,
                PromptSections.RUNTIME_TAIL,
            ),
            built.map { it.name },
        )
        // The SYSTEM_TOOLS section is exactly the legacy `base` variable and the
        // RUNTIME_TAIL section exactly the legacy `dynamicTail` variable.
        assertEquals(BASE, built.first { it.name == PromptSections.SYSTEM_TOOLS }.content)
        assertEquals(TAIL, built.first { it.name == PromptSections.RUNTIME_TAIL }.content)
    }
}
