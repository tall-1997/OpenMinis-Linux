package com.openminis.app.ui.chat

import com.openminis.app.data.CompactSliceBoundaries
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ToolPairing

/** Ordered request-side pairing shared by the resident and budgeted suffixes. */
internal object ToolPairingCore {
    /** Match the normalized provider id and the exact stored name, like request repair. */
    fun pairKey(id: String, name: String): String = ToolPairing.key(id) + "\u0000" + name

    /**
     * `legal[i]` means the suffix at `i` keeps its original pairs and starts
     * without orphaned parts. An unanswered use is allowed only in the final
     * assistant message. A nonempty history must retain at least one message.
     */
    fun legalCuts(history: List<LLMMessage>): BooleanArray {
        val size = history.size
        if (size == 0) return booleanArrayOf(true)
        val crossing = IntArray(size + 1)
        var lastUnmatched = -1
        val pending = match(
            history,
            paired = { use, result ->
                crossing[use + 1]++
                crossing[result + 1]--
            },
            orphaned = { result -> lastUnmatched = result },
        )
        val inFlight = if (history.last().role == LLMMessage.Role.ASSISTANT) size - 1 else -1
        for (uses in pending) {
            for (use in uses) if (use != inFlight) lastUnmatched = maxOf(lastUnmatched, use)
        }
        // The durable compactor also recognizes stored tool-name aliases. Do
        // not cut an original round that closes there but not on the wire.
        val storedPairs = CompactSliceBoundaries(history)
        var open = 0
        val legal = BooleanArray(size + 1)
        for (i in history.indices) {
            open += crossing[i]
            legal[i] = open == 0 && i > lastUnmatched && storedPairs.isClosed(i)
        }
        return legal
    }

    fun isBalancedBefore(history: List<LLMMessage>, index: Int): Boolean =
        legalCuts(history).getOrElse(index) { false }

    fun firstLegalCutAtOrAfter(history: List<LLMMessage>, from: Int): Int? {
        val legal = legalCuts(history)
        return (from.coerceIn(0, legal.lastIndex)..legal.lastIndex).firstOrNull { legal[it] }
    }

    fun lastLegalCutAtOrBefore(history: List<LLMMessage>, from: Int): Int? {
        val legal = legalCuts(history)
        return (from.coerceIn(0, legal.lastIndex) downTo 0).firstOrNull { legal[it] }
    }

    /** A result before its call cannot settle that call. Reused keys are consumed FIFO. */
    fun hasPendingUse(group: List<LLMMessage>): Boolean = match(group).isNotEmpty()

    private fun match(
        history: List<LLMMessage>,
        paired: (Int, Int) -> Unit = { _, _ -> },
        orphaned: (Int) -> Unit = {},
    ): Collection<ArrayDeque<Int>> {
        val pending = HashMap<String, ArrayDeque<Int>>()
        history.forEachIndexed { index, message ->
            for (part in message.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse ->
                        pending.getOrPut(pairKey(part.id, part.name), ::ArrayDeque).addLast(index)
                    is AgentContentPart.ToolResult -> {
                        val key = pairKey(part.id, part.name)
                        val uses = pending[key]
                        if (uses == null) {
                            orphaned(index)
                        } else {
                            paired(uses.removeFirst(), index)
                            if (uses.isEmpty()) pending.remove(key)
                        }
                    }
                    else -> Unit
                }
            }
        }
        return pending.values
    }
}
