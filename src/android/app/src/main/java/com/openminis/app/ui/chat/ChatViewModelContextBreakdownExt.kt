package com.openminis.app.ui.chat

import com.openminis.app.data.ContextTokenEstimate
import com.openminis.app.data.ContextTokenMeter
import com.openminis.app.data.model.ContextUsage
import com.openminis.app.provider.api.ChatRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The per-block composition uses the shared raw ruler. The context total and
// output allowance use the projected request and can have a measured prefix.

/** Semantic blocks of the next request payload, in the order the UI shows them. */
internal enum class ContextBlockKey {
    /** Identity, tool catalogue and guidance — the stable head of the system prompt. */
    SYSTEM_TOOLS,

    /** Skill + MCP disclosure fragments injected after the base prompt. */
    SKILLS_MCPS,

    /** Memory injection (project rules, GLOBAL.md levels, daily window + index). */
    MEMORY,

    /** Per-turn dynamic tail: WorldBook, learned prefs, task anchor, runtime context. */
    RUNTIME_TAIL,

    /** Goal context + live plan-board / work-report / recall injections. */
    GOAL_PLAN,

    /** The effective (compacted / trimmed) agent history window. */
    HISTORY,

    /** Composer attachments not yet sent (images count like in-history images). */
    PENDING_ATTACHMENTS,
}

/** One block of the breakdown. [tokens] is a rough estimate, [share] is vs the sum. */
internal data class ContextBlock(
    val key: ContextBlockKey,
    val tokens: Int,
    /** 0f..1f share of [ContextBreakdown.totalTokens]; 0f when the total is 0. */
    val share: Float,
)

/** Block estimates describe the composition; the input total and allowance describe the request. */
internal data class ContextBreakdown(
    val blocks: List<ContextBlock>,
    val totalTokens: Int,
    val usedTokens: Int,
    val windowTokens: Int,
    val maxOutputTokens: Int,
    val measuredPrefixTokens: Int? = null,
) {
    val contextUsage: ContextUsage
        get() = ContextUsage(usedTokens.toLong(), windowTokens.toLong(), measuredPrefixTokens?.toLong())
}

/**
 * Pure core of the breakdown. Takes pre-computed texts/numbers so the JVM
 * test can pin the arithmetic without a ViewModel or Android types.
 */
internal object ContextBreakdownMath {

    /**
     * Block estimates are intentionally rough; the panel labels them ±10%.
     * The test uses the same constant to prove the block sum stays inside
     * the tolerance of an independently measured whole.
     */
    const val ESTIMATE_TOLERANCE = 0.10f

    fun sectionTokens(text: String): Int = ContextTokenMeter.rawTextTokens(text)

    fun forRequest(
        sections: List<Pair<ContextBlockKey, String>>,
        request: ChatRequest,
        estimate: ContextTokenEstimate,
        windowTokens: Int,
        maxOutputTokens: Int,
        pendingAttachmentTokens: Int,
    ): ContextBreakdown = build(
        sections = sections,
        historyTokens = ContextTokenMeter.rawRequestTokens(request.messages, imageParts = request.imageParts),
        pendingAttachmentTokens = pendingAttachmentTokens,
        usedTokens = estimate.tokens,
        windowTokens = windowTokens,
        maxOutputTokens = maxOutputTokens,
        toolSchemaTokens = ContextTokenMeter.rawRequestTokens(emptyList(), tools = request.tools),
        measuredPrefixTokens = estimate.measuredPrefixTokens,
    )

    /** Blank/zero contributors do not produce rows. Pending attachments are not projected input yet. */
    fun build(
        sections: List<Pair<ContextBlockKey, String>>,
        historyTokens: Int,
        pendingAttachmentTokens: Int,
        usedTokens: Int,
        windowTokens: Int,
        maxOutputTokens: Int,
        toolSchemaTokens: Int = 0,
        measuredPrefixTokens: Int? = null,
    ): ContextBreakdown {
        val blocks = buildList {
            var remainingToolTokens = toolSchemaTokens
            for ((key, text) in sections) {
                val textTokens = if (text.isBlank()) 0 else sectionTokens(text)
                val tokens = if (key == ContextBlockKey.SYSTEM_TOOLS) {
                    val tools = remainingToolTokens
                    remainingToolTokens = 0
                    (textTokens.toLong() + tools).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } else textTokens
                if (tokens > 0) add(ContextBlock(key, tokens, 0f))
            }
            if (remainingToolTokens > 0) add(0, ContextBlock(ContextBlockKey.SYSTEM_TOOLS, remainingToolTokens, 0f))
            if (historyTokens > 0) add(ContextBlock(ContextBlockKey.HISTORY, historyTokens, 0f))
            if (pendingAttachmentTokens > 0) {
                add(ContextBlock(ContextBlockKey.PENDING_ATTACHMENTS, pendingAttachmentTokens, 0f))
            }
        }
        val total = blocks.sumOf { it.tokens.toLong() }
        return ContextBreakdown(
            blocks = if (total > 0) blocks.map { it.copy(share = it.tokens.toFloat() / total) } else blocks,
            totalTokens = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            usedTokens = usedTokens,
            windowTokens = windowTokens,
            maxOutputTokens = maxOutputTokens,
            measuredPrefixTokens = measuredPrefixTokens,
        )
    }
}

/** Read-only projection on IO. Do not publish a prompt breakpoint, recall delivery or media spill. */
internal suspend fun ChatViewModel.buildContextBreakdown(): ContextBreakdown = withContext(Dispatchers.IO) {
    val provider = currentProvider
    val assembly = assembleSystemPrompt(publishStablePrefix = false)
    val tools = agentTools
    val pendingImages = attachments.value.count { it.isImage } * ContextTokenMeter.IMAGE_TOKENS
    if (provider == null) {
        val request = ChatRequest(effectiveAgentHistoryUncounted(), assembly.delivery.prompt, maxTokens = 0, tools = tools)
        return@withContext ContextBreakdownMath.forRequest(
            assembly.sections, request, ContextTokenEstimate(ContextTokenMeter.rawRequestTokens(request)),
            currentModelContextWindow ?: 0, currentModelMaxOutputTokens ?: 0, pendingImages,
        )
    }
    val projected = projectChatRequest(provider, assembly.delivery, agentHistorySnapshot(), tools, requestThinkingLevel(provider))
    ContextBreakdownMath.forRequest(
        sections = projectedContextSections(assembly.sections, assembly.delivery, projected.prompt),
        request = projected.request,
        estimate = projected.estimate,
        windowTokens = projected.window,
        maxOutputTokens = dynamicMaxTokens(provider, projected.estimate.tokens, projected.window),
        pendingAttachmentTokens = pendingImages,
    )
}

/** Projection may prepend a provider identity and replace the dynamic tail. */
internal fun projectedContextSections(
    sections: List<Pair<ContextBlockKey, String>>,
    assembled: MemoryDeliveryPrompt,
    projected: MemoryDeliveryPrompt?,
): List<Pair<ContextBlockKey, String>> {
    if (projected == null) return emptyList()
    if (projected.prompt == assembled.prompt) return sections
    val before = assembled.stablePrefixLen
    val after = projected.stablePrefixLen
    if (before !in 0..assembled.prompt.length || after !in before..projected.prompt.length ||
        !projected.prompt.regionMatches(after - before, assembled.prompt, 0, before)
    ) return listOf(ContextBlockKey.SYSTEM_TOOLS to projected.prompt)

    val addedHead = projected.prompt.substring(0, after - before)
    return sections.filterNot { it.first == ContextBlockKey.RUNTIME_TAIL }.map { (key, text) ->
        key to if (key == ContextBlockKey.SYSTEM_TOOLS) addedHead + text else text
    } + (ContextBlockKey.RUNTIME_TAIL to projected.prompt.substring(after))
}
