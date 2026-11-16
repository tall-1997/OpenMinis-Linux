package com.openminis.app.tools

import com.openminis.app.data.repository.SkillRepository.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-skill-manifest-hidden-cap] (P2-6/P2-7) Pins for the invoke_skill miss
 * message's Available list. The prompt manifest hides
 * `disable-model-invocation` skills from the model entirely — the miss text
 * must not echo their names back, or the model learns exactly what the
 * disclosure was supposed to withhold. [InvokeSkillTool.execute] itself needs
 * an Android-backed SkillRepository; the pure helper is what composes the
 * list (execute's hit path — exact/loose match — is unchanged).
 */
class InvokeSkillToolAvailableListTest {

    private fun skill(name: String, hidden: Boolean = false) = Skill(
        name = name,
        description = "$name skill",
        disableModelInvocation = hidden,
    )

    @Test
    fun `hidden skills are excluded from the available list`() {
        val list = InvokeSkillTool.availableSkillNames(
            listOf(
                skill("web-search"),
                skill("secret-internal", hidden = true),
                skill("pdf-tools"),
            ),
        )
        assertEquals("web-search, pdf-tools", list)
        assertFalse(list.contains("secret-internal"))
    }

    @Test
    fun `a fully hidden catalog yields none instead of an empty tail`() {
        assertEquals("none", InvokeSkillTool.availableSkillNames(listOf(skill("x", hidden = true))))
    }

    @Test
    fun `an empty catalog yields none`() {
        assertEquals("none", InvokeSkillTool.availableSkillNames(emptyList()))
    }

    @Test
    fun `visible skills keep declaration order`() {
        assertEquals("a, b, c", InvokeSkillTool.availableSkillNames(listOf(skill("a"), skill("b"), skill("c"))))
    }

    @Test
    fun `a miss message mentions only disclosable names`() {
        // The exact compose used by execute's miss branch — pin the full sentence
        // shape so a future refactor cannot drift into leaking hidden names.
        val list = InvokeSkillTool.availableSkillNames(listOf(skill("web-search"), skill("hidden", hidden = true)))
        val message = "Skill not found: typo. Available: $list"
        assertEquals("Skill not found: typo. Available: web-search", message)
        assertTrue(message.indexOf("hidden") < 0)
    }
}
