package com.openminis.app.harness.subagent

/**
 * Adapted from taixu SubagentOrchestrator.normalizeWritePath (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 从 orchestrator 里**抽出来单放**：完成主张裁定（SubagentClaim）只依赖这一个
 * 纯函数，上游却把它埋在 800+ 行的 SubagentOrchestrator 里，于是「想接裁定」
 * 被迫拖入整个编排器（闭包分析里 2.3 的 41x 就是这么来的）。抽出后编排器与
 * 裁定器各自引用本文件，依赖方向反过来成立。
 *
 * 语义：反斜杠归一为正斜杠、按段折叠 `.` 与 `..`；逃出根部的 `..` 保留为
 * `../` 前缀（调用方据此判「越界」而不是静默吞掉）。
 */
fun normalizeWritePath(path: String): String {
    val segments = ArrayDeque<String>()
    var escaped = 0
    path.trim().replace('\\', '/').split('/').forEach { segment ->
        when {
            segment.isEmpty() || segment == "." -> Unit
            segment == ".." -> if (segments.isEmpty()) escaped++ else segments.removeLast()
            else -> segments.addLast(segment)
        }
    }
    return "../".repeat(escaped) + segments.joinToString("/")
}
