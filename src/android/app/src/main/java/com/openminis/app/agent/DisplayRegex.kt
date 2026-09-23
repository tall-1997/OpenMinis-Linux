package com.openminis.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bubble-only text rewrite. Rules never touch the string stored on the
 * message or the payload sent to the model — callers must apply this at
 * render time only.
 */
object DisplayRegex {
    enum class Scope { USER, ASSISTANT }

    data class Rule(
        val id: String,
        val name: String,
        val pattern: String,
        val replacement: String,
        val scopes: Set<Scope>,
        val enabled: Boolean = true,
        val visualOnly: Boolean = true,
    )

    fun file(context: Context): File =
        File(context.filesDir, "minis-global/memory/display-regex.json")

    private var cachedStamp = Long.MIN_VALUE
    private var cachedRules: List<Rule> = emptyList()

    fun load(context: Context): List<Rule> {
        val f = file(context)
        val stamp = if (f.isFile) f.lastModified() xor f.length() else 0L
        if (stamp == cachedStamp) return cachedRules
        val rules = if (!f.isFile) {
            emptyList()
        } else {
            runCatching { parse(f.readText()) }.getOrDefault(emptyList())
        }
        cachedRules = rules
        cachedStamp = stamp
        return rules
    }

    fun save(context: Context, rules: List<Rule>) {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("pattern", r.pattern)
                    .put("replacement", r.replacement)
                    .put("scopes", JSONArray(r.scopes.map { it.name }))
                    .put("enabled", r.enabled)
                    .put("visualOnly", r.visualOnly),
            )
        }
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(arr.toString())
        cachedStamp = Long.MIN_VALUE
    }

    fun apply(context: Context, text: String, scope: Scope): String =
        apply(load(context), text, scope)

    internal fun parse(json: String): List<Rule> {
        val arr = JSONArray(json)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pattern = o.optString("pattern")
                if (pattern.isBlank()) continue
                val scopes = o.optJSONArray("scopes")?.let { a ->
                    buildSet {
                        for (k in 0 until a.length()) {
                            runCatching { Scope.valueOf(a.optString(k)) }.getOrNull()?.let { add(it) }
                        }
                    }
                }.orEmpty().ifEmpty { setOf(Scope.ASSISTANT) }
                add(
                    Rule(
                        id = o.optString("id").ifBlank { "rx-$i" },
                        name = o.optString("name").ifBlank { pattern },
                        pattern = pattern,
                        replacement = o.optString("replacement"),
                        scopes = scopes,
                        enabled = o.optBoolean("enabled", true),
                        visualOnly = o.optBoolean("visualOnly", true),
                    ),
                )
            }
        }
    }

    internal fun apply(rules: List<Rule>, text: String, scope: Scope): String {
        var out = text
        for (rule in rules) {
            if (!rule.enabled || !rule.visualOnly || scope !in rule.scopes) continue
            out = runCatching {
                Regex(rule.pattern).replace(out, rule.replacement)
            }.getOrDefault(out)
        }
        return out
    }
}
