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

/**
 * [T-p2-writelease-relative-scope] guest 工作区根。租约两侧（声明的 scopes 与
 * 工具调用的 target）都以它解析相对路径——不解析的话，模型按拒绝文案改用相对
 * 路径反而永远对不上绝对 scope（「照文案改反而永久写拒绝」的根因之一）。
 * 纯字符串常量，不破坏 ：harness 的 JVM 纯度。
 */
const val WORKSPACE_ROOT = "/var/minis/workspace"

/**
 * 相对路径按工作区根解析；绝对路径原样归一。`../` 逃逸与整工作区哨兵（`*`）
 * 保留原语义交调用方判。
 *
 * [T-p2-writelease-relative-scope] 「绝对」按**输入**是否以 `/` 开头判定，而不是
 * 归一化结果——normalizeWritePath 对绝对路径本就不保留前导斜杠（"/var/x" →
 * "var/x"），按结果判定会让绝对 scope 被二次加前缀（实测
 * "var/minis/workspace/var/minis/workspace/docs"）。
 */
fun resolveAgainstWorkspaceRoot(path: String): String {
    val trimmed = path.trim().replace('\\', '/')
    val normalized = normalizeWritePath(trimmed)
    return when {
        trimmed.startsWith("/") || normalized.startsWith("../") -> normalized
        normalized.isBlank() || normalized == "*" -> normalized
        else -> normalizeWritePath("$WORKSPACE_ROOT/$normalized")
    }
}
