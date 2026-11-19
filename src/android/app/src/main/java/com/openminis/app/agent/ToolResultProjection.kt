package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ToolPairing
import com.openminis.app.security.ToolAliases
import com.openminis.app.tools.ToolOutputSpill
import com.openminis.app.tools.ToolOutputTruncator

/** Pure, shared policy for the two request-time tool result projections. */
object ToolResultProjection {
    const val DEFAULT_KEEP_RECENT = 6
    const val AGING_MARKER = "[tool-result aged]"
    const val MICRO_PRUNE_MARKER = "[micro-pruned]"
    private const val LEGACY_MICRO_PRUNE_MARKER = "\n…[micro-pruned "
    private val MCP_MEDIA_DESCRIPTOR = Regex("""(?m)^\[(?:image [^\r\n]+, \d+ b64 chars — not inlined|audio [^\r\n]+|\d+ more MCP media descriptions omitted)\]$""")

    /**
     * First message of the protected suffix (size if [keepRecent] <= 0).
     * Count all result parts, including errors, images and already-projected
     * text, so running one tier cannot move the other tier's cutoff. Extend
     * the cutoff through the owning ToolUse, even across multiple carriers.
     */
    fun protectedSuffixStart(messages: List<LLMMessage>, keepRecent: Int = DEFAULT_KEEP_RECENT): Int {
        if (keepRecent <= 0) return messages.size
        var remaining = keepRecent
        var start = messages.size
        for (index in messages.indices.reversed()) {
            remaining -= messages[index].contentParts.count { it is AgentContentPart.ToolResult }
            if (remaining <= 0) {
                start = index
                break
            }
        }
        if (start == 0 || remaining > 0) return 0

        val pending = HashMap<Pair<String, String>, ArrayDeque<Int>>()
        val roundStarts = IntArray(messages.size) { it }
        for ((index, message) in messages.withIndex()) {
            for (part in message.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> {
                        val id = ToolPairing.key(part.id)
                        if (id.isNotBlank()) pending.getOrPut(id to part.name, ::ArrayDeque).addLast(index)
                    }
                    is AgentContentPart.ToolResult -> {
                        // Same identity and order as request-time pairing repair.
                        val key = ToolPairing.key(part.id) to part.name
                        val owners = pending[key] ?: continue
                        val owner = owners.removeFirst()
                        if (owners.isEmpty()) pending.remove(key)
                        roundStarts[index] = minOf(roundStarts[index], owner)
                    }
                    else -> Unit
                }
            }
        }
        // If one carrier joins multiple rounds, also extend through any
        // earlier owner reached by the extended suffix.
        var index = messages.lastIndex
        while (index >= start) {
            start = minOf(start, roundStarts[index])
            index--
        }
        return start
    }

    fun isProjected(content: String): Boolean = content.startsWith(AGING_MARKER) ||
        content.startsWith(MICRO_PRUNE_MARKER) || content.contains(LEGACY_MICRO_PRUNE_MARKER)

    /** Extract a declared spill reference, not evidence that it is still readable. */
    fun spillPathForRecovery(content: String): String? = ToolOutputSpill.declaredSpillPath(content)

    /**
     * Same extraction keyed on the stored tool name: legacy sub-agent reports
     * wrote the old envelope only the detail view could read. Both pruning
     * tiers must use this so a verifiable old report keeps its link.
     */
    fun spillPathForRecovery(content: String, storedToolName: String): String? =
        ToolOutputSpill.declaredRecoveryPath(content, storedToolName)

    /**
     * Return a strictly shorter body, or null to keep the result verbatim.
     * A caller may supply a spill path it verified in this session; arbitrary
     * filenames in the result cannot become recovery instructions. With no
     * verification, the placeholder describes the omission without a link.
     * This method does not write or read files.
     */
    fun replacement(
        result: AgentContentPart.ToolResult,
        marker: String,
        headChars: Int,
        tailChars: Int,
        minUnbackedChars: Int = 0,
        verifiedSpillPaths: Set<String> = emptySet(),
    ): String? {
        val content = result.content
        if (result.isError || result.imageData != null || result.imageMimeType != null ||
            result.imageLinuxPath != null || isProjected(content) ||
            (ToolAliases.canonical(result.name) == "mcp" && MCP_MEDIA_DESCRIPTOR.containsMatchIn(content))
        ) return null
        val declaredPath = spillPathForRecovery(content, result.name)
        if (declaredPath == null && content.length < minUnbackedChars) return null
        val recoveryPath = declaredPath?.takeIf { it in verifiedSpillPaths }
        val body = ToolOutputSpill.previewBody(content)
        if (body.length.toLong() <= headChars.coerceAtLeast(0).toLong() + tailChars.coerceAtLeast(0)) return null
        return placeholder(result.name, content.length, body, marker, headChars, tailChars, recoveryPath)
            .takeIf { it.length < content.length }
    }

    internal fun placeholder(
        toolName: String,
        originalChars: Int,
        body: String,
        marker: String,
        headChars: Int,
        tailChars: Int,
        verifiedSpillPath: String? = null,
        isError: Boolean = false,
    ): String {
        val (head, tail, omitted) = ToolOutputTruncator.previewSlices(body, headChars, tailChars)
        val label = ToolOutputSpill.previewLabel(toolName)
        return buildString {
            append(marker).append(' ').append(label)
            if (isError) append(" (error)")
            append(" · ").append(originalChars).append(" chars folded to save context\n")
            append(head)
            if (omitted > 0) {
                append("\n… (").append(omitted).append(" chars omitted)")
                if (tail.isNotEmpty()) append(" [tail]\n")
            }
            append(tail)
            if (verifiedSpillPath != null) {
                append("\nfull output: ").append(verifiedSpillPath)
                append(" — page with file_read (offset/lines), or a shell range read for a long line.")
            } else {
                append("\nFull output omitted from this request; no verified recovery path is available.")
                append(" This projection does not change conversation history.")
            }
        }
    }
}
