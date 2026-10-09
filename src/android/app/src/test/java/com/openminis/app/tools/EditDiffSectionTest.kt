package com.openminis.app.tools

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-unified-diff-wiring] 编辑结果附加 diff 的验收：无变化不附加、
 * 正常改动给完整 diff、超大改动截断并显式标注。
 */
class EditDiffSectionTest {

    @Test
    fun `no change means no section`() {
        assertNull(EditDiffSection.render("a.txt", "same\nlines\n", "same\nlines\n"))
    }

    @Test
    fun `small change yields a bounded unified diff`() {
        val section = EditDiffSection.render("a.txt", "one\ntwo\nthree\n", "one\nTWO\nthree\n")
        assertTrue(section != null)
        section!!
        assertTrue(section.contains("[unified-diff]"))
        assertTrue(section.contains("-two"))
        assertTrue(section.contains("+TWO"))
        assertTrue(section.contains("a.txt"))
    }

    @Test
    fun `oversized diffs are truncated with an explicit marker`() {
        val before = (1..4000).joinToString("\n") { "line $it" }
        val after = (1..4000).joinToString("\n") { "changed $it" }
        val section = EditDiffSection.render("big.txt", before, after)!!
        assertTrue(section.contains("truncated at ${EditDiffSection.MAX_DIFF_CHARS} chars"))
        assertTrue(section.length < EditDiffSection.MAX_DIFF_CHARS + 200)
    }
}
