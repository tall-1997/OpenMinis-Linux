package com.openminis.app.evolution

import com.openminis.app.text.BoundedText

/**
 * Heuristic "user is correcting the agent" detector. ICU-bounded.
 * Explicit correction is enough signal for a Be-ACTIVE proposal;
 * harvest still requires [EvolutionPrefs.MIN_BELIEF_HITS] repeats.
 */
object CorrectionDetector {

    private val ZH_SIGNALS = listOf(
        "不对", "错了", "不是这样", "不要再", "别再", "必须", "记住",
        "以后都", "以后请", "我说过", "应该是", "不要用", "改成", "别用",
    )

    private val EN_REGEXES = listOf(
        Regex("""\bdon't (?:do|ever|use)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnever (?:do|use)\b""", RegexOption.IGNORE_CASE),
        Regex("""\balways (?:do|use)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bremember (?:that|to)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bi told you\b""", RegexOption.IGNORE_CASE),
        Regex("""\bstop doing\b""", RegexOption.IGNORE_CASE),
        Regex("""\bmust always\b""", RegexOption.IGNORE_CASE),
        Regex("""\binstead of\b""", RegexOption.IGNORE_CASE),
        Regex("""\bthat's wrong\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdo not (?:do|use|ever)\b""", RegexOption.IGNORE_CASE),
    )

    fun matches(text: String): Boolean {
        val window = BoundedText.icuWindow(text).toString()
        if (window.isBlank()) return false
        if (ZH_SIGNALS.any { window.contains(it) }) return true
        return EN_REGEXES.any { it.containsMatchIn(window) }
    }

    /**
     * One-bullet hint from the user utterance. Null when [matches] is false.
     * Not a polished rule — LLM may refine when the daily cap allows.
     */
    fun extractHint(text: String): String? {
        if (!matches(text)) return null
        val compact = text.trim().replace(Regex("\\s+"), " ")
        if (compact.isBlank()) return null
        val body = compact.take(120).trim()
        return if (body.startsWith("- ")) body else "- $body"
    }

    fun fingerprint(text: String): String {
        val norm = BoundedText.icuWindow(text).toString()
            .lowercase()
            .replace(Regex("[\\p{P}\\p{S}\\s]+"), " ")
            .trim()
            .take(80)
        return Integer.toHexString(norm.hashCode())
    }
}
