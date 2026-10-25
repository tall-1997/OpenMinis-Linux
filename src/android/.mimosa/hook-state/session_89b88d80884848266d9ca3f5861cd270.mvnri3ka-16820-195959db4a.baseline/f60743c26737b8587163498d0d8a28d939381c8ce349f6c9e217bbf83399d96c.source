package com.openminis.app.data.repository

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-server MCP tool switches. Written beside servers.json so the guest
 * daemon (bind-mounted at /var/minis/mcp-servers) rejects disabled calls.
 */
object MCPToolPolicy {
    private const val FILE = "disabled-tools.json"

    data class Invocation(val subcommand: String, val server: String, val tool: String?)

    fun policyFile(context: Context): File =
        File(File(context.filesDir, "minis-global/mcp-servers"), FILE)

    fun disabled(context: Context, serverId: String): Set<String> = read(context)[serverId].orEmpty()

    fun isDisabled(context: Context, serverId: String, tool: String): Boolean =
        tool in disabled(context, serverId)

    fun setEnabled(context: Context, serverId: String, tool: String, enabled: Boolean) {
        val map = read(context).toMutableMap()
        val set = map[serverId].orEmpty().toMutableSet()
        if (enabled) set.remove(tool) else set.add(tool)
        if (set.isEmpty()) map.remove(serverId) else map[serverId] = set
        write(context, map)
    }

    /** Immediate host-side rejection for parsed `minis-mcp-cli call` commands.
     *  [T-mcp-policy-chained-bypass] Scans EVERY invocation in the command — a
     *  chained `call a t1 && call b t2` used to be judged on the first alone,
     *  letting a disabled t2 through the gate. */
    fun blockedMessage(context: Context, command: String): String? {
        for (call in parseAll(command)) {
            if (call.subcommand != "call" || call.tool.isNullOrBlank()) continue
            if (!isDisabled(context, call.server, call.tool)) continue
            return "MCP tool \"${call.tool}\" on ${call.server} is disabled in Settings."
        }
        return null
    }

    fun filterToolsOutput(context: Context, command: String, output: String): String {
        val listed = parse(command) ?: return output
        if (listed.subcommand != "tools") return output
        val disabled = disabled(context, listed.server)
        if (disabled.isEmpty()) return output
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return output
        return try {
            val obj = JSONObject(output.substring(start, end + 1))
            val tools = obj.optJSONArray("tools") ?: return output
            val kept = JSONArray()
            for (i in 0 until tools.length()) {
                val item = tools.get(i)
                val name = when (item) {
                    is JSONObject -> item.optString("name")
                    is String -> item
                    else -> ""
                }
                if (name.isNotBlank() && name !in disabled) kept.put(item)
            }
            obj.put("tools", kept)
            obj.put("count", kept.length())
            output.substring(0, start) + obj.toString(2) + output.substring(end + 1)
        } catch (_: Exception) {
            output
        }
    }

    fun parse(command: String): Invocation? = parseAll(command).firstOrNull()

    /** Every minis-mcp-cli invocation found in [command], in order. */
    fun parseAll(command: String): List<Invocation> {
        val tokens = tokenize(command)
        val out = mutableListOf<Invocation>()
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token != "minis-mcp-cli" && !token.endsWith("/minis-mcp-cli")) continue
            var j = i + 1
            j = skipFlags(tokens, j)
            val sub = tokens.getOrNull(j) ?: break
            if (sub != "call" && sub != "tools") continue
            j = skipFlags(tokens, j + 1)
            val server = tokens.getOrNull(j)?.trim('"', '\'') ?: break
            if (sub == "tools") {
                out.add(Invocation(sub, server, null))
                continue
            }
            j = skipFlags(tokens, j + 1)
            val tool = tokens.getOrNull(j)?.trim('"', '\'') ?: break
            out.add(Invocation(sub, server, tool))
        }
        return out
    }

    private fun skipFlags(tokens: List<String>, start: Int): Int {
        var j = start
        while (j < tokens.size && tokens[j].startsWith("-")) {
            if (tokens[j] == "--input" || tokens[j] == "--name" || tokens[j] == "--url" || tokens[j] == "--command") {
                j += 2
            } else {
                j += 1
            }
        }
        return j
    }

    private fun tokenize(command: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        for (ch in command) {
            when {
                quote != null -> if (ch == quote) quote = null else sb.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() || ch == ';' || ch == '|' || ch == '&' -> {
                    if (sb.isNotEmpty()) {
                        out.add(sb.toString())
                        sb.clear()
                    }
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun read(context: Context): Map<String, Set<String>> {
        val file = policyFile(context)
        if (!file.exists()) return emptyMap()
        return try {
            val obj = JSONObject(file.readText())
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = obj.optJSONArray(key) ?: continue
                    val names = buildSet {
                        for (i in 0 until arr.length()) {
                            val name = arr.optString(i)
                            if (name.isNotBlank()) add(name)
                        }
                    }
                    if (names.isNotEmpty()) put(key, names)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun write(context: Context, map: Map<String, Set<String>>) {
        val file = policyFile(context)
        file.parentFile?.mkdirs()
        val obj = JSONObject()
        for ((server, tools) in map) {
            val arr = JSONArray()
            tools.sorted().forEach { arr.put(it) }
            obj.put(server, arr)
        }
        // [T-mcp-policy-atomic-write] The guest daemon reads this file on every
        // call; a torn write lands in its catch-all and enables EVERY tool for
        // that window. Temp+rename, same discipline as servers.json.
        writeTextAtomic(file, obj.toString())
    }
}
