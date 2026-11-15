package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject

/**
 * `grep_source` — sub-agent-only fast source search.
 *
 * Returns every line matching a pattern plus its surrounding context in one
 * call, replacing the "file_read page 1 → page 2 → page 3 …" loop that burns
 * sub-agent turns. Cheaper than shell_execute (`grep -n`) because there is no
 * PersistentShell round-trip, and it can also fan out across a directory.
 *
 * The scan itself lives in [GrepStreamScan]: a bounded streaming pipeline
 * (pi's bounded-enumeration contract) — the tree is never materialized as a
 * list, symlink cycles are broken by a visited set, a wall-clock deadline
 * bounds the whole call, and every truncation reason (deadline, walk guard,
 * match/output cap plus the unscanned remainder) is reported explicitly.
 *
 * Deliberately NOT registered in the main-session tool list: the coordinator
 * already has shell_execute for this. See AgentTools.makeSubAgentExtraTools.
 */
object GrepSourceTool {
    const val NAME = "grep_source"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search ALL files under a path for a pattern and return matching lines with ±context in ONE call (noise dirs like .git/node_modules/build are pruned; files >512 KB are skipped and counted). Prefer this over paging file_read or running shell grep — it saves turns. Sub-agents only.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this search does, shown to the user."),
            "pattern" to AgentToolParam("string", "Text or regex to match (case-insensitive when is_regex=false)."),
            "path" to AgentToolParam("string", "Absolute file or directory path to search (default /var/minis/workspace)."),
            "context" to AgentToolParam("integer", "Lines of context around each match (0-6, default 2)."),
            "is_regex" to AgentToolParam("boolean", "Treat pattern as a regex (default false = literal substring)."),
            "max_matches" to AgentToolParam("integer", "Cap on matches returned (default 50, max 500)."),
        ),
        required = listOf("tool_title", "pattern"),
        propertyOrdering = listOf("tool_title", "pattern", "path", "context", "is_regex", "max_matches"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val args = JSONObject(argsJson)
            val pattern = args.optString("pattern", "")
            if (pattern.isBlank()) {
                return ToolExecutionResult("Error: 'pattern' is required", false, toolTitle = toolTitle)
            }
            val rawPath = args.optString("path", "/var/minis/workspace").ifBlank { "/var/minis/workspace" }
            val ctx = args.optInt("context", GrepStreamScan.DEFAULT_CONTEXT).coerceIn(0, GrepStreamScan.MAX_CONTEXT)
            val isRegex = args.optBoolean("is_regex", false)
            val maxMatches = args.optInt("max_matches", GrepStreamScan.MAX_MATCHES).coerceIn(1, GrepStreamScan.MAX_MATCHES_CAP)

            // [T-grep-source-regex-case] is_regex=true honours the pattern's
            // case (schema contract: "case-insensitive when is_regex=false")
            // — the old ALWAYS-IGNORE_CASE silently widened matches.
            val matcher: (String) -> Boolean = if (isRegex) {
                val re = try {
                    Regex(pattern)
                } catch (e: Exception) {
                    return ToolExecutionResult("Error: invalid regex: ${e.message}", false, toolTitle = toolTitle)
                }
                ({ line -> re.containsMatchIn(line) })
            } else {
                val needle = pattern.lowercase()
                ({ line -> line.lowercase().contains(needle) })
            }

            val root = PRootKernel.resolveSessionHostPath(sessionId, rawPath, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $rawPath", false, toolTitle = toolTitle)
            if (!root.exists()) {
                return ToolExecutionResult("Error: Path not found: $rawPath", false, toolTitle = toolTitle)
            }

            // [T-grep-source-coverage] Bounded streaming scan of the FULL tree
            // (noise dirs pruned, walk never materialized): coverage is bounded
            // by max_matches/output caps, not by a file count that silently
            // truncates the search domain — and every truncation (deadline,
            // walk guard, caps) is reported with the unscanned remainder so
            // the model can narrow the path instead of guessing.
            val stats = GrepStreamScan.scan(
                root,
                matcher,
                GrepStreamScan.Options(maxMatches = maxMatches, contextLines = ctx),
            )
            ToolExecutionResult(
                GrepStreamScan.formatReport(pattern, rawPath, stats, maxMatches),
                true,
                toolTitle = toolTitle,
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false, toolTitle = toolTitle)
        }
    }
}
