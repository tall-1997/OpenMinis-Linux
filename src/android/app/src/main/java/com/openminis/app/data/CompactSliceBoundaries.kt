package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ToolPairing
import com.openminis.app.security.ToolAliases

/** Complete tool calls (including parallel results) must stay on one side of a cut. */
internal class CompactSliceBoundaries(history: List<LLMMessage>, protectPending: Boolean = false) {
    private val closed = BooleanArray(history.size + 1)

    init {
        // Mark intervals in O(messages + parts). Consecutive calls may reuse an ID;
        // consume one preceding use per result, just like request pairing repair.
        val pending = HashMap<Pair<String, String>, ArrayDeque<Int>>()
        val crossing = IntArray(closed.size)
        history.forEachIndexed { index, message ->
            for (part in message.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> {
                        val key = compactPairKey(part.id, part.name)
                        if (key != null) pending.getOrPut(key, ::ArrayDeque).addLast(index)
                        else if (protectPending) crossing[index + 1]++
                    }
                    is AgentContentPart.ToolResult -> {
                        val key = compactPairKey(part.id, part.name)
                        val uses = key?.let { pending[it] }
                        if (uses == null) {
                            // A legacy boundary may leave a result whose use
                            // is before the source. Only the repository's
                            // explicit text projection can retire a Stop;
                            // every remaining tool result still needs its use.
                            if (protectPending) crossing[index + 1]++
                            continue
                        }
                        val start = uses.removeFirst()
                        if (uses.isEmpty()) pending.remove(key)
                        crossing[start + 1]++
                        crossing[index + 1]--
                    }
                    else -> Unit
                }
            }
        }
        // A source page/explicit anchor might end before the matching result.
        // Conservatively leave outstanding calls active when the caller has
        // no trustworthy bound on the rest of the session.
        if (protectPending) {
            for (uses in pending.values) crossing[uses.first() + 1]++
        }
        var open = 0
        for (cut in closed.indices) {
            open += crossing[cut]
            closed[cut] = open == 0
        }
    }

    fun isClosed(cut: Int): Boolean = closed.getOrElse(cut) { false }

    fun atOrBefore(cut: Int): Int {
        var current = cut.coerceIn(0, closed.lastIndex)
        while (!closed[current]) current--
        return current
    }

    fun interiorSplit(): Int? {
        val middle = closed.lastIndex / 2
        val earlier = atOrBefore(middle)
        if (earlier > 0) return earlier
        return (middle + 1 until closed.lastIndex).firstOrNull(::isClosed)
    }

}

internal fun compactPairKey(id: String, name: String): Pair<String, String>? {
    val callId = ToolPairing.key(id)
    val canonical = ToolAliases.canonical(name)
    return if (callId.isBlank() || canonical.isBlank()) null else callId to canonical
}

internal fun preserveToolPairsAtBoundaries(
    history: List<LLMMessage>,
    startIdx: Int,
    anchorIdx: Int,
): Pair<Int, Int> {
    val boundaries = CompactSliceBoundaries(history)
    return boundaries.atOrBefore(startIdx) to (boundaries.atOrBefore(anchorIdx + 1) - 1)
}
