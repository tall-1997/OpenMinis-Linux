package com.openminis.app.harness.text

/**
 * Adapted from taixu UnifiedDiffGenerator (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 轻量级 Unified Diff 生成器（无第三方依赖）。
 *
 * 只在 WorkspaceFileAccess.edit 成功后写入 `ToolResult.metadata["diff"]`，
 * 供前端 DiffView 渲染；该文本不会进入发给模型的上下文，避免重复占用 Token。
 *
 * 算法：行级 diff。先剥离公共前后缀，仅对中间差异块做 LCS（差异块过大时退化为
 * 整体删除+插入），再按 3 行上下文合并成标准 hunk。
 */
object UnifiedDiffGenerator {
    private const val CONTEXT_LINES = 3

    /** 中间差异块超过该规模时退化为整体替换，避免 O(n*m) 内存峰值。 */
    private const val MAX_LCS_CELLS = 2_000_000L

    private data class Op(val type: Char, val text: String)

    /** 生成 Unified Diff；内容无变化时返回 null。 */
    fun diff(original: String, updated: String, path: String): String? {
        if (original == updated) return null
        val ops = diffOps(splitLines(original), splitLines(updated))
        if (ops.none { it.type != ' ' }) return null
        return formatUnified(ops, path)
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n').toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        return lines
    }

    private fun diffOps(a: List<String>, b: List<String>): List<Op> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        val maxSuffix = minOf(a.size, b.size) - prefix
        while (suffix < maxSuffix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++

        val ops = ArrayList<Op>(a.size + b.size)
        for (i in 0 until prefix) ops += Op(' ', a[i])
        ops += diffMiddle(a.subList(prefix, a.size - suffix), b.subList(prefix, b.size - suffix))
        for (i in a.size - suffix until a.size) ops += Op(' ', a[i])
        return ops
    }

    private fun diffMiddle(a: List<String>, b: List<String>): List<Op> {
        if (a.isEmpty()) return b.map { Op('+', it) }
        if (b.isEmpty()) return a.map { Op('-', it) }
        if (a.size.toLong() * b.size.toLong() > MAX_LCS_CELLS) {
            return a.map { Op('-', it) } + b.map { Op('+', it) }
        }
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }
        val ops = ArrayList<Op>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { ops += Op(' ', a[i]); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { ops += Op('-', a[i]); i++ }
                else -> { ops += Op('+', b[j]); j++ }
            }
        }
        while (i < n) { ops += Op('-', a[i]); i++ }
        while (j < m) { ops += Op('+', b[j]); j++ }
        return ops
    }

    private fun formatUnified(ops: List<Op>, path: String): String {
        val changed = ops.indices.filter { ops[it].type != ' ' }
        if (changed.isEmpty()) return ""
        val groups = ArrayList<IntRange>()
        var groupStart = changed.first()
        var groupEnd = groupStart
        for (idx in changed.drop(1)) {
            if (idx - groupEnd <= CONTEXT_LINES * 2) {
                groupEnd = idx
            } else {
                groups += groupStart..groupEnd
                groupStart = idx
                groupEnd = idx
            }
        }
        groups += groupStart..groupEnd

        val oldLineAt = IntArray(ops.size + 1)
        val newLineAt = IntArray(ops.size + 1)
        var oldLine = 1
        var newLine = 1
        for (i in ops.indices) {
            oldLineAt[i] = oldLine
            newLineAt[i] = newLine
            when (ops[i].type) {
                ' ' -> { oldLine++; newLine++ }
                '-' -> oldLine++
                '+' -> newLine++
            }
        }
        oldLineAt[ops.size] = oldLine
        newLineAt[ops.size] = newLine

        val sb = StringBuilder()
        sb.append("--- a/").append(path).append('\n')
        sb.append("+++ b/").append(path).append('\n')
        for (group in groups) {
            val from = (group.first - CONTEXT_LINES).coerceAtLeast(0)
            val to = (group.last + CONTEXT_LINES).coerceAtMost(ops.size - 1)
            var oldCount = 0
            var newCount = 0
            for (i in from..to) {
                when (ops[i].type) {
                    ' ' -> { oldCount++; newCount++ }
                    '-' -> oldCount++
                    '+' -> newCount++
                }
            }
            var oldStart = oldLineAt[from]
            var newStart = newLineAt[from]
            if (oldCount == 0) oldStart--
            if (newCount == 0) newStart--
            sb.append("@@ -").append(oldStart).append(',').append(oldCount)
                .append(" +").append(newStart).append(',').append(newCount).append(" @@\n")
            for (i in from..to) {
                sb.append(ops[i].type).append(ops[i].text).append('\n')
            }
        }
        return sb.toString()
    }
}
