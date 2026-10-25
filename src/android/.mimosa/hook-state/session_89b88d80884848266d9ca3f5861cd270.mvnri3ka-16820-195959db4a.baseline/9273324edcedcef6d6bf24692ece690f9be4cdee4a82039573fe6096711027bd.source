package com.openminis.app.provider

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Parses DataLearner search hits and pretrained-model detail HTML.
 *
 * Search JSON has ids only. Context / max-output / thinking modes live on the
 * detail page: `#basic-info` labels and the RSC payload's `thinkingModes`.
 * There is no public JSON detail API (GET `/api/v4/.../{code}` is 404).
 */
internal object DataLearnerParser {

    private val VARIANT_SUFFIXES = listOf("pro", "instant", "mini", "lite", "nano", "flash")

    data class SearchHit(
        val modelCode: String,
        val abbrName: String = "",
        val aliases: List<String> = emptyList(),
        val reasoningModel: Int = 0,
    )

    data class Detail(
        val contextWindow: Int? = null,
        val maxOutputTokens: Int? = null,
        val supportsReasoning: Boolean? = null,
        val reasoningEffortValues: List<String>? = null,
    )

    fun parseTokenSize(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val raw = text.trim()
        if (raw.contains("暂无") || raw.equals("n/a", ignoreCase = true) ||
            raw.equals("unknown", ignoreCase = true)
        ) {
            return null
        }
        val compact = raw.replace(",", "").replace(" ", "")
            .replace(Regex("(?i)tokens?"), "")
        val match = Regex("""^([\d.]+)([kKmMbB]?)$""").matchEntire(compact) ?: return null
        val n = match.groupValues[1].toDoubleOrNull() ?: return null
        val mul = when (match.groupValues[2].lowercase(Locale.US)) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            "b" -> 1_000_000_000.0
            else -> 1.0
        }
        val tokens = (n * mul).roundToInt()
        return tokens.takeIf { it > 0 }
    }

    fun parseSearchHits(json: String): List<SearchHit> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<SearchHit>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val code = obj.optString("model_code").trim()
            if (code.isEmpty()) continue
            val aliases = mutableListOf<String>()
            val aliasArr = obj.optJSONArray("aliases")
            if (aliasArr != null) {
                for (j in 0 until aliasArr.length()) {
                    aliasArr.optString(j).trim().takeIf { it.isNotEmpty() }?.let { aliases.add(it) }
                }
            }
            out.add(
                SearchHit(
                    modelCode = code,
                    abbrName = obj.optString("model_abbr_name").trim(),
                    aliases = aliases,
                    reasoningModel = obj.optInt("reasoningModel", 0),
                ),
            )
        }
        return out
    }

    /**
     * Same-name search returns siblings (`gpt-6-astra` vs `gpt-6-astra-pro`).
     * Prefer an exact `model_code` match; never pick a `-pro`/`-instant`
     * sibling unless the wanted key itself carries that suffix.
     */
    fun pickSearchHit(
        wantedKey: String,
        displayName: String,
        hits: List<SearchHit>,
    ): SearchHit? {
        if (hits.isEmpty()) return null
        val wanted = normalizeKey(wantedKey)
        if (wanted.isEmpty()) return null
        val name = displayName.trim()

        fun score(hit: SearchHit): Int {
            val code = normalizeKey(hit.modelCode)
            var s = 0
            if (code == wanted) s += 100
            if (normalizeKey(hit.abbrName) == wanted) s += 40
            if (name.isNotEmpty() && hit.abbrName.equals(name, ignoreCase = true)) s += 30
            if (hit.aliases.any { normalizeKey(it) == wanted || it.equals(name, ignoreCase = true) }) {
                s += 20
            }
            if (code.startsWith("$wanted-") || wanted.startsWith("$code-")) s += 5
            s -= variantPenalty(wanted, code) * 50
            return s
        }

        val exact = hits
            .map { it to score(it) }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy({ it.second }, { -variantPenalty(wanted, normalizeKey(it.first.modelCode)) }))
            ?.first
        if (exact != null) return exact
        return ModelAliasMatcher.pickBest(
            wantedKey,
            displayName,
            hits,
            tokensOf = { hit ->
                ModelAliasMatcher.tokens(
                    listOf(hit.modelCode, hit.abbrName).plus(hit.aliases).joinToString(" "),
                ).toSet()
            },
            idOf = { it.modelCode },
        )
    }

    fun parseDetail(html: String): Detail? {
        if (html.isBlank()) return null
        val context = parseTokenSize(labelValue(html, "上下文长度"))
        val maxOutput = parseTokenSize(labelValue(html, "最大输出长度"))
        val reasoningRaw = labelValue(html, "推理过程")
        val supportsReasoning = when {
            reasoningRaw == null -> null
            reasoningRaw.contains("暂无") -> null
            reasoningRaw.contains("支持") && !reasoningRaw.contains("不支持") -> true
            reasoningRaw.contains("不支持") -> false
            else -> null
        }
        val modes = extractThinkingModeKeys(html)
        if (context == null && maxOutput == null && supportsReasoning == null && modes.isNullOrEmpty()) {
            return null
        }
        return Detail(
            contextWindow = context,
            maxOutputTokens = maxOutput,
            supportsReasoning = supportsReasoning,
            reasoningEffortValues = modes,
        )
    }

    fun normalizeKey(id: String): String {
        val bare = id.substringAfterLast('/')
        return bare.lowercase(Locale.US)
            .replace('.', '-')
            .replace('_', '-')
            .trim()
    }

    fun searchQueries(id: String, displayName: String): List<String> {
        val idTail = id.substringAfterLast('/').trim()
        val name = displayName.trim()
        val out = LinkedHashSet<String>()
        if (name.isNotEmpty() && '/' !in name) out.add(name)
        val cleanedName = ModelAliasMatcher.stripNoise(name)
        if (cleanedName.isNotEmpty() && cleanedName != name) out.add(cleanedName)
        if (idTail.isNotEmpty()) out.add(idTail)
        val cleanedId = ModelAliasMatcher.stripNoise(idTail)
        if (cleanedId.isNotEmpty() && cleanedId != idTail) out.add(cleanedId)
        val dotted = idTail.replace(Regex("""(\d)-(\d)"""), "$1.$2")
        if (dotted != idTail && dotted.isNotEmpty()) out.add(dotted)
        return out.toList()
    }

    fun shouldLookup(id: String): Boolean {
        val tail = id.substringAfterLast('/').trim()
        if (tail.length < 3) return false
        if (':' in tail) return false
        if (tail.endsWith(".gguf", ignoreCase = true)) return false
        return true
    }

    fun needsSupplement(
        contextWindow: Int?,
        maxOutputTokens: Int?,
    ): Boolean {
        val missingCtx = contextWindow == null || contextWindow <= 0
        val missingOut = maxOutputTokens == null || maxOutputTokens <= 0
        return missingCtx || missingOut
    }

    private fun variantPenalty(wanted: String, code: String): Int {
        var penalty = 0
        for (suffix in VARIANT_SUFFIXES) {
            val tag = "-$suffix"
            if (code.endsWith(tag) && !wanted.endsWith(tag)) penalty += 1
            if (wanted.endsWith(tag) && !code.endsWith(tag)) penalty += 1
        }
        return penalty
    }

    private fun labelValue(html: String, label: String): String? {
        val needle = "$label</div>"
        val idx = html.indexOf(needle)
        if (idx < 0) return null
        val rest = html.substring(idx + needle.length)
        val match = Regex("""<div[^>]*>\s*([^<]+)""").find(rest) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    /**
     * RSC embeds the array as escaped JSON (`\"thinkingModes\":[...`).
     * Unescape a window, then parse `modeKey` values in declared order.
     */
    internal fun extractThinkingModeKeys(html: String): List<String>? {
        val idx = html.indexOf("thinkingModes")
        if (idx < 0) return null
        val end = minOf(html.length, idx + 24_000)
        val window = html.substring(idx, end).replace("\\\"", "\"")
        val keyAt = window.indexOf("thinkingModes")
        val bracket = window.indexOf('[', keyAt)
        if (bracket < 0) return null
        val arrayJson = sliceJsonArray(window, bracket) ?: return null
        return try {
            val arr = JSONArray(arrayJson)
            val keys = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val key = obj.optString("modeKey").trim().lowercase(Locale.US)
                if (key.isNotEmpty()) keys.add(key)
            }
            keys.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun sliceJsonArray(src: String, start: Int): String? {
        if (start >= src.length || src[start] != '[') return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until src.length) {
            val ch = src[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    ch == '\\' -> esc = true
                    ch == '"' -> inStr = false
                }
                continue
            }
            when (ch) {
                '"' -> inStr = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return src.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
