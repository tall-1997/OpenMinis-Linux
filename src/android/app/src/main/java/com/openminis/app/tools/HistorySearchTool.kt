package com.openminis.app.tools

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.compactBoundaryMs
import com.openminis.app.data.repository.messageText
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-history-retrieval] Read-only retrieval over THIS session's earlier
 * messages, including history compaction has already folded out of context.
 *
 * WHY this exists: after compaction the folded rounds leave the request, and
 * the only substitute was [com.openminis.app.ui.chat.DroppedRoundDigest] — a
 * digest the HOST chose at a fixed budget. When the model needed "what did we
 * decide about X before the boundary" the digest either covered it or did not,
 * and there was no way to ask for a specific memory. Deer-flow's
 * `task_continuity` solves the same problem with `history_search` +
 * `history_read`; this is that pair, scoped to one session.
 *
 * Contract, deliberately strict so a caller can verify a hit:
 *  - results carry `excerpt_start` / `excerpt_end` in the SAME code-point
 *    coordinates `history_read` uses, so reading at `excerpt_start` returns
 *    text that begins with the excerpt;
 *  - `history_read` pages by code point, never splitting a surrogate pair;
 *  - both sides mask credentials and never invent text (verbatim slices only).
 *
 * Read-only by construction: no repository write, no model call.
 *
 * Both entry points are `suspend`, matching [SessionLookupTool]: tool dispatch
 * already runs inside the agent loop's coroutine, so a blocking bridge would
 * have parked a thread on every search.
 */
object HistorySearchTool {

    const val SEARCH = "history_search"
    const val READ = "history_read"

    private const val SEARCH_LIMIT_DEFAULT = 6
    private const val SEARCH_LIMIT_MAX = 8
    private const val EXCERPT_CHARS = 600
    private const val PAGE_CHARS = 4_000


    fun searchDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = SEARCH,
        description = "Search this conversation's earlier messages, including history that " +
            "compaction has already folded out of your context. Returns at most 8 hits with " +
            "message_id, role, excerpt, and excerpt_start/excerpt_end code-point offsets. " +
            "Call history_read with the same message_id and offset to read the full text. " +
            "Use this when you need an exact earlier decision, command, or file path that is " +
            "no longer in your context.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short status, e.g. 'Find migration decision'."),
            "query" to AgentToolParam("string", "Keywords to match. CJK runs are split into bigrams."),
            "role" to AgentToolParam("string", "Optional filter: 'user', 'assistant', or 'tool'."),
            "limit" to AgentToolParam("integer", "Max hits (default 6, max 8)."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "role", "limit"),
    )

    fun readDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = READ,
        description = "Read one earlier message by message_id from history_search, starting at a " +
            "code-point offset and returning up to 4000 characters. The response carries " +
            "next_offset when more text remains and truncated=false when it does not. " +
            "Pass offset=0 to read from the beginning.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short status, e.g. 'Read that message'."),
            "message_id" to AgentToolParam("string", "id returned by history_search."),
            "offset" to AgentToolParam("integer", "Code-point offset to start at (default 0)."),
        ),
        required = listOf("tool_title", "message_id"),
        propertyOrdering = listOf("tool_title", "message_id", "offset"),
    )

    /**
     * Split a query into search keywords. Latin words stay whole; a CJK run is
     * split into bigrams because the table has no tokenizer and a single
     * character matches almost everything.
     */
    internal fun keywords(query: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in Regex("[A-Za-z0-9_]{2,}").findAll(query)) out += m.value.lowercase()
        val run = StringBuilder()
        fun flush() {
            val s = run.toString()
            if (s.length == 1) out += s
            for (i in 0 until s.length - 1) out += s.substring(i, i + 2)
            run.setLength(0)
        }
        for (ch in query) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3040..0x30FF) run.append(ch) else flush()
        }
        flush()
        return out.take(8).toList()
    }

    /**
     * Locate [needle] in [text] by code point, returning a centred excerpt and
     * its exact code-point offsets. When the hit sits near either end the
     * window slides instead of shrinking, so a caller always gets a full-width
     * excerpt when the source is long enough.
     */
    internal fun excerptAt(
        text: String,
        needle: String,
        width: Int = EXCERPT_CHARS,
    ): Triple<String, Int, Int>? {
        if (text.isEmpty() || needle.isEmpty()) return null
        val hay = text.codePoints().toArray()
        val pin = needle.codePoints().toArray()
        if (pin.isEmpty() || pin.size > hay.size) return null
        var found = -1
        outer@ for (start in 0..(hay.size - pin.size)) {
            for (k in pin.indices) {
                if (hay[start + k] != pin[k]) continue@outer
            }
            found = start
            break
        }
        if (found < 0) return null
        var from = (found - width / 2).coerceAtLeast(0)
        var to = (from + width).coerceAtMost(hay.size)
        if (to - from < width) {
            from = (to - width).coerceAtLeast(0)
            to = (from + width).coerceAtMost(hay.size)
        }
        return Triple(String(hay, from, to - from), from, to)
    }

    /** Code-point safe slice with an explicit next offset (null = end). */
    internal fun page(
        text: String,
        offset: Int,
        limit: Int = PAGE_CHARS,
    ): Triple<String, Int?, Boolean> {
        val cps = text.codePoints().toArray()
        val from = offset.coerceIn(0, cps.size)
        val to = (from + limit).coerceAtMost(cps.size)
        // [T-history-retrieval] `next_offset` is only meaningful while text
        // REMAINS after this page. Returning the page end on the final page
        // would tell the caller to read again for nothing, so the offset is
        // null exactly when the page reached the end.
        val hasMore = to < cps.size
        val next = if (hasMore) to else null
        return Triple(String(cps, from, to - from), next, hasMore)
    }

    private fun repo(context: Context): ChatRepository? =
        (context.applicationContext as? MinisApp)?.chatRepositoryOrNull

    suspend fun executeSearch(
        argsJson: String,
        sessionId: String,
        context: Context,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val toolTitle = args.optString("tool_title", SEARCH)
        val repo = repo(context)
            ?: return ToolExecutionResult("Error: chat store is not ready.", false, toolTitle = toolTitle)
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) {
            return ToolExecutionResult("Error: 'query' is required.", false, toolTitle = toolTitle)
        }
        val words = keywords(query)
        if (words.isEmpty()) {
            return ToolExecutionResult("Error: query has no searchable terms.", false, toolTitle = toolTitle)
        }
        val role = args.optString("role", "").trim().lowercase()
            .takeIf { it in setOf("user", "assistant", "tool") }
        val limit = args.optInt("limit", SEARCH_LIMIT_DEFAULT).coerceIn(1, SEARCH_LIMIT_MAX)
        return try {
            // [T-history-marker-scope] Restrict to history compaction folded
            // OUT of the request. Without the cutoff this tool also returned the
            // messages the model is already reading, which spends a hit slot on
            // something it can see directly and makes "found in history"
            // indistinguishable from "it is right above". No marker at all
            // means nothing has been folded, so the whole session stays visible
            // (searching an uncompacted chat is still useful).
            val boundaryMs = repo.compactBoundaryMs(sessionId)
            // Over-fetch, then filter by role and excerpt: a hit whose excerpt
            // cannot be located is not a usable hit and must not consume a slot.
            val matches = repo.searchMessages(
                sessionIds = listOf(sessionId),
                keywords = words,
                limit = limit * 4,
                startMs = null,
                endMs = boundaryMs,
            )
            val hits = JSONArray()
            for (m in matches) {
                if (role != null && m.role != role) continue
                // [T-history-retrieval] One row at a time. The old code loaded the
                // whole page (every part body in the session) to locate a handful
                // of excerpts; the single-row query costs O(1) per surviving hit.
                val text = repo.messageText(sessionId, m.messageId) ?: continue
                val needle = words.firstOrNull { text.contains(it, ignoreCase = true) } ?: continue
                val found = excerptAt(text, needle) ?: continue
                hits.put(
                    JSONObject()
                        .put("message_id", m.messageId)
                        .put("role", m.role)
                        .put("excerpt", com.openminis.app.util.SecretMasking.mask(found.first))
                        .put("excerpt_start", found.second)
                        .put("excerpt_end", found.third),
                )
                if (hits.length() >= limit) break
            }
            if (hits.length() == 0) {
                // [T-history-marker-scope] Say WHERE it looked. "No match" after
                // a silent scope change reads as "this never happened", which is
                // a worse failure than no answer: the model concludes the work
                // was not done.
                val scope = if (boundaryMs == null) {
                    "this session"
                } else {
                    "the compacted part of this session (the active region is already in context)"
                }
                ToolExecutionResult(
                    "No message in $scope matched " +
                        words.joinToString(", ") + ". Try fewer or different keywords.",
                    true,
                    toolTitle = toolTitle,
                )
            } else {
                ToolExecutionResult(
                    JSONObject().put("hits", hits).put("count", hits.length()).toString(2),
                    true,
                    toolTitle = toolTitle,
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    suspend fun executeRead(
        argsJson: String,
        sessionId: String,
        context: Context,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val toolTitle = args.optString("tool_title", READ)
        val repo = repo(context)
            ?: return ToolExecutionResult("Error: chat store is not ready.", false, toolTitle = toolTitle)
        val id = args.optString("message_id", "").trim()
        if (id.isEmpty()) {
            return ToolExecutionResult("Error: 'message_id' is required.", false, toolTitle = toolTitle)
        }
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        return try {
            // [T-history-retrieval] Single-row read: a page scan here was the
            // largest allocation this tool could make.
            val text = repo.messageText(sessionId, id)
            if (text.isNullOrBlank()) {
                ToolExecutionResult(
                    "Error: message $id not found in this session.",
                    false,
                    toolTitle = toolTitle,
                )
            } else {
                val (body, next, truncated) = page(text, offset)
                ToolExecutionResult(
                    JSONObject()
                        .put("message_id", id)
                        .put("offset", offset)
                        .put("text", com.openminis.app.util.SecretMasking.mask(body))
                        .put("next_offset", next ?: JSONObject.NULL)
                        .put("truncated", truncated)
                        .toString(2),
                    true,
                    toolTitle = toolTitle,
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)
        }
    }
}