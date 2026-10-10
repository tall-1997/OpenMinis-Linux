package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-multi-edit-receipt] multi_edit 回执聚合的验收。
 *
 * 回归的是自相矛盾文案：旧实现透传**最后一条** edit 的 file_edit 输出，
 * 于是两条 edit 的回执写着「Applied 2 edits … (1 replacement(s), 66 bytes)」——
 * 模型据此以为只改了一处，UI（ChatToolDetailUI 取第一个括号组）也跟着显示错数。
 */
class MultiEditReceiptTest {

    private fun edited(replacements: Int, bytes: Int, diff: String? = null) =
        "Edited /p ($replacements replacement(s), $bytes bytes)" + (diff ?: "")

    @Test
    fun `two single edits report two replacements and the final size`() {
        val receipt = MultiEditTool.composeReceipt(
            "/p",
            2,
            listOf(edited(1, 64), edited(1, 66)),
        )
        assertEquals("Applied 2 edits to /p (2 replacement(s), 66 bytes)", receipt)
        // 矛盾文案的特征串：单条计数不得再出现在聚合回执里
        assertFalse(receipt.contains("(1 replacement(s)"))
    }

    @Test
    fun `replace_all counts are summed instead of echoing the last edit`() {
        val receipt = MultiEditTool.composeReceipt(
            "/p",
            2,
            listOf(edited(3, 100), edited(1, 102)),
        )
        assertTrue(receipt.contains("(4 replacement(s), 102 bytes)"))
    }

    @Test
    fun `every edit keeps its own diff section in order`() {
        val receipt = MultiEditTool.composeReceipt(
            "/p",
            2,
            listOf(
                edited(1, 64, "\n[unified-diff]\n-one\n+ONE\n"),
                edited(1, 66, "\n[unified-diff]\n-three\n+THREE\n"),
            ),
        )
        assertTrue(receipt.contains("-one"))
        assertTrue(receipt.contains("-three"))
        assertTrue(receipt.indexOf("-one") < receipt.indexOf("-three"))
        // 两段各带自己的头，模型能分清哪段属于哪条 edit
        assertEquals(2, Regex("\\[unified-diff]").findAll(receipt).count())
    }

    @Test
    fun `unrecognized summary reports no numbers rather than fake ones`() {
        val receipt = MultiEditTool.composeReceipt("/p", 1, listOf("Edited /p — unexpected shape"))
        assertEquals("Applied 1 edits to /p", receipt)
        assertFalse(receipt.contains("replacement(s)"))
    }

    @Test
    fun `concatenated diffs stay bounded with an explicit marker`() {
        val receipt = MultiEditTool.composeReceipt(
            "/p",
            2,
            listOf(
                edited(1, 10, "\n[unified-diff]\n" + "x".repeat(3_000)),
                edited(1, 12, "\n[unified-diff]\n" + "y".repeat(3_000)),
            ),
        )
        assertTrue(receipt.contains("(2 replacement(s), 12 bytes)"))
        assertTrue(
            receipt.contains(
                "truncated at ${EditDiffSection.MAX_DIFF_CHARS} chars across 2 edits",
            ),
        )
        assertTrue(receipt.length < EditDiffSection.MAX_DIFF_CHARS + 300)
    }
}
