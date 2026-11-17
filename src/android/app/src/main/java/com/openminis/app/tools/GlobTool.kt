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
    internal const val MAX_HITS = 500
    internal const val MAX_WALK = 4_000

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
            ToolExecutionResult(walkAndReport(root, pattern, path), true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error glob: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    /**
     * Walk + report core, extracted from [execute] so a plain-JVM test can
     * drive real directories without PRootKernel/Context.
     *
     * [T-glob-coverage-note] Matches found once the hit cap is full are
     * COUNTED, not dropped silently: the walk keeps running (name matching
     * only — no content reads, same cost class as before) so the result can
     * say "first 500 of N" the way GrepStreamScan reports its match cap,
     * instead of leaving the model to guess what it never saw. A walk-budget
     * stop is reported too — every count is then a lower bound, and the model
     * must know coverage is partial rather than read "500 files" as "all".
     */
    internal fun walkAndReport(root: File, pattern: String, path: String): String {
        val regex = globToRegex(pattern)
        val hits = mutableListOf<File>()
        var walked = 0
        var overflow = 0
        var walkCapped = false
        fun walk(dir: File) {
            if (walked > MAX_WALK) {
                walkCapped = true
                return
            }
            val kids = dir.listFiles() ?: return
            for (f in kids) {
                walked++
                if (walked > MAX_WALK) {
                    walkCapped = true
                    return
                }
                if (f.name.startsWith(".git")) continue
                val rel = f.relativeTo(root).path.replace('\\', '/')
                if (f.isFile && regex.matches(rel)) {
                    if (hits.size < MAX_HITS) hits += f else overflow++
                }
                if (f.isDirectory) walk(f)
            }
        }
        if (root.isFile) {
            if (regex.matches(root.name)) hits += root
        } else {
            walk(root)
        }
        val sorted = hits.sortedByDescending { it.lastModified() }.take(MAX_HITS)
        return buildString {
            append("${sorted.size} files for $pattern under $path")
            // Cap reached → say so with the real total (GrepStreamScan's
            // "(match cap reached)" convention).
            if (overflow > 0) {
                append(
                    " (match cap reached — showing first $MAX_HITS of " +
                        "${sorted.size + overflow} matches; sort/filter or narrow the query for the rest)",
                )
            }
            append('\n')
            for (f in sorted) {
                val rel = if (root.isDirectory) f.relativeTo(root).path.replace('\\', '/') else f.name
                append(rel)
                append('\n')
            }
            if (walkCapped) {
                append("(walk budget of $MAX_WALK entries reached — counts and coverage are partial; narrow the path)\n")
            }
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
