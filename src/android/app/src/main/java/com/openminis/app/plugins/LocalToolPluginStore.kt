package com.openminis.app.plugins

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * User-defined local tool plugins — a command template plus named params.
 *
 * The remote [PluginRegistry] catalog registers HTTP API tools; this store
 * covers the other half of a plugin system: locally authored tools whose
 * body is a shell command with `{{param}}` placeholders. The agent calls
 * them like any other tool; the executor substitutes params and runs the
 * command through the normal sandbox shell path.
 */
object LocalToolPluginStore {

    private const val PREFS = "local_tool_plugins"
    private const val KEY = "tools_json"

    data class LocalParam(
        val name: String,
        val description: String = "",
        val required: Boolean = true,
        val defaultValue: String? = null,
    )

    data class LocalToolDef(
        val id: String,
        val name: String,
        val description: String,
        val params: List<LocalParam>,
        /** Command template; `{{param}}` placeholders are substituted at call time. */
        val command: String,
        val enabled: Boolean = true,
        val createdAtMs: Long = 0L,
    )

    // ─── persistence ───────────────────────────────────────────────────

    fun load(context: Context): List<LocalToolDef> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching { parse(text) }.getOrDefault(emptyList())
    }

    fun save(context: Context, tools: List<LocalToolDef>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, serialize(tools))
            .apply()
    }

    fun upsert(context: Context, tool: LocalToolDef) {
        val tools = load(context).filterNot { it.id == tool.id } + tool
        save(context, tools)
    }

    fun remove(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        save(context, load(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun find(context: Context, name: String): LocalToolDef? =
        load(context).firstOrNull { it.enabled && it.name == name }

    // ─── agent surface ─────────────────────────────────────────────────

    /**
     * Agent-facing definitions for the ENABLED local tools. Appended to the
     * tool list in ChatViewModel.agentTools (same slot as the online plugin
     * tools); execution dispatches in ChatViewModelExecuteToolExt.executeTool,
     * which materializes the {{param}} template and runs the command through
     * the normal sandbox shell pipeline.
     */
    fun agentToolDefinitions(context: Context): List<com.openminis.app.data.model.AgentToolDefinition> =
        toAgentDefinitions(load(context))

    /** Pure mapping — unit-testable without a Context. */
    fun toAgentDefinitions(tools: List<LocalToolDef>): List<com.openminis.app.data.model.AgentToolDefinition> =
        tools.filter { it.enabled }.map { def ->
            com.openminis.app.data.model.AgentToolDefinition(
                name = def.name,
                description = def.description.ifBlank {
                    "Locally-defined command-template tool. Command: ${def.command.take(120)}"
                },
                parameters = def.params.associate { p ->
                    p.name to com.openminis.app.data.model.AgentToolParam(
                        type = "string",
                        description = p.description.ifBlank { p.name },
                    )
                },
                required = def.params.filter { it.required && it.defaultValue == null }.map { it.name },
                propertyOrdering = def.params.map { it.name },
            )
        }

    /** Cheap change stamp for tool-list memoization: hash of the raw JSON, no parse. */
    fun toolsStamp(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)?.hashCode()?.toLong() ?: 0L

    // ─── JSON codec ────────────────────────────────────────────────────

    internal fun parse(text: String): List<LocalToolDef> {
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<LocalToolDef>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val command = o.optString("command").trim()
            // name is the agent-facing tool id: must be non-blank and
            // identifier-safe (letters, digits, underscore) so it can be
            // dispatched without quoting concerns.
            if (name.isBlank() || !name.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) continue
            if (command.isBlank()) continue
            val params = mutableListOf<LocalParam>()
            val pj = o.optJSONArray("params")
            if (pj != null) {
                for (k in 0 until pj.length()) {
                    val po = pj.optJSONObject(k) ?: continue
                    val pn = po.optString("name").trim()
                    if (pn.isBlank() || !pn.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) continue
                    params.add(
                        LocalParam(
                            name = pn,
                            description = po.optString("description"),
                            required = po.optBoolean("required", true),
                            defaultValue = po.optString("default").ifBlank { null },
                        ),
                    )
                }
            }
            out.add(
                LocalToolDef(
                    id = o.optString("id").ifBlank { "lt-${name}-${out.size}" },
                    name = name,
                    description = o.optString("description"),
                    params = params,
                    command = command,
                    enabled = o.optBoolean("enabled", true),
                    createdAtMs = o.optLong("created_at", 0L),
                ),
            )
        }
        return out
    }

    internal fun serialize(tools: List<LocalToolDef>): String {
        val arr = JSONArray()
        for (t in tools) {
            val params = JSONArray()
            for (p in t.params) {
                params.put(
                    JSONObject()
                        .put("name", p.name)
                        .put("description", p.description)
                        .put("required", p.required)
                        .put("default", p.defaultValue ?: ""),
                )
            }
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("params", params)
                    .put("command", t.command)
                    .put("enabled", t.enabled)
                    .put("created_at", t.createdAtMs),
            )
        }
        return arr.toString()
    }

    // ─── command materialization ───────────────────────────────────────

    sealed interface MaterializeResult {
        /** Fully substituted, ready-to-run command. */
        data class Ok(val command: String) : MaterializeResult

        /** A required param was missing. */
        data class MissingParam(val param: String) : MaterializeResult

        /** A param value carries shell metacharacters — refuse rather than
         *  interpolate blindly. Callers may quote-escape via [shellQuote] if
         *  the tool author intended free-form text. */
        data class UnsafeParam(val param: String, val value: String) : MaterializeResult
    }

    /**
     * Substitute `{{param}}` placeholders with call arguments. Rules:
     * - missing required param (no default) → [MaterializeResult.MissingParam]
     * - value containing any of `;|&$>` `` ` `` or newline → [MaterializeResult.UnsafeParam]
     *   unless [allowUnsafe] is true (the caller then OWNS quoting)
     * - unknown placeholders are left verbatim so template typos are visible
     *   in the executed command rather than silently dropped
     */
    fun materialize(
        tool: LocalToolDef,
        args: Map<String, String>,
        allowUnsafe: Boolean = false,
    ): MaterializeResult {
        var command = tool.command
        for (p in tool.params) {
            val raw = args[p.name] ?: p.defaultValue ?: run {
                if (p.required) return MaterializeResult.MissingParam(p.name)
                null
            } ?: continue
            if (!allowUnsafe && raw.any { it in ";|&$>`\n" }) {
                return MaterializeResult.UnsafeParam(p.name, raw)
            }
            command = command.replace("{{${p.name}}}", raw)
        }
        return MaterializeResult.Ok(command)
    }

    /** POSIX-style single-quote escaping for [allowUnsafe] values. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Export the store as a portable JSON file (backup / share). */
    fun export(context: Context, target: File): Boolean = runCatching {
        target.writeText(serialize(load(context)))
        true
    }.getOrDefault(false)

    fun import(context: Context, source: File, replace: Boolean): Int {
        val incoming = runCatching { parse(source.readText()) }.getOrDefault(emptyList())
        if (incoming.isEmpty()) return 0
        val merged = if (replace) incoming else {
            val existing = load(context).associateBy { it.id }
            (existing.values + incoming.filter { it.id !in existing }).toList()
        }
        save(context, merged)
        return incoming.size
    }
}
