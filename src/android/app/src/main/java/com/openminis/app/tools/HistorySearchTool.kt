package com.openminis.app.tools

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.HistoryBody
import com.openminis.app.data.repository.HistoryScope
import com.openminis.app.data.repository.SessionHistory
import com.openminis.app.util.SecretMasking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Same-session retrieval only. Offsets are code points in the shared, redacted transcript text. */
object HistorySearchTool {
    const val SEARCH = "history_search"
    const val READ = "history_read"

    internal fun handles(name: String): Boolean = name == SEARCH || name == READ

    private const val SEARCH_LIMIT_DEFAULT = 6
    private const val SEARCH_LIMIT_MAX = 8
    private const val EXCERPT_CHARS = 600
    private const val QUERY_CHAR_CAP = 1_200
    private const val KEYWORD_LIMIT = 8
    private const val PAGE_CHARS = 4_000
    private const val MAX_SEARCH_ROWS = 512
    private const val ROWS_PER_QUERY = 32
    private const val MAX_SEARCH_BYTES = 4L * ResourceLimits.MAX_DECLARED_UNCOMPRESSED
    // Control characters may cost six characters after JSON escaping. Leave room
    // for metadata so the generic output policy cannot turn valid JSON into a spill notice.
    private const val MAX_JSON_TEXT = 12_000
    private const val MAX_ID_CHARS = 128
    private val latinTerm = Regex("[A-Za-z0-9_]{2,}")

    fun searchDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = SEARCH,
        description = "Search earlier messages in this conversation only. With a compaction marker, " +
            "searches only the folded region. Returns up to 8 hits with message_id, role, excerpt, " +
            "and excerpt_start/excerpt_end code-point offsets. Call history_read at that offset for more text. " +
            "Hits marked preview_only come from stored tool previews. Nonzero preview_only_rows or unavailable_rows " +
            "indicate incomplete coverage, even when no hits were found.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short status, e.g. 'Find migration decision'."),
            "query" to AgentToolParam("string", "Keywords to match. CJK runs are split into bigrams."),
            "role" to AgentToolParam("string", "Optional filter: 'user', 'assistant', or 'tool'."),
            "scope" to AgentToolParam("string", "Optional 'folded' or 'all'; all includes the active region."),
            "limit" to AgentToolParam("integer", "Max hits (default 6, max 8)."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "role", "scope", "limit"),
    )

    fun readDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = READ,
        description = "Read one message from this session's searchable history by message_id. " +
            "Returns up to 4000 code points from offset. With preview_only, the full tool output is unavailable " +
            "through history and a null next_offset means only the end of the stored preview. " +
            "Credentials and host-only data are excluded.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short status, e.g. 'Read that message'."),
            "message_id" to AgentToolParam("string", "ID returned by history_search."),
            "offset" to AgentToolParam("integer", "Code-point offset to start at (default 0)."),
        ),
        required = listOf("tool_title", "message_id"),
        propertyOrdering = listOf("tool_title", "message_id", "offset"),
    )

    internal fun keywords(query: String): List<String> {
        val capped = query.take(QUERY_CHAR_CAP)
        val out = linkedSetOf<String>()
        for (m in latinTerm.findAll(capped)) {
            out += m.value.lowercase()
            if (out.size >= KEYWORD_LIMIT) return out.toList()
        }
        var runStart = 0
        for (end in 0..capped.length) {
            val ch = capped.getOrNull(end)
            if (ch != null && (ch.code in 0x4E00..0x9FFF || ch.code in 0x3040..0x30FF)) continue
            if (end - runStart == 1) out += capped.substring(runStart, end)
            for (i in runStart until end - 1) {
                out += capped.substring(i, i + 2)
                if (out.size >= KEYWORD_LIMIT) return out.toList()
            }
            if (out.size >= KEYWORD_LIMIT) break
            runStart = end + 1
        }
        return out.toList()
    }

    internal fun excerptAt(text: String, needle: String, width: Int = EXCERPT_CHARS): Triple<String, Int, Int>? {
        if (needle.isEmpty()) return null
        val match = text.indexOf(needle, ignoreCase = true)
        if (match < 0) return null
        val size = text.codePointCount(0, text.length)
        val found = text.codePointCount(0, match)
        val start = (found - width / 2).coerceIn(0, (size - width).coerceAtLeast(0))
        val end = minOf(size, start + width)
        val from = text.offsetByCodePoints(0, start)
        return Triple(text.substring(from, text.offsetByCodePoints(from, end - start)), start, end)
    }

    internal fun page(text: String, offset: Int, limit: Int = PAGE_CHARS): Triple<String, Int?, Boolean> {
        val size = text.codePointCount(0, text.length)
        val start = offset.coerceIn(0, size)
        val end = start + minOf(limit, size - start)
        val from = text.offsetByCodePoints(0, start)
        val next = end.takeIf { it < size }
        return Triple(text.substring(from, text.offsetByCodePoints(from, end - start)), next, next != null)
    }

    private fun repo(context: Context): ChatRepository? = (context.applicationContext as? MinisApp)?.chatRepositoryOrNull

    suspend fun executeSearch(argsJson: String, sessionId: String, context: Context): ToolExecutionResult =
        executeSearch(argsJson, sessionId, repo(context))

    suspend fun executeRead(argsJson: String, sessionId: String, context: Context): ToolExecutionResult =
        executeRead(argsJson, sessionId, repo(context))

    internal suspend fun executeSearch(argsJson: String, sessionId: String, repo: ChatRepository?): ToolExecutionResult =
        execute(SEARCH, argsJson, sessionId, repo) search@{ args, title, history ->
            val query = args.opt("query") as? String
            val words = keywords(query.orEmpty())
            if (words.isEmpty()) return@search error(title, "A query with searchable words is required.", ToolErrorCode.INVALID_ARGUMENTS)
            val role = (args.opt("role") as? String)?.trim()?.lowercase()
            if (args.has("role") && role !in setOf("user", "assistant", "tool")) {
                return@search error(title, "Role must be user, assistant, or tool.", ToolErrorCode.INVALID_ARGUMENTS)
            }
            val limit = args.optInt("limit", SEARCH_LIMIT_DEFAULT).coerceIn(1, SEARCH_LIMIT_MAX)
            val requestedScope = (args.opt("scope") as? String)?.trim()?.lowercase()
            if (args.has("scope") && requestedScope !in setOf("folded", "all")) {
                return@search error(title, "Scope must be folded or all.", ToolErrorCode.INVALID_ARGUMENTS)
            }
            val scope = if (requestedScope == "all") HistoryScope.WholeSession else history.scope()
            if (scope == HistoryScope.Unavailable) return@search unavailable(title)
            val hits = JSONArray()
            var previewOnlyRows = 0
            var unavailableRows = 0
            var hitChars = 0
            var before = Long.MAX_VALUE
            var rowsLeft = MAX_SEARCH_ROWS
            var bytesLeft = MAX_SEARCH_BYTES
            var exhausted = false
            var outputLimited = false
            scan@while (rowsLeft > 0) {
                val rows = history.rows(scope, before, role, minOf(rowsLeft, ROWS_PER_QUERY))
                if (rows.isEmpty()) {
                    exhausted = true
                    break
                }
                for (row in rows) {
                    currentCoroutineContext().ensureActive()
                    rowsLeft--
                    var overBudget = false
                    val body = history.body(scope, row) { size ->
                        overBudget = size > bytesLeft
                        if (!overBudget) bytesLeft -= size
                        !overBudget
                    }
                    if (overBudget) break@scan
                    val message = when (body) {
                        HistoryBody.Unavailable -> {
                            // An unreadable user-role carrier could contain tool results.
                            unavailableRows++
                            continue
                        }
                        HistoryBody.NoText -> continue
                        is HistoryBody.Text -> body
                    }
                    if (role != null && role != message.role) continue
                    // Count before matching: an omitted middle may hold the only hit.
                    if (message.previewOnly) previewOnlyRows++
                    if (message.messageId.length > MAX_ID_CHARS) continue
                    val text = safeTranscript(message.text)
                    val (excerpt, start, end) = words.firstNotNullOfOrNull { excerptAt(text, it) } ?: continue
                    val hit = JSONObject().put("message_id", message.messageId).put("role", message.role)
                        .put("excerpt", excerpt).put("excerpt_start", start).put("excerpt_end", end)
                    if (message.previewOnly) hit.put("preview_only", true)
                    hitChars += hit.toString().length + 1
                    if (hitChars > MAX_JSON_TEXT) {
                        outputLimited = true
                        break@scan
                    }
                    hits.put(hit)
                    if (hits.length() >= limit) break@scan
                }
                before = rows.last().sortOrder.toLong()
            }
            if (history.scope() != scope) return@search unavailable(title)
            val output = JSONObject().put("hits", hits).put("count", hits.length())
                .put("scope", if (scope is HistoryScope.Folded) "folded" else "session")
                .put("truncated", unavailableRows > 0 || previewOnlyRows > 0 || outputLimited || (!exhausted && hits.length() < limit))
                .put("preview_only_rows", previewOnlyRows)
                .put("unavailable_rows", unavailableRows)
            if (unavailableRows > 0 || previewOnlyRows > 0) {
                output.put("coverage_note", buildString {
                    if (unavailableRows > 0) append("Some message bodies were unavailable and could not be searched.")
                    if (previewOnlyRows > 0) {
                        if (isNotEmpty()) append(' ')
                        append("Some tool outputs were searched as previews only. Their full text is unavailable in history.")
                    }
                })
            }
            ToolExecutionResult(output.toString(), true, toolTitle = title)
        }

    internal suspend fun executeRead(argsJson: String, sessionId: String, repo: ChatRepository?): ToolExecutionResult =
        execute(READ, argsJson, sessionId, repo) read@{ args, title, history ->
            val id = (args.opt("message_id") as? String)?.trim().orEmpty()
            if (id.isEmpty() || id.length > MAX_ID_CHARS) {
                return@read error(title, "A valid message_id is required.", ToolErrorCode.INVALID_ARGUMENTS)
            }
            val offset = if (args.has("offset")) (args.opt("offset") as? Number)?.toString()?.toIntOrNull() else 0
            if (offset == null || offset < 0) {
                return@read error(title, "Offset must be a non-negative integer.", ToolErrorCode.INVALID_ARGUMENTS)
            }
            var scope = history.scope()
            if (scope == HistoryScope.Unavailable) return@read notFound(title)
            var row = history.row(scope, id)
            if (row == null && scope != HistoryScope.WholeSession) {
                scope = HistoryScope.WholeSession
                row = history.row(scope, id)
            }
            if (row == null) return@read notFound(title)
            val message = history.body(scope, row) as? HistoryBody.Text ?: return@read notFound(title)
            if (scope is HistoryScope.Folded && history.scope() != scope) return@read notFound(title)
            val text = safeTranscript(message.text)
            if (offset > text.codePointCount(0, text.length)) {
                val detail = if (message.previewOnly) {
                    "Offset is outside the stored preview. The full tool output is unavailable in history."
                } else "Offset is beyond the end of the message."
                return@read error(title, detail, ToolErrorCode.INVALID_ARGUMENTS)
            }
            var cap = PAGE_CHARS
            var (body, next, truncated) = page(text, offset, cap)
            while (JSONObject.quote(body).length > MAX_JSON_TEXT) {
                cap /= 2
                val smaller = page(text, offset, cap)
                body = smaller.first
                next = smaller.second
                truncated = smaller.third
            }
            val output = JSONObject().put("message_id", message.messageId).put("role", message.role)
                .put("offset", offset).put("text", body).put("next_offset", next ?: JSONObject.NULL)
                .put("truncated", truncated || message.previewOnly)
            if (message.previewOnly) {
                output.put("preview_only", true)
                    .put("coverage_note", "Only the stored preview is available. A null next_offset is the end of the preview, not the full tool output.")
            }
            ToolExecutionResult(output.toString(), true, toolTitle = title)
        }

    private suspend fun execute(
        name: String,
        argsJson: String,
        sessionId: String,
        repo: ChatRepository?,
        action: suspend (JSONObject, String, SessionHistory) -> ToolExecutionResult,
    ): ToolExecutionResult {
        if (repo == null || sessionId.isBlank()) return unavailable(name)
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            return error(name, "Invalid history arguments.", ToolErrorCode.INVALID_ARGUMENTS)
        } catch (_: StackOverflowError) {
            return error(name, "Invalid history arguments.", ToolErrorCode.INVALID_ARGUMENTS)
        }
        val title = safeTranscript((args.opt("tool_title") as? String).orEmpty()).take(100).ifBlank { name }
        return try {
            withContext(Dispatchers.IO) { action(args, title, SessionHistory(repo, sessionId)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            unavailable(title) // Do not expose DB diagnostics, paths, or input in error output.
        }
    }

    private fun safeTranscript(text: String): String = SecretMasking.mask(ToolOutputPolicy.redactEnvVars(text))
    private fun error(title: String, detail: String, code: ToolErrorCode) =
        ToolExecutionResult("Error: $detail", false, errorCode = code, toolTitle = title)

    private fun unavailable(title: String) = error(title, "This session's history is unavailable.", ToolErrorCode.EXECUTION_FAILED)
    private fun notFound(title: String) = error(title, "Message not available in this session's searchable history.", ToolErrorCode.NOT_FOUND)
}
