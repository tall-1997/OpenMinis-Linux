package com.openminis.app.ui.chat

/**
 * [T-download-tool-card] Executor for [ChatViewModel]'s BrowserTabPool
 * onDownloadEvent callback. Unknown wording falls back to the legacy
 * system-info row so nothing is lost; recognized wordings drive a
 * "browser_download" tool card — create on RUNNING, update in place on
 * terminal state, supersede a stuck same-name RUNNING card. UI-only state:
 * never persisted, never touches agentHistory, so the tool_use block cannot
 * orphan into the LLM context.
 */
internal fun ChatViewModel.onBrowserDownloadEventCard(text: String) {
    val event = parseBrowserDownloadEvent(text)
    if (event == null) {
        appendSystemInfo(text, "info")
        return
    }
    val cur = _messages.value
    val running = findRunningDownloadCard(cur, event.fileName)
    if (event.status == ToolBlockStatus.RUNNING || running == null) {
        // New attempt card. A same-name card still RUNNING is superseded by
        // this one — mark it CANCELLED so it doesn't spin forever. A terminal
        // event with no matching RUNNING card (e.g. after a cold restart)
        // still surfaces as a terminal card so the outcome is never dropped.
        val base = if (running != null)
            markDownloadCard(
                cur, running.first, running.second,
                ToolBlockStatus.CANCELLED,
                "Superseded by a new download of ${event.fileName}",
            )
        else cur
        val now = System.currentTimeMillis()
        _messages.value = trimLoadedWindow(
            base + ChatMessage(
                id = "dl_$now",
                role = "system",
                content = "",
                toolBlocks = listOf(downloadCardBlock(event, text, "dl_blk_$now")),
            )
        )
    } else {
        _messages.value = markDownloadCard(cur, running.first, running.second, event.status, text)
    }
}
