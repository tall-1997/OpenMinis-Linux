package com.openminis.app.provider.openai

import org.json.JSONArray
import org.json.JSONObject

/**
 * [GH#222/#293/#352/#382] Final wire-shape gate for Chat-Completions tool
 * pairing.
 *
 * 中文：发送前清理孤立/重复的 tool 消息，并为悬空 tool_calls 补一条错误
 * tool 结果，避免上游 400 让会话永久卡死；每次剔除都计数并写日志。
 *
 * The LLM-history layer already repairs orphaned tool parts, but this array is
 * what the provider actually validates, and it can still be malformed by:
 *  - a `role:"tool"` message whose `tool_calls` partner was compacted/
 *    truncated away or rewritten under another id → HTTP 400
 *    `Messages with role 'tool' must be a response to a preceding message
 *    with 'tool_calls'` (#293);
 *  - an assistant `tool_calls` entry with no following tool reply (an
 *    interrupted turn whose tail was kept) → HTTP 400
 *    `An assistant message with 'tool_calls' must be followed by tool
 *    messages responding to each 'tool_call_id'` (#352);
 *  - the same tool id answered twice → duplicate/400 (#222/#382).
 *
 * Policy, applied to a COPY so the caller's history/model is untouched:
 *  1. assistant tool_calls are sanitized (id/name must be non-blank);
 *  2. a tool reply is kept only while its id is pending from the immediately
 *     preceding assistant tool_calls message (wire order, not set membership);
 *  3. duplicate replies (same id already consumed) are dropped;
 *  4. when a pending call would be separated from its reply by any other
 *     message, a synthetic error tool reply is emitted first so strict
 *     gateways never see a dangling tool_calls block.
 *
 * Every drop/insert is counted and logged by the caller at WARNING level.
 */
internal object ChatToolPairSanitizer {

    data class Report(
        val messages: JSONArray,
        val droppedOrphanResults: Int,
        val droppedDuplicateResults: Int,
        val insertedMissingResults: Int,
        val droppedMalformedCalls: Int,
    ) {
        val changed: Boolean
            get() = droppedOrphanResults > 0 || droppedDuplicateResults > 0 ||
                insertedMissingResults > 0 || droppedMalformedCalls > 0
    }

    internal const val MISSING_RESULT_TEXT =
        "Error: tool result missing from history — the client repaired a broken " +
            "tool-call pairing before sending. The tool may not have run; re-issue " +
            "the call if its output is still needed."

    fun sanitize(messages: JSONArray): Report {
        val out = JSONArray()
        val pending = LinkedHashSet<String>()
        val consumed = HashSet<String>()
        var orphans = 0
        var duplicates = 0
        var inserted = 0
        var malformed = 0

        fun flushMissing() {
            if (pending.isEmpty()) return
            for (id in pending) {
                out.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", MISSING_RESULT_TEXT),
                )
                consumed += id
                inserted += 1
            }
            pending.clear()
        }

        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            when (msg.optString("role")) {
                "assistant" -> {
                    // Any unanswered calls belong to the previous assistant
                    // block; close them before the new turn starts.
                    flushMissing()
                    val calls = msg.optJSONArray("tool_calls")
                    if (calls != null) {
                        val cleaned = JSONArray()
                        for (j in 0 until calls.length()) {
                            val call = calls.optJSONObject(j) ?: continue
                            val id = call.optString("id", "")
                            val name = call.optJSONObject("function")
                                ?.optString("name", "").orEmpty()
                            if (id.isBlank() || name.isBlank()) {
                                malformed += 1
                                continue
                            }
                            cleaned.put(call)
                            pending += id
                        }
                        if (cleaned.length() == 0) {
                            msg.remove("tool_calls")
                        } else if (cleaned.length() != calls.length()) {
                            msg.put("tool_calls", cleaned)
                        }
                    }
                    out.put(msg)
                }
                "tool" -> {
                    val id = msg.optString("tool_call_id", "")
                    when {
                        id.isBlank() -> orphans += 1
                        id in pending -> {
                            pending.remove(id)
                            consumed += id
                            out.put(msg)
                        }
                        id in consumed -> duplicates += 1
                        else -> orphans += 1
                    }
                }
                else -> {
                    // A user/system message between tool calls closes the
                    // block: synthesize the missing replies now, then emit it.
                    flushMissing()
                    out.put(msg)
                }
            }
        }
        flushMissing()
        return Report(
            messages = out,
            droppedOrphanResults = orphans,
            droppedDuplicateResults = duplicates,
            insertedMissingResults = inserted,
            droppedMalformedCalls = malformed,
        )
    }
}
