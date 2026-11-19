package com.openminis.app.mcp.client

import com.openminis.app.tools.ToolOutputSpill
import com.openminis.app.tools.ToolOutputTruncator

/** Use the shared spill preview, retaining MCP's additional UTF-8 byte ceiling. */
object McpOutputLimit {
    const val MCP_OUTPUT_MAX_BYTES = 20 * 1024
    private const val MEDIA_PREVIEW_CHARS = 1_024

    data class LimitedMcpText(val text: String, val fullOutputPath: String?, val truncated: Boolean)

    /** [saveOutput] receives the whole text and must return a verified, session-readable guest path. */
    fun limit(
        serverName: String,
        toolName: String,
        combinedText: String,
        preservedBlocks: List<String> = emptyList(),
        saveOutput: ((fullText: String) -> String?)? = null,
    ): LimitedMcpText {
        val fullText = if (preservedBlocks.isEmpty()) combinedText else combinedText + "\n" + preservedBlocks.joinToString("\n")
        val bytes = ToolOutputTruncator.utf8ByteLength(fullText)
        if (fullText.length <= ToolOutputSpill.LIMIT && bytes <= MCP_OUTPUT_MAX_BYTES) {
            return LimitedMcpText(fullText, null, false)
        }
        val path = try {
            saveOutput?.invoke(fullText)?.takeIf { ToolOutputSpill.isSpillGuestPath(it) && it == it.trim() }
        } catch (_: Exception) {
            null
        }
        val preview = ToolOutputSpill.formatPreview(
            "mcp '$serverName' tool '$toolName'", path, combinedText,
            originalBytes = bytes.takeIf { preservedBlocks.isEmpty() },
        )
        return LimitedMcpText(appendMediaPreview(preview, preservedBlocks), path, true)
    }

    /** Keep whole media descriptions outside the text cut, with a bounded list. */
    private fun appendMediaPreview(preview: String, preserved: List<String>): String = buildString {
        append(preview)
        var remaining = MEDIA_PREVIEW_CHARS
        var shown = 0
        for (block in preserved) {
            if (block.length >= remaining) break
            append('\n').append(block)
            remaining -= block.length + 1
            shown++
        }
        if (shown < preserved.size) append("\n[").append(preserved.size - shown).append(" more MCP media descriptions omitted]")
    }
}
