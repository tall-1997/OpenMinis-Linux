package com.openminis.app.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-recovery-layer] 截断方向随工具切换的验收：命令类的报错在尾部要保住尾部，
 * read/search 类的关键信息在头部要保住头部；超长单行先折叠再按行截断。
 */
class ToolOutputSpillRetentionTest {

    /** 200 行 × 60 字符 ≈ 12000 字符；MIDDLE-TOKEN 落在约 5000 字符处。 */
    private fun output(): String {
        val lines = MutableList(200) { i -> "line-$i".padEnd(59, '.') + "\n" }
        lines[83] = "MIDDLE-TOKEN".padEnd(59, '.') + "\n"
        lines[199] = "TAILLINE-END\n"
        return lines.joinToString("")
    }

    @Test
    fun `shell output is tail biased so the error line survives`() {
        val preview = ToolOutputSpill.formatPreview("shell_execute", "/var/minis/workspace/tool-spill/x.txt", output())
        assertTrue(preview.contains("[retention: tail-biased"))
        assertTrue("tail must survive: panic/assert lines live at the end", preview.contains("TAILLINE-END"))
        assertTrue("middle must be cut on a tail-biased head budget of 4000", !preview.contains("MIDDLE-TOKEN"))
    }

    @Test
    fun `read output stays head biased`() {
        val preview = ToolOutputSpill.formatPreview("file_read", "/var/minis/workspace/tool-spill/y.txt", output())
        assertTrue(!preview.contains("[retention: tail-biased"))
        assertTrue("head budget of 6000 must keep the middle token", preview.contains("MIDDLE-TOKEN"))
        assertTrue(preview.contains("TAILLINE-END"))
    }

    @Test
    fun `overlong single lines are folded before line aligned cuts`() {
        val huge = "x".repeat(5000)
        val preview = ToolOutputSpill.formatPreview("shell_execute", "/var/minis/workspace/tool-spill/z.txt", "first\n$huge\nlast")
        assertTrue(preview.contains("单行内容过长已折叠"))
        assertTrue(
            "no line in the preview may exceed the fold budget",
            preview.lines().all { it.length <= 2600 },
        )
    }
}
