package com.openminis.app.data.repository

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-skill-manifest] End-to-end pin for the pi `disable-model-invocation`
 * frontmatter flag (skills.ts:341): the flag is parsed on import, round-trips
 * through the on-disk SKILL.md (the file is the single source of truth —
 * deliberately not a DB column), and hides the skill from the prompt manifest
 * while bundled skills stay visible.
 *
 * Robolectric because the flag flows through the real repository (SQLite +
 * filesDir); the parse itself is exercised on the same content the agent can
 * produce via `file_write` of a SKILL.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SkillDisableModelInvocationTest {

    private lateinit var repo: SkillRepository

    @Before
    fun setUp() {
        repo = SkillRepository(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `frontmatter flag is parsed, persisted and hides the skill from the manifest`() {
        val imported = repo.importFromContent(
            """
            ---
            name: hidden-skill
            description: only reachable through explicit invocation
            disable-model-invocation: true
            ---

            Body that invoke_skill still returns.
            """.trimIndent(),
        )
        assertNotNull(imported)
        assertTrue(imported!!.disableModelInvocation)

        // writeSkillMd round-trips the flag into the on-disk frontmatter.
        val onDisk = repo.readSkillFile(imported.id, "SKILL.md")
            ?: throw AssertionError("SKILL.md must exist after import")
        assertTrue(onDisk.contains("disable-model-invocation: true"))

        // Disk -> Skill re-derivation (rescanFromDisk shares the loadAll parse).
        val rescanned = repo.rescanFromDisk(imported.id)
        assertNotNull(rescanned)
        assertTrue(rescanned!!.disableModelInvocation)

        // Hidden from the manifest; bundled skills stay visible.
        val manifest = SkillManifest.buildSkillManifest(repo.skills.value)
            ?: throw AssertionError("bundled skills keep the manifest non-null")
        assertFalse(manifest.contains("hidden-skill"))
        assertTrue(manifest.contains("skill-creator"))
    }

    @Test
    fun `flag absent keeps the skill visible in the manifest`() {
        val imported = repo.importFromContent(
            """
            ---
            name: plain-skill
            description: model-discoverable
            ---

            Body.
            """.trimIndent(),
        )
        assertNotNull(imported)
        assertFalse(imported!!.disableModelInvocation)

        val manifest = SkillManifest.buildSkillManifest(repo.skills.value)
            ?: throw AssertionError("manifest must build")
        assertTrue(manifest.contains("<name>plain-skill</name>"))
    }
}
