package com.openminis.app.mcp.client

import com.openminis.app.tools.ToolOutputTruncator

/**
 * [T-mcp-output-limit] Port of pi `mcp/src/output-limit.ts`: the size limit of
 * the model-facing text of MCP results. Longer text keeps its start and end
 * with the middle cut out, and the caller saves the full text where the model
 * can read it back (a session-workspace spill file, resolved through
 * [com.openminis.app.tools.ToolOutputSpill] so this is one mechanism, not two).
 *
 * Why the preview starts with the `[tool-output-spill]` prefix: every tool
 * result in this app passes through the shared oversize gate
 * (`ToolOutputSpill.maybeSpill`) downstream. A bounded MCP preview that already
 * carries the offload prefix is recognized by `isOffloadPlaceholder` and passed
 * through untouched — the MCP layer stays the single writer of the spill file,
 * and the UI's spill-link affordance (`parseGuestPath`) keeps working.
 */
object McpOutputLimit {

    /**
     * Model-facing text of an MCP result beyond this (UTF-8 bytes) is cut in
     * the middle. Value from pi `mcp/src/output-limit.ts`
     * (`MCP_OUTPUT_MAX_BYTES = 20 * 1024`).
     */
    const val MCP_OUTPUT_MAX_BYTES = 20 * 1024

    /** Result of bounding one `tools/call` text (pi `LimitedMcpContent`). */
    data class LimitedMcpText(
        val text: String,
        /** Guest path of the file holding the full text; null when not saved. */
        val fullOutputPath: String?,
        val truncated: Boolean,
    )

    /**
     * Keep [combinedText] within [MCP_OUTPUT_MAX_BYTES]. Content within the
     * limit is returned as it is ([preservedBlocks] still follow it — pi keeps
     * image blocks outside the cut). Over-limit text becomes one middle-cut
     * text block with a warning line (token estimate = bytes/4) and the total
     * line count, followed by the path of the file with the full text when
     * [saveOutput] is given and succeeds.
     *
     * [saveOutput] receives the FULL text and returns the guest path the model
     * can read; it runs only when truncation is needed (pi calls `saveOutput`
     * on the combined text only past the limit). It must return a non-empty
     * path or throw — a failed save makes the preview say so instead of
     * pointing at a nonexistent file (pi's `[Could not save the full output: …]`).
     */
    fun limit(
        serverName: String,
        toolName: String,
        combinedText: String,
        preservedBlocks: List<String> = emptyList(),
        saveOutput: ((fullText: String) -> String)? = null,
    ): LimitedMcpText {
        val truncation = ToolOutputTruncator.truncateMiddle(combinedText, MCP_OUTPUT_MAX_BYTES)
            ?: return LimitedMcpText(
                text = withPreserved(combinedText, preservedBlocks),
                fullOutputPath = null,
                truncated = false,
            )

        val tokens = (truncation.totalBytes + 3) / 4

        // Resolve the save outcome FIRST so the preview is assembled from pure
        // values (no mutable state): saved path, no target wired, or failure.
        var savedPath: String? = null
        var saveError: String? = null
        if (saveOutput != null) {
            try {
                savedPath = saveOutput(combinedText)
                if (savedPath.isNullOrBlank()) {
                    savedPath = null
                    saveError = "the output saver returned no path"
                }
            } catch (error: Exception) {
                saveError = error.message?.takeIf { it.isNotBlank() }
                    ?: error.javaClass.simpleName
            }
        }

        val text = buildString {
            append("[tool-output-spill] mcp '").append(serverName)
            append("' tool '").append(toolName)
            append("' produced ").append(truncation.totalBytes).append(" bytes.\n")
            append("Warning: truncated output (original token count: $tokens)\n")
            append("Total output lines: ${truncation.totalLines}\n")
            val path = savedPath
            if (path != null) {
                append("Full output saved to $path — use file_read if you need more.\n")
                append("Open in app: minis://workspace/tool-spill/${path.substringAfterLast('/')}\n")
            } else {
                append(
                    "Full output could not be saved to the session workspace, " +
                        "so only this bounded preview is available.\n",
                )
            }
            append('\n')
            append(truncation.content)
            if (path != null) {
                append("\n\n[Full output: $path (read it with offset/limit)]")
            } else if (saveError != null) {
                append("\n\n[Could not save the full output: $saveError]")
            }
        }

        return LimitedMcpText(
            text = withPreserved(text, preservedBlocks),
            fullOutputPath = savedPath,
            truncated = true,
        )
    }

    /** pi keeps image blocks after the (possibly truncated) text block. */
    private fun withPreserved(text: String, preserved: List<String>): String {
        if (preserved.isEmpty()) return text
        return text + "\n" + preserved.joinToString("\n")
    }
}
