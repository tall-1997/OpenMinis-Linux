package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

/**
 * Workspace glob. Paths go through [PRootKernel].
 *
 * Adapted from XINCODE-Public GlobTool (GPL-3.0-or-later).
 */
object GlobTool {
    const val NAME = "glob"
    private const val MAX_HITS = 500
    private const val MAX_WALK = 4_000

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Find files by glob (e.g. **/*.kt) under a Linux path. Prefer this over find/ls pipelines.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "pattern" to AgentToolParam("string", "Glob pattern. * matches one path segment, ** matches recursively."),
            "path" to AgentToolParam("string", "Root Linux path (default /var/minis/workspace)."),
        ),
        required = listOf("tool_title", "pattern"),
        propertyOrdering = listOf("tool_title", "pattern", "path"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", NAME) } catch (_: Exception) { NAME }
        return try {
            val args = JSONObject(argsJson)
            val pattern = args.optString("pattern", "")
            if (pattern.isBlank()) return ToolExecutionResult("Error: pattern required", false, toolTitle = toolTitle)
            val path = args.optString("path", "/var/minis/workspace").ifBlank { "/var/minis/workspace" }
            val root = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)
            if (!root.exists()) return ToolExecutionResult("Error: not found: $path", false, toolTitle = toolTitle)
            val regex = globToRegex(pattern)
            val hits = mutableListOf<File>()
            var walked = 0
            fun walk(dir: File) {
                if (walked > MAX_WALK || hits.size >= MAX_HITS) return
                val kids = dir.listFiles() ?: return
                for (f in kids) {
                    walked++
                    if (f.name.startsWith(".git")) continue
                    val rel = f.relativeTo(root).path.replace('\\', '/')
                    if (f.isFile && regex.matches(rel)) hits += f
                    if (f.isDirectory) walk(f)
                    if (hits.size >= MAX_HITS) return
                }
            }
            if (root.isFile) {
                if (regex.matches(root.name)) hits += root
            } else {
                walk(root)
            }
            val sorted = hits.sortedByDescending { it.lastModified() }.take(MAX_HITS)
            val body = buildString {
                append("${sorted.size} files for $pattern under $path\n")
                for (f in sorted) {
                    val rel = if (root.isDirectory) f.relativeTo(root).path.replace('\\', '/') else f.name
                    append(rel)
                    append('\n')
                }
            }
            ToolExecutionResult(body, true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error glob: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    internal fun globToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            when {
                pattern.startsWith("**/", i) -> {
                    sb.append("(?:.*/)?")
                    i += 3
                }
                pattern.startsWith("**", i) -> {
                    sb.append(".*")
                    i += 2
                }
                pattern[i] == '*' -> {
                    sb.append("[^/]*")
                    i++
                }
                pattern[i] == '?' -> {
                    sb.append("[^/]")
                    i++
                }
                else -> {
                    val c = pattern[i]
                    if (c in ".$()[]{}+^|\\") sb.append('\\')
                    sb.append(c)
                    i++
                }
            }
        }
        sb.append('$')
        return Regex(sb.toString())
    }
}
