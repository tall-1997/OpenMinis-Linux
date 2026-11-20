package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.tools.ToolOutputSpill
import com.openminis.app.tools.ToolOutputTruncator
import com.openminis.app.util.TextClip

/** Request-time folding of old tool text. No persisted history is changed. */
object ToolResultAging {
    const val MARKER = ToolResultProjection.AGING_MARKER
    const val DEFAULT_KEEP_RECENT = ToolResultProjection.DEFAULT_KEEP_RECENT
    const val DEFAULT_MIN_CHARS = 1_200
    const val SPILL_LIMIT_CHARS = ToolOutputSpill.LIMIT

    /** Preserve medium results with no known recovery path. */
    const val UNSPILLED_FOLD_FLOOR_CHARS = 4_000

    private const val HEADLINE_CHARS = 160
    private const val TAIL_CHARS = 160
    private const val ERROR_HEADLINE_CHARS = 400
    private const val TOOL_ARGS_ESTIMATE_CHARS = 200

    data class Plan(
        val messages: List<LLMMessage>,
        val agedCount: Int,
        val savedChars: Long,
        val scannedResults: Int,
    ) {
        val changed: Boolean get() = agedCount > 0
    }

    fun isAged(content: String): Boolean = content.startsWith(MARKER)

    /** Coarse character count; request decisions use the shared token meter instead. */
    fun totalChars(messages: List<LLMMessage>): Long {
        var total = 0L
        for (msg in messages) {
            total += msg.content.length
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> total += part.text.length
                    is AgentContentPart.ToolUse -> total += TOOL_ARGS_ESTIMATE_CHARS
                    is AgentContentPart.ToolResult -> total += part.content.length
                    is AgentContentPart.ImageData -> Unit
                }
            }
        }
        return total
    }

    /**
     * Keep the newest [keepRecent] results' complete rounds. Zero protects no
     * rounds, but errors, media and already-projected results still do not fold.
     * The [verifiedSpillPaths] snapshot comes from the caller; this pass does no I/O.
     */
    fun age(
        messages: List<LLMMessage>,
        keepRecent: Int = DEFAULT_KEEP_RECENT,
        minChars: Int = DEFAULT_MIN_CHARS,
        verifiedSpillPaths: Set<String> = emptySet(),
    ): Plan {
        val protectFrom = ToolResultProjection.protectedSuffixStart(messages, keepRecent)
        var out: MutableList<LLMMessage>? = null
        var agedCount = 0
        var savedChars = 0L
        var scannedResults = 0
        messages.forEachIndexed { messageIndex, message ->
            var parts: MutableList<AgentContentPart>? = null
            message.contentParts.forEachIndexed partsLoop@{ partIndex, part ->
                if (part !is AgentContentPart.ToolResult || ToolResultProjection.isProjected(part.content)) {
                    return@partsLoop
                }
                scannedResults++
                if (messageIndex >= protectFrom || part.content.length <= minChars) return@partsLoop
                val replacement = ToolResultProjection.replacement(
                    result = part,
                    marker = MARKER,
                    headChars = HEADLINE_CHARS,
                    tailChars = TAIL_CHARS,
                    minUnbackedChars = UNSPILLED_FOLD_FLOOR_CHARS,
                    verifiedSpillPaths = verifiedSpillPaths,
                ) ?: return@partsLoop
                val changedParts = parts ?: message.contentParts.toMutableList().also { parts = it }
                changedParts[partIndex] = part.copy(content = replacement)
                agedCount++
                savedChars += part.content.length - replacement.length
            }
            parts?.let {
                val changedMessages = out ?: messages.toMutableList().also { out = it }
                changedMessages[messageIndex] = message.copy(contentParts = it)
            }
        }
        return Plan(out ?: messages, agedCount, savedChars, scannedResults)
    }

    /** Render an example without claiming a recovery path. [age] leaves errors alone. */
    fun placeholder(toolName: String, content: String, isError: Boolean = false): String =
        ToolResultProjection.placeholder(
            toolName = toolName,
            originalChars = content.length,
            body = content,
            marker = MARKER,
            headChars = if (isError) ERROR_HEADLINE_CHARS else HEADLINE_CHARS,
            tailChars = TAIL_CHARS,
            isError = isError,
        )

    fun tailOf(content: String, limit: Int = TAIL_CHARS): String =
        ToolOutputTruncator.sliceTail(collapseWhitespace(content), limit)

    fun headlineOf(content: String, limit: Int = HEADLINE_CHARS): String =
        TextClip.safeEllipsize(collapseWhitespace(content), limit)

    private fun collapseWhitespace(content: String): String {
        val out = StringBuilder(minOf(content.length, 512))
        var space = false
        for (ch in content) {
            if (ch.isWhitespace()) {
                space = out.isNotEmpty()
            } else {
                if (space) out.append(' ')
                out.append(ch)
                space = false
            }
        }
        return out.toString()
    }
}
