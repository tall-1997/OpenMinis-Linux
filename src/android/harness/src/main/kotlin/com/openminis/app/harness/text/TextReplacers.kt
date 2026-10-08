package com.openminis.app.harness.text

/*
 * Adapted from taixu TextReplacers (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 */

/**
 * 分级容错文本替换引擎（对标 RikkaHub `TextReplacers.kt`）。
 *
 * 大模型生成代码修改时经常出现与文件原文的微小偏差：
 * - 缩进多/少一两个空格、制表符与空格混用；
 * - 行首/行尾空白差异；
 * - CRLF 与 LF 换行符混淆。
 *
 * 严格的 `content.indexOf(oldText)` 遇到任何偏差都会失败。本引擎定义了
 * `Exact -> LineTrimmed -> BlockAnchor` 三级降级责任链，在保持"唯一匹配"
 * 安全语义的前提下大幅提升编辑成功率：
 * 1. [ExactReplacer]       严格子串匹配（最快、最准确）；
 * 2. [LineTrimmedReplacer] 行级去空白匹配 + 按真实缩进自动重排 newText；
 * 3. [BlockAnchorReplacer] 仅以首尾行作为锚点，容忍中间行的细微差异（>= 3 行时）。
 */
interface TextReplacer {
    /** 策略名，用于结果元数据与错误提示。 */
    val name: String

    /**
     * 在 [content] 中查找 [oldText] 的全部匹配区间；[newText] 仅用于计算需要
     * 重排缩进时的替换文本。未命中返回空列表。
     */
    fun findMatches(content: String, oldText: String, newText: String): List<Match>

    /**
     * 一个待替换区间。[start, endExclusive) 为在 `content` 中的半开区间，
     * [replacement] 为该区间应被替换成的文本（已按目标缩进/换行风格重排）。
     */
    data class Match(
        val start: Int,
        val endExclusive: Int,
        val replacement: String,
    )
}

/**
 * 文本替换结果；供 `WorkspaceFileAccess.editDetailed` 生成结果提示与 Unified Diff。
 * diff 由 `UnifiedDiffGenerator` 单独生成（需要文件路径作头部），不在此重复持有。
 */
data class ReplaceTextResult(
    val updated: String,
    val replacements: Int,
    val occurrences: Int,
    val strategy: String,
)

/** 文本替换失败（未找到 / 匹配多处）。调用方应把 message 原样回给模型自我纠正。 */
class TextReplacementException(message: String) : IllegalArgumentException(message)

/** 第一级：严格子串匹配。保持最高速度与完全准确性。 */
object ExactReplacer : TextReplacer {
    override val name: String = "exact"

    override fun findMatches(content: String, oldText: String, newText: String): List<TextReplacer.Match> {
        val matches = mutableListOf<TextReplacer.Match>()
        var index = content.indexOf(oldText)
        while (index >= 0) {
            matches += TextReplacer.Match(index, index + oldText.length, newText)
            index = content.indexOf(oldText, index + oldText.length)
        }
        return matches
    }
}

/**
 * 行窗口匹配基类：按行切分后滑动窗口，命中后按目标文件真实缩进对 newText 重排。
 *
 * 尾随空行的处理与 RikkaHub 对齐：模型给出的 oldText 常以换行结尾，切行后会多出
 * 一个空串，需要丢弃；对应的 newText 尾随换行也一并去掉，避免替换区间吞掉分隔换行。
 */
abstract class LineWindowReplacer : TextReplacer {
    protected abstract fun windowMatches(windowTrimmed: List<String>, oldTrimmed: List<String>): Boolean

    protected open fun isApplicable(oldTrimmed: List<String>): Boolean =
        oldTrimmed.any { it.isNotEmpty() }

    override fun findMatches(content: String, oldText: String, newText: String): List<TextReplacer.Match> {
        val rawOldLines = splitNormalized(oldText)
        val dropTrailingEmpty = rawOldLines.size > 1 && rawOldLines.last().isEmpty()
        val oldLines = if (dropTrailingEmpty) rawOldLines.dropLast(1) else rawOldLines
        val oldTrimmed = oldLines.map { it.trim() }
        if (!isApplicable(oldTrimmed)) return emptyList()
        val adjustedNewText = if (dropTrailingEmpty) newText.trimEnd('\n', '\r') else newText

        val contentLines = splitLinesWithOffsets(content)
        if (oldLines.isEmpty() || contentLines.size < oldLines.size) return emptyList()

        val matches = mutableListOf<TextReplacer.Match>()
        var index = 0
        while (index + oldLines.size <= contentLines.size) {
            val window = contentLines.subList(index, index + oldLines.size)
            if (windowMatches(window.map { it.text.trim() }, oldTrimmed)) {
                val usesCrlf = window.any { it.text.endsWith("\r") }
                val replacement = reindent(
                    text = adjustedNewText,
                    oldIndent = indentOf(oldLines.first()),
                    newIndent = indentOf(window.first().text),
                    crlf = usesCrlf,
                )
                matches += TextReplacer.Match(window.first().start, window.last().endExclusive, replacement)
                index += oldLines.size
            } else {
                index++
            }
        }
        return matches
    }

    private data class OffsetLine(val text: String, val start: Int, val endExclusive: Int)

    private fun splitLinesWithOffsets(content: String): List<OffsetLine> {
        val lines = ArrayList<OffsetLine>()
        var start = 0
        var i = 0
        while (i < content.length) {
            if (content[i] == '\n') {
                val lineText = content.substring(start, i)
                // 行文本不含换行符：CRLF 时 endExclusive 也排除 '\r'，
                // 让紧随其后的原始 "\r\n" 保持完整，避免替换后换行风格被改坏。
                val endExclusive = if (lineText.endsWith("\r")) i - 1 else i
                lines += OffsetLine(lineText, start, endExclusive)
                start = i + 1
            }
            i++
        }
        lines += OffsetLine(content.substring(start), start, content.length)
        return lines
    }

    private fun splitNormalized(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    /** 取行首连续空白（空格/制表符）作为缩进。 */
    private fun indentOf(line: String): String {
        val end = line.indexOfFirst { it != ' ' && it != '\t' }
        return if (end < 0) line else line.substring(0, end)
    }

    /**
     * 把 [text] 从 [oldIndent] 基准缩进重排到 [newIndent] 基准缩进：
     * 仅对以 [oldIndent] 开头的行做前缀替换，保留更深层级的相对缩进；空行不动。
     * [crlf] 为 true 时把结果换行统一为 CRLF，避免把文件 CRLF 风格改坏。
     */
    private fun reindent(text: String, oldIndent: String, newIndent: String, crlf: Boolean): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val reindented = if (oldIndent == newIndent) {
            normalized
        } else {
            normalized.split('\n').joinToString("\n") { line ->
                when {
                    line.isBlank() -> line
                    line.startsWith(oldIndent) -> newIndent + line.substring(oldIndent.length)
                    else -> line
                }
            }
        }
        return if (crlf) reindented.replace("\n", "\r\n") else reindented
    }
}

object LineTrimmedReplacer : LineWindowReplacer() {
    override val name: String = "line_trimmed"
    override fun windowMatches(windowTrimmed: List<String>, oldTrimmed: List<String>): Boolean =
        windowTrimmed == oldTrimmed
}

object BlockAnchorReplacer : LineWindowReplacer() {
    override val name: String = "block_anchor"
    override fun isApplicable(oldTrimmed: List<String>): Boolean =
        oldTrimmed.size >= 3 && oldTrimmed.first().isNotEmpty() && oldTrimmed.last().isNotEmpty()

    override fun windowMatches(windowTrimmed: List<String>, oldTrimmed: List<String>): Boolean =
        windowTrimmed.first() == oldTrimmed.first() && windowTrimmed.last() == oldTrimmed.last()
}

/**
 * 责任链执行器：按顺序尝试各级策略，命中唯一匹配后执行替换。
 *
 * 安全性：与旧实现一致，若某一级命中多处，直接拒绝并提示模型提供更精确的上下文，
 * 而不是静默替换全部（对代码文件来说静默多处替换是灾难性的）。
 */
object TextReplacerEngine {
    val defaultChain: List<TextReplacer> = listOf(ExactReplacer, LineTrimmedReplacer, BlockAnchorReplacer)

    fun replace(
        content: String,
        oldText: String,
        newText: String,
        chain: List<TextReplacer> = defaultChain,
    ): ReplaceTextResult {
        require(oldText.isNotEmpty()) { "oldText 不能为空" }
        chain.forEach { replacer ->
            val matches = replacer.findMatches(content, oldText, newText)
            if (matches.isEmpty()) return@forEach
            if (matches.size > 1) {
                throw TextReplacementException(
                    "oldText 在文件中匹配 ${matches.size} 处（策略：${replacer.name}），" +
                        "请提供更精确的上下文或唯一锚点后重试",
                )
            }
            val match = matches.first()
            val updated = content.replaceRange(match.start, match.endExclusive, match.replacement)
            if (updated == content) {
                // 文本在语义上替换成功但结果不变（例如仅缩进差异被规范化后无变化）
                return ReplaceTextResult(updated, 0, 1, replacer.name)
            }
            return ReplaceTextResult(updated, 1, 1, replacer.name)
        }
        throw TextReplacementException(
            "oldText 未找到（已尝试策略：${chain.joinToString("/") { it.name }}）。" +
                "请先用 read 工具确认原文后重试，避免凭记忆猜测代码",
        )
    }
}
