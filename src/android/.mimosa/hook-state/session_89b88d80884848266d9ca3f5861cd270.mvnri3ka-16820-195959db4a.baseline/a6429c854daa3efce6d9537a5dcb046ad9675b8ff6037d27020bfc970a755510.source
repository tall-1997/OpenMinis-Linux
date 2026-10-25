package com.openminis.app.evolution

import org.json.JSONArray
import org.json.JSONObject

object EvolutionJson {

    fun parseObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        runCatching { return JSONObject(trimmed) }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return runCatching { JSONObject(trimmed.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }

    fun parseRules(raw: String): List<String> {
        val obj = parseObject(raw) ?: return emptyList()
        if (obj.optBoolean("skip", false)) return emptyList()
        val single = obj.optString("rule", "").trim()
        val out = mutableListOf<String>()
        if (single.isNotBlank()) out.add(single)
        val arr: JSONArray? = obj.optJSONArray("rules")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "").trim()
                if (item.isNotBlank()) out.add(item)
            }
        }
        return out.mapNotNull { LearnedPrefsStore.normalize(it) }.distinct()
    }

    fun parseSkillPatch(raw: String): String? {
        val obj = parseObject(raw) ?: return null
        if (obj.optBoolean("skip", false)) return null
        val patch = obj.optString("patch", "").trim()
        return patch.takeIf { it.isNotBlank() }
    }

    data class ReflectItem(
        val action: String,
        val rule: String,
        val replacement: String?,
        val evidence: String?,
    )

    fun parseReflect(raw: String): List<ReflectItem> {
        val obj = parseObject(raw) ?: return emptyList()
        if (obj.optBoolean("skip", false)) return emptyList()
        val arr = obj.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<ReflectItem>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val action = item.optString("action", "").trim()
            val rule = item.optString("rule", "").trim()
            if (action.isBlank() || rule.isBlank()) continue
            out.add(
                ReflectItem(
                    action = action,
                    rule = rule,
                    replacement = item.optString("replacement", "").trim().ifBlank { null },
                    evidence = item.optString("evidence", "").trim().ifBlank { null },
                ),
            )
        }
        return out
    }
}
