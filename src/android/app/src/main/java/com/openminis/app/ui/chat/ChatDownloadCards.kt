package com.openminis.app.ui.chat

/**
 * [T-download-tool-card] Browser download events surface as tool cards
 * (toolName [DOWNLOAD_TOOL_NAME]) instead of divider-style system-info rows:
 * one RUNNING card per download attempt, updated in place to its terminal
 * state. Pure helpers here — parsing, card construction, card lookup and
 * card mutation — so the whole pairing logic is JVM-testable. The stateful
 * executor lives in ChatViewModelDownloadExt.
 */
internal const val DOWNLOAD_TOOL_NAME = "browser_download"

internal data class BrowserDownloadEvent(
    val status: ToolBlockStatus,
    val fileName: String,
    val detail: String,
)

/**
 * Parse the three BrowserTabPool [BrowserTabPool.onDownloadEvent] wordings
 * ("Downloading X…", "Downloaded X (size)", "Download failed: X — reason").
 * The size tail uses lastIndexOf so file names containing " (" survive; the
 * failure reason uses indexOf since the name comes first. Unknown wording
 * returns null — the caller falls back to the legacy info row.
 */
internal fun parseBrowserDownloadEvent(text: String): BrowserDownloadEvent? {
    val t = text.trim()
    if (t.startsWith("Downloading ") && t.endsWith("…")) {
        val name = t.removePrefix("Downloading ").removeSuffix("…")
        if (name.isBlank()) return null
        return BrowserDownloadEvent(ToolBlockStatus.RUNNING, name, "")
    }
    if (t.startsWith("Downloaded ") && t.endsWith(")")) {
        val cut = t.lastIndexOf(" (")
        if (cut <= "Downloaded ".length) return null
        val name = t.substring("Downloaded ".length, cut)
        val size = t.substring(cut + 2, t.length - 1)
        if (name.isBlank()) return null
        return BrowserDownloadEvent(ToolBlockStatus.SUCCESS, name, size)
    }
    if (t.startsWith("Download failed: ")) {
        val cut = t.indexOf(" — ")
        if (cut < 0) return null
        val name = t.substring("Download failed: ".length, cut)
        val msg = t.substring(cut + 3)
        if (name.isBlank()) return null
        return BrowserDownloadEvent(ToolBlockStatus.FAILED, name, msg)
    }
    return null
}

internal fun downloadCardBlock(event: BrowserDownloadEvent, rawText: String, blockId: String): AssistantBlock =
    AssistantBlock(
        id = blockId,
        kind = "tool_use",
        content = rawText,
        toolStatus = event.status,
        toolName = DOWNLOAD_TOOL_NAME,
    )

/**
 * Newest card (messageId, blockId) for [fileName] still RUNNING, scanning
 * tail-first. Terminal events update the card they belong to; events that
 * find no RUNNING card create a fresh terminal card instead.
 */
internal fun findRunningDownloadCard(messages: List<ChatMessage>, fileName: String): Pair<String, String>? {
    for (m in messages.asReversed()) {
        for (b in m.toolBlocks.asReversed()) {
            if (b.kind == "tool_use" && b.toolName == DOWNLOAD_TOOL_NAME &&
                b.toolStatus == ToolBlockStatus.RUNNING &&
                parseBrowserDownloadEvent(b.content)?.fileName == fileName
            ) {
                return m.id to b.id
            }
        }
    }
    return null
}

/** Copy-on-write status/content update of one download card block. */
internal fun markDownloadCard(
    messages: List<ChatMessage>,
    messageId: String,
    blockId: String,
    status: ToolBlockStatus,
    content: String,
): List<ChatMessage> =
    messages.map { m ->
        if (m.id != messageId) m
        else m.copy(toolBlocks = m.toolBlocks.map { b ->
            if (b.id == blockId) b.copy(toolStatus = status, content = content) else b
        })
    }
