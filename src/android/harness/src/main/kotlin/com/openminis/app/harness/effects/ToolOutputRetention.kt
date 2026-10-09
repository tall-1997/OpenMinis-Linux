package com.openminis.app.harness.effects

/**
 * Adapted from taixu ToolOutputRetention (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 */

/** 工具输出超限时的保留方向（对标 Pi `ToolDefinition.retention`）。 */
enum class OutputRetention { HEAD, TAIL }

/**
 * 差异化截断策略：不同工具的关键信息位置不同，截断方向必须随工具切换。
 *
 * - [OutputRetention.TAIL]：命令执行 / 构建 / 进程日志。panic、断言失败、编译错误
 *   永远出现在末尾，保留尾部才能让模型直击报错核心，而不是被前面的下载进度刷屏。
 * - [OutputRetention.HEAD]：read / search 等，关键信息通常在最前面（文件头、命中项）。
 */
object ToolOutputRetention {
    fun forTool(toolName: String?): OutputRetention = when (toolName?.trim()?.lowercase()) {
        "base", "process", "build_script" -> OutputRetention.TAIL
        else -> OutputRetention.HEAD
    }
}

/**
 * 头部截断并对齐完整行：优先在字符预算内最后一个换行处切分，避免把一行切成两半；
 * 单行超过预算时退化为硬截断（无法按行对齐）。
 */
fun keepHeadWholeLines(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.lastIndexOf('\n', maxChars).let { if (it <= 0) maxChars else it }
    return text.substring(0, cut)
}

/** 尾部截断并对齐完整行：从末尾往前取整行，保证不切在行中。 */
fun keepTailWholeLines(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val from = (text.length - maxChars).coerceAtLeast(0)
    val newline = text.indexOf('\n', from)
    val cut = if (newline < 0) text.length - maxChars else newline + 1
    return text.substring(cut.coerceIn(0, text.length))
}

/**
 * 折叠超长单行（混淆/压缩/最小化文件一整行可达数十万字符）：
 * 按行截断策略对单行会退化为保留 60k 字符的整行，一条 tool result 就能顶爆单条消息
 * 的传输上限（中转 Connection reset），且对模型几乎没有可读信息。
 * 折叠为「头部 + 提示 + 尾部」并保证结果行不超预算，提示模型用 grep -o 提取片段。
 * 返回值保证不包含超过 [maxLineChars] 的行，后续按行截断不再退化为硬切。
 */
fun foldOverlongLines(text: String, maxLineChars: Int = 2000): String {
    if (text.lines().none { it.length > maxLineChars }) return text
    val head = maxLineChars / 2
    val tail = maxLineChars / 4
    return text.lines().joinToString("\n") { line ->
        if (line.length <= maxLineChars) {
            line
        } else {
            buildString {
                append(line, 0, head)
                append("\n[单行内容过长已折叠：本行共 ")
                append(line.length)
                append(" 字符，疑似压缩/混淆/最小化文件，整行可读性极低。")
                append("如需定位内容请用 grep -o 提取匹配片段、sed -n 按行号或字节段查看，不要原样输出整行。]\n")
                append(line.takeLast(tail))
            }
        }
    }
}
