package com.openminis.app.ui.chat

import com.openminis.app.agent.HarnessConfig
import com.openminis.app.data.ContextTokenMeter
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.util.TextClip
import kotlinx.coroutines.CancellationException

/** Request-only budget for the active region after a compact marker. */
internal object PostAnchorTail {
    internal const val BUDGET_TOKENS: Int = 60_000
    internal const val NOTICE_RESERVE_TOKENS: Int = 1_200
    internal const val WINDOW_FRACTION: Double = 0.35

    /** The compact gate and formal projection must be able to see all uncovered rows. */
    internal fun budgetTokensForWindow(
        windowTokens: Int?,
        applyTailBudget: Boolean = true,
        config: HarnessConfig = HarnessConfig.current(),
    ): Int = if (applyTailBudget) config.postAnchorBudgetFor(windowTokens) else Int.MAX_VALUE

    internal data class Budget(
        val budgetTokens: Int,
        val estimate: (LLMMessage) -> Int = WalkBack::estimateMessageTokens,
        val digest: (dropped: List<LLMMessage>) -> String? = { null },
        val config: HarnessConfig = HarnessConfig.current(),
    )

    internal data class Result(
        /** Kept slice; [notice] already prepended when rounds were dropped. */
        val kept: List<LLMMessage>,
        val droppedRounds: Int,
        val droppedMessages: Int,
        val droppedTokens: Int,
    ) {
        val notice: LLMMessage? get() = kept.firstOrNull()?.takeIf { it.isDroppedRoundsNotice }
        val hasNotice: Boolean get() = notice != null
    }

    internal fun droppedRoundsNotice(droppedRounds: Int, droppedTokens: Int): String =
        "[context window: $droppedRounds older rounds dropped (≈$droppedTokens tokens) — " +
            "the earlier summary does NOT cover them; see the digest and working-memory note, " +
            "and re-run a specific tool if you need exact output]"

    private val LLMMessage.isDroppedRoundsNotice: Boolean
        get() = role == LLMMessage.Role.USER &&
            content.startsWith("[context window: ") && content.contains(" older rounds dropped (")

    /** Always keep the newest legal group, even when it alone exceeds the budget. */
    fun apply(postAnchor: List<LLMMessage>, budget: Budget): Result {
        if (postAnchor.isEmpty() || budget.budgetTokens <= 0 || budget.budgetTokens == Int.MAX_VALUE) {
            return Result(postAnchor, 0, 0, 0)
        }
        val groups = splitRounds(postAnchor)
        if (groups.size <= 1) return Result(postAnchor, 0, 0, 0)
        val effectiveBudget = budget.config.effectiveTailBudget(budget.budgetTokens)
        val costs = LongArray(groups.size) { i ->
            groups[i].sumOf { msg -> budget.estimate(msg).coerceAtLeast(0).toLong() }
        }
        var total = costs.last()
        var firstKept = groups.lastIndex
        while (firstKept > 0) {
            val next = total + costs[firstKept - 1]
            if (next > effectiveBudget) break
            total = next
            firstKept--
        }
        if (firstKept == 0) return Result(postAnchor, 0, 0, 0)

        val droppedMessages = (0 until firstKept).sumOf { groups[it].size }
        val droppedTokens = (0 until firstKept).sumOf { costs[it] }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val notice = withBudgetedDigest(
            droppedRoundsNotice(firstKept, droppedTokens), postAnchor.subList(0, droppedMessages),
            budget.config, budget.digest,
        )
        val kept = ArrayList<LLMMessage>(postAnchor.size - droppedMessages + 1)
        kept.add(LLMMessage(LLMMessage.Role.USER, notice, contentParts = listOf(AgentContentPart.Text(notice))))
        kept.addAll(postAnchor.subList(droppedMessages, postAnchor.size))
        return Result(kept, firstKept, droppedMessages, droppedTokens)
    }

    /** Both tail modes share the reserve; the meter covers mixed-width excerpts too. */
    internal fun withBudgetedDigest(
        base: String,
        dropped: List<LLMMessage>,
        config: HarnessConfig,
        digest: ((List<LLMMessage>) -> String?)?,
    ): String {
        if (digest == null || config.noticeReserveTokens <= config.noticeBaseTokens) return base
        val text = try {
            digest(dropped)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotBlank() } ?: return base
        val prefix = "$base\n"
        if (ContextTokenMeter.rawTextTokens(prefix) >= config.noticeReserveTokens) return base
        fun fits(excerpt: String): Boolean =
            ContextTokenMeter.rawTextTokens(prefix + excerpt) <= config.noticeReserveTokens &&
                ContextTokenMeter.rawTextTokens(excerpt) <= config.noticeReserveTokens - config.noticeBaseTokens

        fun clip(limit: Int): String = if (text.length <= limit) text else TextClip.safeTake(text, limit - 1) + '…'
        val bounded = clip(config.digestMaxChars)
        if (fits(bounded)) return prefix + bounded
        var lo = 0
        var hi = minOf(config.digestMaxChars, text.lastIndex)
        while (lo < hi) {
            val mid = lo + (hi - lo + 1) / 2
            if (fits(clip(mid))) lo = mid else hi = mid - 1
        }
        return if (lo == 0) base else prefix + clip(lo)
    }

    /** Permit new user turns and assistant steps, but only at legal pair boundaries. */
    internal fun splitRounds(messages: List<LLMMessage>): List<List<LLMMessage>> {
        if (messages.isEmpty()) return emptyList()
        val legal = ToolPairingCore.legalCuts(messages)
        val groups = ArrayList<List<LLMMessage>>()
        var start = 0
        var hasAssistant = messages.first().role == LLMMessage.Role.ASSISTANT
        for (i in 1 until messages.size) {
            val msg = messages[i]
            val startsRound = msg.canCarryCompactSummary()
            val startsStep = msg.role == LLMMessage.Role.ASSISTANT && hasAssistant
            if (legal[i] && (startsRound || startsStep)) {
                groups.add(messages.subList(start, i))
                start = i
                hasAssistant = false
            }
            if (msg.role == LLMMessage.Role.ASSISTANT) hasAssistant = true
        }
        groups.add(messages.subList(start, messages.size))
        return groups
    }
}
