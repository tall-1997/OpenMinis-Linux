package com.openminis.app.evolution

import com.openminis.app.text.BoundedText

/**
 * Pure belief-tier / merge / contradiction helpers. No Android, no I/O.
 *
 * Tiers: draft → established → core. Unreinforced established beliefs go
 * stale then decayed. Core is only demoted by an accepted retract proposal.
 */
object BeliefMaintenance {

    const val CORE_HITS = 5
    const val STALE_MS = 21L * 24 * 60 * 60 * 1000
    const val DECAY_MS = 42L * 24 * 60 * 60 * 1000

    const val DRAFT = "draft"
    const val ESTABLISHED = "established"
    const val CORE = "core"
    const val STALE = "stale"
    const val DECAYED = "decayed"

    private val STOP = setOf(
        "the", "a", "an", "to", "of", "and", "or", "for", "in", "on", "with",
        "prefer", "always", "never", "don't", "do", "use", "using",
        "以后", "都", "用", "不要", "不要再", "别", "必须", "记住", "请",
    )

    private val NEGATION = listOf(
        "不要再", "不要用", "别再", "别用", "不是这样", "不对", "错了",
        "don't", "never", "stop doing", "that's wrong",
    )

    fun nextTier(
        hitCount: Int,
        lastHitAt: Long,
        current: String,
        now: Long,
        acceptedCore: Boolean,
    ): String {
        if (acceptedCore || hitCount >= CORE_HITS) return CORE
        if (current == CORE) return CORE
        val age = (now - lastHitAt).coerceAtLeast(0L)
        if (age >= DECAY_MS) return DECAYED
        if (age >= STALE_MS && (current == ESTABLISHED || current == STALE)) return STALE
        if (hitCount >= 2) return ESTABLISHED
        return DRAFT
    }

    fun tokens(text: String): Set<String> {
        val window = BoundedText.icuWindow(text).toString().lowercase()
        return window
            .split(Regex("[\\p{Punct}\\s]+"))
            .filter { it.length >= 2 && it !in STOP }
            .toSet()
    }

    fun shouldMerge(a: String, b: String): Boolean {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val inter = ta.intersect(tb).size
        val union = ta.union(tb).size
        if (inter >= 3) return true
        return union > 0 && inter * 2 >= union
    }

    /**
     * Later user utterance contradicts an approved rule: correction signal +
     * shared content tokens + a negation. Task-diary lines should be filtered
     * by the caller before this.
     */
    fun contradicts(rule: String, userText: String): Boolean {
        if (!CorrectionDetector.matches(userText)) return false
        if (NEGATION.none { userText.contains(it, ignoreCase = true) }) return false
        val overlap = tokens(rule).intersect(tokens(userText))
        return overlap.size >= 2
    }
}
