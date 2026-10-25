package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger

/**
 * Single-shot LLM call that turns [conversationText] into a structured
 * summary. Throws on provider error so the splitter above can detect
 * context-too-large failures and retry with halved input.
 */
internal suspend fun ChatViewModel.generateCompactSummary(conversationText: String): String {
    // Wrap the transcript in explicit BEGIN/END framing so the model
    // treats it as material to summarize rather than as a chat turn to
    // continue. Mirrors iOS AIChatViewModel+Compaction.swift
    // `compactUserMessage` construction. Without this wrapper, fast models
    // (e.g. deepseek-v4-flash) tend to "answer" whatever the last user
    // turn in the transcript said — producing a single-line continuation
    // instead of a structured summary.
    val userMessage = buildString {
        append("Compact this conversation into a context summary:\n\n")
        append(conversationText)
        append("\n\n---\nEND OF CONVERSATION TO COMPACT.\n\n")
        append(
            "Now generate a structured context summary following the system prompt " +
                "instructions. Do NOT continue the conversation above — summarize it. " +
                "Write everything in past tense, framed as \"what was discussed / what " +
                "was done\", NOT as an ongoing goal or todo list."
        )
    }
    val attempt = compactLeafAttempt ?: compactStagePlan().firstOrNull()
        ?: throw IllegalStateException("No LLM provider available for compaction")
    AppLogger.info(ChatViewModel.TAG, "[Compact] leaf via ${attempt.label}")
    val text = attempt.provider.sendMessage(
        messages = listOf(
            LLMMessage(role = LLMMessage.Role.USER, content = userMessage)
        ),
        systemPrompt = compactSummarySystemPrompt,
        maxTokens = attempt.maxOutFor(userMessage),
        temperature = attempt.temperature,
        imageParts = emptyList(),
        tools = emptyList(),
        thinkingLevel = ThinkingLevel.OFF,
    ).text
    if (text.isBlank()) throw IllegalStateException("empty compact summary from ${attempt.label}")
    return text
}

/**
 * System prompt for the single-shot summarisation call. Matches iOS
 * wording so cross-device summaries stay stylistically aligned.
 */
private val compactSummarySystemPrompt: String = """
    You are a context compaction engine. Your summary will REPLACE the original messages in the conversation context window. The agent will read your summary as past context, then proceed based on the user's NEXT message — your summary is background, not a standing work order. Write the summary in the same language the user used in the conversation.

    MUST PRESERVE (never omit or shorten):
    - All file paths, directory names, URLs, UUIDs, and identifiers — copy verbatim
    - Commands executed and their outcomes (success/failure/output)
    - What was requested and what was done (record as past events, not as ongoing goals)
    - Key decisions made and their rationale
    - Errors encountered and how they were resolved
    - Important constraints, rules, or user preferences mentioned
    - Any tool calls and their results that affect current state

    STRUCTURE:
    1. Start with a one-line description of what the conversation was about (use past tense — "User asked X, agent did Y", NOT "Goal: X").
    2. Then a concise narrative of what happened, preserving technical details.
    3. End with a "What had been done so far" section listing completed work — NOT a "todo" or "pending" list. Do not invent ongoing objectives or carry-over tasks from old turns; if the user wants to continue, they will say so in their next message.

    PRIORITIZE recent context over older history — recent decisions and recent file/path references are most useful for continuity.

    Do NOT translate or alter code snippets, file paths, identifiers, or error messages. Be concise but never lose information the agent needs.
""".trimIndent()
