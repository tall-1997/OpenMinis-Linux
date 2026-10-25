package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillDiskIndexTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun ignoresDirectoriesWithoutSkillMd() {
        val root = tmp.root
        File(root, "notes").mkdirs()
        File(root, "notes/README.md").writeText("nope")
        assertTrue(SkillDiskIndex.signatures(root).isEmpty())
    }

    @Test
    fun diffReportsAddedRemovedAndChanged() {
        val root = tmp.root
        writeSkill(root, "keep", "one")
        writeSkill(root, "gone", "two")
        val before = SkillDiskIndex.signatures(root)
        File(root, "gone").deleteRecursively()
        writeSkill(root, "keep", "one-edited")
        writeSkill(root, "fresh", "three")
        val after = SkillDiskIndex.signatures(root)
        val delta = SkillDiskIndex.diff(before, after)
        assertEquals(listOf("fresh"), delta.added)
        assertEquals(listOf("gone"), delta.removed)
        assertEquals(listOf("keep"), delta.changed)
        assertFalse(delta.isEmpty)
    }

    @Test
    fun requirementsChangeIsAChangeEvenIfSkillMdIsUntouched() {
        val root = tmp.root
        writeSkill(root, "a", "body")
        val before = SkillDiskIndex.signatures(root)
        File(root, "a/requirements.json").writeText("""{"apt":["curl"]}""")
        val after = SkillDiskIndex.signatures(root)
        assertEquals(listOf("a"), SkillDiskIndex.diff(before, after).changed)
    }

    private fun writeSkill(root: File, id: String, body: String) {
        val dir = File(root, id)
        dir.mkdirs()
        File(dir, "SKILL.md").writeText("---\nname: $id\n---\n$body\n")
    }
}
