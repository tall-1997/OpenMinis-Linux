package com.openminis.app.provider.openai

import org.json.JSONArray

/**
 * Walk every assistant.tool_calls entry and every role:"tool" tool_call_id in
 * order, renaming any duplicate id to `{id}-{N}`. The first occurrence keeps the
 * raw id; subsequent collisions get a numeric suffix starting at 2. Renames
 * propagate to each pair's matching role:"tool" reply by remembering the latest
 * rename per raw id (the reply is required to immediately follow its claiming
 * assistant tool_calls on this provider).
 *
 * Top-level rather than a member of [OpenAIRequestBodies]: the architecture
 * ratchet freezes that file's size, and this pass touches no instance state.
 * The renamed-count log line is kept because the condition it reports
 * (DB-loaded history or a cross-provider switch) is otherwise invisible.
 */
internal fun globallyDedupeToolCallIds(messagesArray: JSONArray) {
    // raw id → max suffix already issued (0 = unused, 1 = raw kept,
    // 2+ = renamed copies).
    val seen = HashMap<String, Int>()
    // raw id → latest renamed id, so the next role:"tool" reply claiming this
    // raw id can pick up the same rewrite.
    val renameForPendingResults = HashMap<String, String>()
    var renamedCount = 0
    val n = messagesArray.length()
    for (i in 0 until n) {
        val msg = messagesArray.optJSONObject(i) ?: continue
        val role = msg.optString("role")
        when (role) {
            "assistant" -> {
                val toolCalls = msg.optJSONArray("tool_calls") ?: continue
                for (j in 0 until toolCalls.length()) {
                    val call = toolCalls.optJSONObject(j) ?: continue
                    val rawId = call.optString("id", "")
                    if (rawId.isEmpty()) continue
                    val used = seen[rawId] ?: 0
                    val renamedId: String
                    if (used == 0) {
                        renamedId = rawId
                        seen[rawId] = 1
                    } else {
                        val next = used + 1
                        renamedId = "$rawId-$next"
                        seen[rawId] = next
                        renamedCount += 1
                        call.put("id", renamedId)
                    }
                    renameForPendingResults[rawId] = renamedId
                }
            }
            "tool" -> {
                val rawId = msg.optString("tool_call_id", "")
                if (rawId.isEmpty()) continue
                val renamedId = renameForPendingResults[rawId] ?: continue
                if (renamedId != rawId) {
                    msg.put("tool_call_id", renamedId)
                }
            }
        }
    }
    if (renamedCount > 0) {
        android.util.Log.w(
            "OpenAIProvider",
            "[dedupe-tool-call-id] renamed $renamedCount duplicate tool_call_id(s) across messages " +
                "— likely DB-loaded history or cross-provider switch",
        )
    }
}
