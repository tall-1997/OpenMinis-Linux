package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.tools.OcrTool
import com.openminis.app.tools.ScreenTimeTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.WebSearchTool

/**
 * Host-side tools that do not need the chat loop's streaming state.
 * Kept out of [ChatViewModel] so the dispatch when-block does not grow
 * every time a device capability is added.
 */
internal fun dispatchHostTool(
    name: String,
    argsJson: String,
    sessionId: String?,
    context: Context?,
): ToolExecutionResult? = when (name) {
    WebSearchTool.NAME -> WebSearchTool.execute(argsJson, context)
    OcrTool.NAME -> OcrTool.execute(argsJson, sessionId, context)
    ScreenTimeTool.NAME -> ScreenTimeTool.execute(argsJson, context)
    else -> null
}

/** Latest user turn plus a short tail, so a keyword said one turn ago still hits. */
internal fun ChatViewModel.recentWorldBookText(): String {
    val tail = _messages.value.takeLast(6).joinToString("\n") { it.content.orEmpty() }
    return tail.takeLast(4_000)
}
