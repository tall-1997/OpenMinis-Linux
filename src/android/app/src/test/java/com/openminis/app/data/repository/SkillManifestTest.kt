package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-skill-manifest] pi coding-agent skills.ts:358-388 progressive skill
 * disclosure, ported. Pins the three contracts the prompt depends on:
 * the manifest format (pi XML + full entity escaping), the
 * `disable-model-invocation` exclusion (hidden skills stay reachable only via
 * invoke_skill), and the `skillsProgressiveLoading` switch (OFF must fall back
 * to the legacy one-line fragment, never to nothing).
 *
 * Pure JVM: [SkillRepository.Skill] is a static-nested data class, so no
 * Context or database is involved here.
 */
class SkillManifestTest {

    private fun skill(
        id: String,
        name: String = id,
        description: String = "desc of $id",
        bundled: Boolean = false,
        updatedAt: Long = 0,
        useCount: Double = 0.0,
        hidden: Boolean = false,
    ): SkillRepository.Skill = SkillRepository.Skill(
        id = id,
        name = name,
        description = description,
        importSource = if (bundled) {
            SkillRepository.ImportSource.BUNDLED
        } else {
            SkillRepository.ImportSource.FILE
        },
        updatedAt = updatedAt,
        useCount = useCount,
        disableModelInvocation = hidden,
    )

    // -- Manifest format (pi skills.ts:358-397) --

    @Test
    fun `manifest renders name description location with full xml escaping`() {
        val manifest = SkillManifest.buildSkillManifest(
            listOf(
                skill(
                    "esc",
                    name = "a & b <c> \"d\" 'e'",
                    description = "x & <y> \"z\"",
                ),
            ),
        ) ?: throw AssertionError("manifest must build for a visible skill")

        assertTrue(manifest.contains("<available_skills>"))
        assertTrue(manifest.contains("</available_skills>"))
        // pi skills.ts:390 escapes all five entities; the name carries all of them.
        assertTrue(manifest.contains("<name>a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos;</name>"))
        assertTrue(manifest.contains("<description>x &amp; &lt;y&gt; &quot;z&quot;</description>"))
        // Default location is the canonical guest-side SKILL.md path.
        assertTrue(manifest.contains("<location>/var/minis/skills/esc/SKILL.md</location>"))
        // The on-demand channel: the header names the read tool.
        assertTrue(
            manifest.contains(
                "Use the file_read tool to load a skill's file when the task matches its description.",
            ),
        )
    }

    @Test
    fun `manifest never emits raw markup from skill metadata`() {
        val manifest = SkillManifest.buildSkillManifest(
            listOf(skill("inj", name = "</available_skills><evil>", description = "<script>")),
        ) ?: throw AssertionError("manifest must build")
        // The only closing tags in the output are the manifest's own.
        assertEquals(1, Regex("</available_skills>").findAll(manifest).count())
        assertFalse(manifest.contains("<evil>"))
        assertFalse(manifest.contains("<script>"))
    }

    @Test
    fun `description is collapsed and capped at 200 chars`() {
        val capped = SkillManifest.clampDescription("a".repeat(250) + "\nsecond line")
        assertEquals(201, capped.length) // 200 kept + the ellipsis
        assertTrue(capped.endsWith("…"))

        val collapsed = SkillManifest.clampDescription("line1\r\nline2")
        // Legacy-faithful (SkillRepository.skillPromptFragment line-for-line):
        // '\n' and '\r' EACH become one space, so CRLF collapses to TWO spaces.
        // The manifest must render byte-identical text to the legacy fragment
        // for the same skill (clampDescription contract; the OFF fallback of
        // skillsProgressiveLoading is byte-for-byte).
        assertEquals("line1  line2", collapsed)
    }

    // -- disable-model-invocation (pi skills.ts:359) --

    @Test
    fun `disable-model-invocation skills are excluded from the manifest`() {
        val manifest = SkillManifest.buildSkillManifest(
            listOf(skill("visible"), skill("secret", hidden = true)),
        ) ?: throw AssertionError("manifest must build when a visible skill exists")
        assertTrue(manifest.contains("<name>visible</name>"))
        assertFalse(manifest.contains("secret"))
    }

    @Test
    fun `manifest is null when the list is empty or every skill is hidden`() {
        assertNull(SkillManifest.buildSkillManifest(emptyList()))
        assertNull(SkillManifest.buildSkillManifest(listOf(skill("s", hidden = true))))
    }

    // -- Shared selection (moved verbatim from SkillRepository) --

    @Test
    fun `selection caps and prioritizes bundled then recent then useCount`() {
        // A realistic clock: with now = 1_000_000 the 7-day cutoff goes
        // NEGATIVE and updatedAt=0 skills would fall INSIDE the recency
        // window (the implementation's `updatedAt > cutoff` is verbatim from
        // SkillRepository and correct for real clocks). The zero-updated
        // skills below must sit OUTSIDE the window so priorities 2/3 split.
        val now = 1_700_000_000_000L
        val bundled = skill("b", bundled = true, updatedAt = 0)
        val recent = skill("r", updatedAt = now)
        val highUse = skill("u", useCount = 99.0, updatedAt = 0)
        val filler = skill("f", useCount = 1.0, updatedAt = 0)

        val selection = SkillManifest.selectForPrompt(
            listOf(filler, highUse, recent, bundled),
            maxSkills = 3,
            nowMs = now,
        )

        assertEquals(listOf("b", "r", "u"), selection.selected.map { it.id })
        assertEquals(listOf("f"), selection.omitted.map { it.id })
    }

    @Test
    fun `selection under the cap orders by updatedAt and omits nothing`() {
        val older = skill("a", updatedAt = 5)
        val newer = skill("b", updatedAt = 9)

        val selection = SkillManifest.selectForPrompt(listOf(older, newer))

        assertEquals(listOf("b", "a"), selection.selected.map { it.id })
        assertTrue(selection.omitted.isEmpty())
    }

    @Test
    fun `disclosure note is byte-identical to the legacy tail`() {
        val selected = listOf(skill("a"))
        val omitted = listOf(skill("b"), skill("c"))

        assertEquals(
            "\n\n2 more skills not shown above: b, c. List /var/minis/skills/ or grep to search all.",
            SkillManifest.disclosureNote(selected, omitted),
        )
        assertNull(SkillManifest.disclosureNote(selected, emptyList()))
    }

    // -- The skillsProgressiveLoading switch --

    @Test
    fun `switch OFF falls back to the legacy fragment and never builds the manifest`() {
        var manifestCalls = 0
        var legacyCalls = 0
        val fragment = SkillManifest.resolvePromptFragment(
            progressiveLoading = false,
            manifest = { manifestCalls++; "xml-manifest" },
            legacy = { legacyCalls++; "legacy-lines" },
        )
        assertEquals("legacy-lines", fragment)
        assertEquals(0, manifestCalls)
        assertEquals(1, legacyCalls)
    }

    @Test
    fun `switch ON builds the manifest and skips the legacy fragment`() {
        var manifestCalls = 0
        var legacyCalls = 0
        val fragment = SkillManifest.resolvePromptFragment(
            progressiveLoading = true,
            manifest = { manifestCalls++; "xml-manifest" },
            legacy = { legacyCalls++; "legacy-lines" },
        )
        assertEquals("xml-manifest", fragment)
        assertEquals(1, manifestCalls)
        assertEquals(0, legacyCalls)
    }

    @Test
    fun `switch OFF propagates the legacy fragment's null instead of masking it`() {
        // The legacy fragment legitimately returns null (no enabled skills).
        // A fallback that coerced null to "" would inject a stray separator.
        assertNull(
            SkillManifest.resolvePromptFragment(
                progressiveLoading = false,
                manifest = { "xml-manifest" },
                legacy = { null },
            ),
        )
    }
}
