package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputSpillTest {

    @Test
    fun `preview keeps head and tail and guest path`() {
        val output = "H".repeat(8_000) + "M".repeat(20_000) + "T".repeat(5_000)
        val preview = ToolOutputSpill.formatPreview(
            toolName = "shell_execute",
            guestPath = "/var/minis/workspace/tool-spill/abc.txt",
            output = output,
        )
        assertTrue(preview.contains("tool-output-spill"))
        assertTrue(preview.contains("/var/minis/workspace/tool-spill/abc.txt"))
        assertTrue(preview.contains("file_read"))
        assertTrue(preview.startsWith("[tool-output-spill]"))
        assertTrue(preview.contains("H".repeat(32)))
        assertTrue(preview.contains("T".repeat(32)))
        assertFalse(preview.contains("M".repeat(100)))
        assertTrue(preview.length < output.length)
    }

    @Test
    fun `head only band carries the omitted annotation`() {
        // [T-spill-head-omitted] P2：输出落在 (head, head+tail] 预算内时只显示
        // 头段——此前中段被静默丢掉，模型看不出内容被截。8,000 字符整行输出，
        // HEAD-first（head 6000）与 TAIL-first（head 4000）都要带省略标注。
        val output = ("L".repeat(79) + "\n").repeat(100)
        for (tool in listOf("file_read", "shell_execute")) {
            val preview = ToolOutputSpill.formatPreview(
                toolName = tool,
                guestPath = "/var/minis/workspace/tool-spill/band.txt",
                output = output,
            )
            assertTrue("$tool head-only preview should annotate omission", preview.contains("chars omitted"))
        }
    }

    @Test
    fun `preview fully covered by head keeps no omitted annotation`() {
        // [T-spill-head-omitted] 内容没被截（head == 全文）时不标注，标注只
        // 在真正丢内容时出现。
        val output = ("S".repeat(49) + "\n").repeat(100)
        val preview = ToolOutputSpill.formatPreview(
            toolName = "file_read",
            guestPath = "/var/minis/workspace/tool-spill/short.txt",
            output = output,
        )
        assertFalse(preview.contains("chars omitted"))
    }
}
