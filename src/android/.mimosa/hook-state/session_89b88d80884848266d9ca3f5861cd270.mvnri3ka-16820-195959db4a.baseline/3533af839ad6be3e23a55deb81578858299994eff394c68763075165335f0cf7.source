package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMUsage

@Deprecated("Use persistAssistantTurn(parts, ...) for per-turn delta persistence")
internal suspend fun ChatViewModel.persistAssistantMessage(
    text: String,
    usage: LLMUsage?,
    toolBlocks: List<AssistantBlock>? = null,
    toolCallInputs: Map<String, String> = emptyMap()
    ,
    reasoningContent: String? = null,
) {
    if (text.isEmpty() && (toolBlocks == null || toolBlocks.isEmpty())) return

    val partsJson = buildString {
        append("[")
        var first = true
        if (text.isNotEmpty()) {
            append("""{"type":"text","value":${escapeJson(text)}}""")
            first = false
        }
        toolBlocks?.forEach { block ->
            // Only persist real tool-use blocks. text / thinking / info blocks are
            // either represented via the `text` parameter (accumulatedText) or
            // reconstructed from thinking metadata; persisting them as `toolUse`
            // produces empty-name records that Anthropic rejects with
            // "messages.N.content.M.tool_use.name: String should have at least 1 character".
            if (block.kind != "tool_use") return@forEach
            if (block.toolName.isBlank()) return@forEach  // extra safety
            if (!first) append(",")
            first = false
            val inputJson = toolCallInputs[block.id]?.let { escapeJson(it) } ?: "\"\""
            val pUrl = block.browserURL ?: ""
            val iPath = block.imageFilePath ?: ""
            append("""{"type":"toolUse","value":{"toolUseId":${escapeJson(block.id)},"name":${escapeJson(block.toolName)},"input":$inputJson,"description":${escapeJson(block.toolTitle)},"pageURL":${escapeJson(pUrl)},"imageFilePath":${escapeJson(iPath)},"thoughtSignature":null}}""")
        }
        append("]")
    }
    val tokenJson = usage?.let {
        """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens}}"""
    }
    chatRepository.appendMessage(
        realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
        reasoningContent = reasoningContent,
        modelSnapshot = currentModelSnapshot(),
    )
}
