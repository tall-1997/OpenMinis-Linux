package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger

/**
 * [T-dedupe-toolcallid 03fbcbfd] Per-turn dedupe of tool_call_id.
 *
 * Some upstream OpenAI-compatible gateways occasionally emit multiple
 * parallel tool_calls with the SAME id but different name/args. Sending both
 * back unchanged trips the receiver's uniqueness check (HTTP 400 "duplicate
 * tool_call_id"). The first occurrence keeps the raw id, the second becomes
 * "<id>-2", the third "<id>-3", and so on.
 *
 * Extracted from ChatViewModelAgentLoopExt so that file stays under its
 * architecture-policy line ceiling. The three counters live together because
 * Android routes ToolInputDelta by chunk.id (iOS routes by name) and OpenAI
 * emits ALL completes together after finish_reason — the "currently in-flight"
 * map cannot be dropped by the time completes arrive.
 *
 *   startCounts     raw id → # ToolUseStart events seen
 *   completeCounts  raw id → # ToolCallComplete events seen
 *   inFlightRenamed raw id → renamed id of the tool currently streaming
 *                   deltas (overwritten on each start)
 *
 * Start/complete ordering match: OpenAI streams emit tools in `index` order
 * at finish_reason, mirroring start order.
 */
internal class ToolCallIdDedupe {

    private val startCounts = mutableMapOf<String, Int>()
    private val completeCounts = mutableMapOf<String, Int>()
    private val inFlightRenamed = mutableMapOf<String, String>()

    fun startId(raw: String): String {
        val n = (startCounts[raw] ?: 0) + 1
        startCounts[raw] = n
        val renamed = if (n == 1) raw else "$raw-$n"
        if (n > 1) {
            AppLogger.warning(
                ChatViewModel.TAG_STREAM,
                "[ToolDedupe] duplicate tool_call id on stream start: '$raw' #$n -> renamed '$renamed'",
            )
        }
        inFlightRenamed[raw] = renamed
        return renamed
    }

    fun inputId(raw: String): String = inFlightRenamed[raw] ?: raw

    fun completeId(raw: String): String {
        val n = (completeCounts[raw] ?: 0) + 1
        completeCounts[raw] = n
        return if (n == 1) raw else "$raw-$n"
    }
}
