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
 * Deliberately NOT registered in the main-session tool list: the coordinator
 * already has shell_execute for this. See AgentTools.makeSubAgentExtraTools.
 */
object GrepSourceTool {
    const val NAME = "grep_source"

    private const val DEFAULT_CONTEXT = 2
    private const val MAX_CONTEXT = 6
    private const val MAX_MATCHES = 50
    private const val MAX_FILES = 200
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val OUTPUT_HARD_CAP = 40_000

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search file(s) for a pattern and return matching lines with ±context in ONE call. Prefer this over paging file_read through a big file or running shell grep — it saves turns. Sub-agents only.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this search does, shown to the user."),
            "pattern" to AgentToolParam("string", "Text or regex to match (case-insensitive when is_regex=false)."),
            "path" to AgentToolParam("string", "Absolute file or directory path to search (default /var/minis/workspace)."),
            "context" to AgentToolParam("integer", "Lines of context around each match (0-6, default 2)."),
            "is_regex" to AgentToolParam("boolean", "Treat pattern as a regex (default false = literal substring)."),
            "max_matches" to AgentToolParam("integer", "Cap on matches returned (default 50)."),
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
            val ctx = args.optInt("context", DEFAULT_CONTEXT).coerceIn(0, MAX_CONTEXT)
            val isRegex = args.optBoolean("is_regex", false)
            val maxMatches = args.optInt("max_matches", MAX_MATCHES).coerceIn(1, 200)

            val matcher: (String) -> Boolean = if (isRegex) {
                val re = try {
                    Regex(pattern, setOf(RegexOption.IGNORE_CASE))
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

            val files: List<java.io.File> = when {
                root.isFile -> listOf(root)
                root.isDirectory -> root.walkTopDown()
                    .filter { it.isFile && it.length() in 1..MAX_FILE_BYTES.toLong() }
                    .take(MAX_FILES)
                    .toList()
                else -> emptyList()
            }
            if (files.isEmpty()) {
                return ToolExecutionResult("No searchable files under: $rawPath", false, toolTitle = toolTitle)
            }

            val out = StringBuilder()
            var totalMatches = 0
            var hitFiles = 0
            var scanned = 0
            for (f in files) {
                if (totalMatches >= maxMatches) break
                scanned++
                val lines = try {
                    f.bufferedReader().useLines { it.toList() }
                } catch (_: Exception) {
                    continue // binary or unreadable — skip
                }
                val hitIdx = lines.indices.filter { matcher(lines[it]) }
                if (hitIdx.isEmpty()) continue
                hitFiles++
                out.append("=== ").append(f.absolutePath).append(" ===\n")
                var lastPrinted = -1
                for (i in hitIdx) {
                    if (totalMatches >= maxMatches) {
                        out.append("  … more matches in this file (cap) …\n")
                        break
                    }
                    val lo = (i - ctx).coerceAtLeast(0)
                    val hi = (i + ctx).coerceAtMost(lines.size - 1)
                    if (lo > lastPrinted + 1 && lastPrinted >= 0) out.append("  --\n")
                    for (n in lo..hi) {
                        if (n <= lastPrinted) continue
                        val mark = if (n == i) ">" else " "
                        out.append(mark).append(n + 1).append(": ").append(lines[n]).append('\n')
                        lastPrinted = n
                    }
                    totalMatches++
                }
            }

            if (totalMatches == 0) {
                return ToolExecutionResult(
                    "No matches for \"$pattern\" in $rawPath (scanned $scanned file(s))",
                    true, toolTitle = toolTitle,
                )
            }

            var body = out.toString().trimEnd()
            var note = ""
            if (body.length > OUTPUT_HARD_CAP) {
                body = body.take(OUTPUT_HARD_CAP)
                note = "\n…[output truncated at ${OUTPUT_HARD_CAP} chars — narrow path or pattern]…"
            }
            val header = buildString {
                append("[grep_source: \"$pattern\" in $rawPath — ")
                append(totalMatches).append(" match(es) across ").append(hitFiles)
                append(" file(s), scanned ").append(scanned).append("]")
                if (totalMatches >= maxMatches) append(" (match cap reached)")
            }
            ToolExecutionResult(header + "\n\n" + body + note, true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false, toolTitle = toolTitle)
        }
    }
}
