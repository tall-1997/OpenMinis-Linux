package com.openminis.app.tools

import com.openminis.app.harness.text.UnifiedDiffGenerator

/**
 * [T-unified-diff-wiring] 把 harness 的 [UnifiedDiffGenerator] 接进编辑类工具的
 * 结果：模型拿到的不再只是「Edited N replacement(s)」一句计数，而是有界 unified
 * diff——改错了能立刻看见改错了哪几行，下一轮不必再 file_read 回读确认。
 *
 * 有界是硬要求：一次 replace_all 命中上千行时，diff 本身就能吃掉半个上下文。
 * 超界截断并显式标注，模型知道去看盘而不是信截断后的半张 diff。
 */
object EditDiffSection {

    const val MAX_DIFF_CHARS = 4_000

    /** 无变化或超界无法生成时返回 null（调用方不附加段落）。 */
    fun render(path: String, before: String, after: String): String? {
        if (before == after) return null
        val diff = UnifiedDiffGenerator.diff(before, after, path) ?: return null
        return if (diff.length <= MAX_DIFF_CHARS) {
            "\n[unified-diff]\n$diff"
        } else {
            "\n[unified-diff truncated at $MAX_DIFF_CHARS chars — read the file for the full change]\n" +
                diff.take(MAX_DIFF_CHARS)
        }
    }
}
