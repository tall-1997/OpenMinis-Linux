package com.openminis.app.tools

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.sandbox.SessionAccessAudit
import com.openminis.app.sandbox.SessionAccessPolicy
import com.openminis.app.util.IsoTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * First-class session lookup (拾忆 `search_sessions` / `read_session`).
 * Wraps the existing Room backend used by `minis-sessions-cli` so the model
 * does not have to know that CLI.
 */
object SessionLookupTool {
    const val SEARCH = "search_sessions"
    const val READ = "read_session"

    private const val SEARCH_LIMIT_DEFAULT = 8
    private const val SEARCH_LIMIT_MAX = 20
    private const val READ_LIMIT_DEFAULT = 24
    private const val READ_LIMIT_MAX = 40
    private const val READ_CHARS = 600

    fun searchDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = SEARCH,
        description = "Search other chat sessions on this device (titles and message text). " +
            "Use this when the user refers to a previous conversation, project, or decision " +
            "that is not in the current thread. Returns session_id, title, snippet. " +
            "Does not include the current session unless include_current is true. " +
            "Follow with read_session to load a transcript. Do not dump secrets.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short status, e.g. 'Find last backup talk'."),
            "query" to AgentToolParam("string", "Keywords to match in titles or messages. Empty lists recent sessions."),
            "limit" to AgentToolParam("integer", "Max sessions or hits to return (default 8, max 20)."),
            "include_current" to AgentToolParam("boolean", "Include the current session (default false)."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "query", "limit", "include_current"),
    )

    fun readDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = READ,
        description = "Read a paginated transcript of one past chat session by session_id " +
            "from search_sessions. Each message is capped at 600 characters. " +
            "Use offset to page. Do not dump secrets or API keys if they appear.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short status, e.g. 'Read backup session'."),
            "session_id" to AgentToolParam("string", "Session id returned by search_sessions."),
            "limit" to AgentToolParam("integer", "Messages to return (default 24, max 40)."),
            "offset" to AgentToolParam("integer", "Skip this many visible messages (default 0)."),
        ),
        required = listOf("tool_title", "session_id"),
        propertyOrdering = listOf("tool_title", "session_id", "limit", "offset"),
    )

    suspend fun executeSearch(argsJson: String, currentSessionId: String, context: Context): ToolExecutionResult {
        val args = parseArgs(argsJson)
        val toolTitle = args.optString("tool_title", SEARCH)
        val repo = repo(context)
            ?: return ToolExecutionResult("Error: chat store is not ready.", false, toolTitle = toolTitle)
        val query = args.optString("query", "").trim()
        val includeCurrent = args.optBoolean("include_current", false)
        // [T-android-session-read-boundary] Without the `session_read` grant this
        // tool only sees the session it runs in. Both branches below already take an
        // explicit id filter, so the restriction is an argument rather than a second
        // code path that could drift away from the granted one.
        val granted = SessionAccessPolicy.isCrossSessionGranted(currentSessionId)
        val scopeIds = if (granted) null else listOf(currentSessionId)
        SessionAccessAudit.record(
            currentSessionId, SEARCH, if (granted) "(all sessions)" else currentSessionId, true, granted,
        )
        val limit = args.optInt("limit", SEARCH_LIMIT_DEFAULT).coerceIn(1, SEARCH_LIMIT_MAX)
        val keywords = splitQuery(query)
        return try {
            val json = run {
                if (keywords.isEmpty()) {
                    val rows = repo.querySessionsMeta(scopeIds, null, limit + 2, null, null)
                        .filter { granted || includeCurrent || it.id != currentSessionId }
                        .take(limit)
                    JSONObject()
                        .put("count", rows.size)
                        .put("scope", if (granted) "all_sessions" else "current_session")
                        .put("sessions", JSONArray().also { arr ->
                            rows.forEach { s ->
                                arr.put(
                                    JSONObject()
                                        .put("session_id", s.id)
                                        .put("title", s.title ?: "")
                                        .put("preview", s.preview ?: "")
                                        .put("message_count", s.messageCount)
                                        .put("last_active", iso(s.lastActive)),
                                )
                            }
                        })
                } else {
                    val hits = repo.searchMessages(scopeIds, keywords, limit + 4, null, null)
                        .filter { granted || includeCurrent || it.sessionId != currentSessionId }
                        .take(limit)
                    val ids = hits.map { it.sessionId }.distinct()
                    val titles = if (ids.isEmpty()) {
                        emptyMap()
                    } else {
                        repo.querySessionsMeta(ids, null, ids.size, null, null)
                            .associate { it.id to (it.title ?: "") }
                    }
                    JSONObject()
                        .put("count", hits.size)
                        .put("scope", if (granted) "all_sessions" else "current_session")
                        .put("query", query)
                        .put("hits", JSONArray().also { arr ->
                            hits.forEach { h ->
                                arr.put(
                                    JSONObject()
                                        .put("session_id", h.sessionId)
                                        .put("title", titles[h.sessionId] ?: "")
                                        .put("role", h.role)
                                        .put("snippet", h.snippet)
                                        .put("created_at", iso(h.createdAt)),
                                )
                            }
                        })
                }
            }
            ToolExecutionResult(json.toString(), true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false, toolTitle = toolTitle)
        }
    }

    suspend fun executeRead(
        argsJson: String,
        currentSessionId: String,
        context: Context,
    ): ToolExecutionResult {
        val args = parseArgs(argsJson)
        val toolTitle = args.optString("tool_title", READ)
        val sessionId = args.optString("session_id", "").trim()
        if (sessionId.isEmpty()) {
            return ToolExecutionResult("Error: session_id is required.", false, toolTitle = toolTitle)
        }
        // [T-android-session-read-boundary] Reading another chat's transcript is
        // exactly what this boundary exists for: it needs the user's `session_read`
        // grant. The attempt is audited either way, granted or not.
        if (SessionAccessPolicy.isForeign(currentSessionId, sessionId)) {
            val granted = SessionAccessPolicy.isCrossSessionGranted(currentSessionId)
            SessionAccessAudit.record(currentSessionId, READ, sessionId, granted, granted)
            if (!granted) {
                return ToolExecutionResult(
                    "Error: session $sessionId belongs to another chat. Reading it needs the user " +
                        "to grant `session_read` (Settings > Permissions > Read other chats). Ask " +
                        "the user first, then retry.",
                    false,
                    toolTitle = toolTitle,
                )
            }
        } else {
            SessionAccessAudit.record(currentSessionId, READ, sessionId, true, false)
        }
        val repo = repo(context)
            ?: return ToolExecutionResult("Error: chat store is not ready.", false, toolTitle = toolTitle)
        val limit = args.optInt("limit", READ_LIMIT_DEFAULT).coerceIn(1, READ_LIMIT_MAX)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        return try {
            val json = run {
                val meta = repo.querySessionsMeta(listOf(sessionId), null, 1, null, null).firstOrNull()
                val page = repo.loadMessagePage(sessionId, offset, limit, READ_CHARS)
                val total = repo.messageCount(sessionId)
                JSONObject()
                    .put("session_id", sessionId)
                    .put("title", meta?.title ?: "")
                    .put("offset", offset)
                    .put("returned", page.size)
                    .put("total_messages", total)
                    .put("has_more", offset + page.size < total)
                    .put("messages", JSONArray().also { arr ->
                        page.forEach { m ->
                            arr.put(
                                JSONObject()
                                    .put("role", m.role)
                                    .put("text", m.text)
                                    .put("truncated", m.truncated)
                                    .put("created_at", iso(m.createdAt)),
                            )
                        }
                    })
            }
            ToolExecutionResult(json.toString(), true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false, toolTitle = toolTitle)
        }
    }

    fun splitQuery(raw: String): List<String> =
        raw.trim().split(Regex("\\s+")).map { it.trim() }.filter { it.length >= 2 }.take(8)

    private fun parseArgs(argsJson: String): JSONObject =
        try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }

    private fun repo(context: Context): ChatRepository? =
        (context.applicationContext as? MinisApp)?.chatRepositoryOrNull

    private fun iso(ms: Long): String = IsoTime.formatOffset(ms)
}
