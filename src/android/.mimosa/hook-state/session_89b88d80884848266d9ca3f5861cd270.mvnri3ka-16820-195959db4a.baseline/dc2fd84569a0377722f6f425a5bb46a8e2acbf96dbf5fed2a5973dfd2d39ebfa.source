package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

    /**
     * Format the agent history as a plain-text transcript for the
     * summarisation LLM. Keeps role prefixes and truncates long tool arg /
     * output bodies so we stay well under any context window. Mirrors iOS
     * `buildConversationTextForSummary`.
     */
internal fun ChatViewModel.buildConversationTextForSummary(history: List<LLMMessage>): String = buildString {
        for (msg in history) {
            val role = msg.role.name.lowercase()
            val text = ChatViewModel.preserveSummaryEdges(msg.content, 500)
            if (text.isNotEmpty()) {
                append(role).append(": ").append(text).append('\n')
            }
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> {
                        append(role).append(": ").append(ChatViewModel.preserveSummaryEdges(part.text, 500)).append('\n')
                    }
                    is AgentContentPart.ToolUse -> {
                        val preview = ChatViewModel.preserveSummaryEdges(part.input.toString(), 200)
                        append(role).append(" [tool:").append(part.name).append("]: ")
                            .append(preview).append('\n')
                    }
                    is AgentContentPart.ToolResult -> {
                        append(role).append(" [result:").append(part.name).append("]: ")
                            .append(ChatViewModel.preserveSummaryEdges(part.content, 500)).append('\n')
                    }
                    is AgentContentPart.ImageData -> {
                        append(role).append(" [image: ").append(part.mimeType).append("]\n")
                    }
                }
            }
        }
    }

