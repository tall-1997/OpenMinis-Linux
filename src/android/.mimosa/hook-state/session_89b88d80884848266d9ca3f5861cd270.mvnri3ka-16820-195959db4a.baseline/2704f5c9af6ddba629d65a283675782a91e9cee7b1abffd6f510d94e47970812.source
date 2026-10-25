package com.openminis.app.accessibility

/**
 * Instruction-similarity matching over recorded a11y skills.
 *
 * - English / latin text: whitespace-token Jaccard.
 * - Chinese: character bigram Jaccard (no segmentation needed).
 * - Mixed text: max of both scores.
 * - Exact name or exact instruction match dominates (score 1.0).
 * - Stale skills never match (they stay listed / deletable only).
 *
 * [MATCH_THRESHOLD] is deliberately conservative (0.22) — replaying the wrong
 * skill is far worse than reprompting the user; tune upward before downward.
 */
object SkillMatcher {

    const val MATCH_THRESHOLD = 0.22

    data class Match(val skill: A11ySkillStore.Skill, val score: Double)

    fun bestMatch(instruction: String, skills: List<A11ySkillStore.Skill>): Match? {
        val q = instruction.trim()
        if (q.isEmpty()) return null
        var best: Match? = null
        for (s in skills) {
            if (s.stale) continue
            var score = similarity(q, s.instruction)
            if (s.name.equals(q, ignoreCase = true) || s.instruction.equals(q, ignoreCase = true)) {
                score = 1.0
            }
            if (score > (best?.score ?: -1.0)) best = Match(s, score)
        }
        return best?.takeIf { it.score >= MATCH_THRESHOLD }
    }

    fun similarity(a: String, b: String): Double =
        maxOf(tokenJaccard(a, b), charBigramJaccard(a, b))

    /** English-style token Jaccard (split on non letter/digit runs). */
    fun tokenJaccard(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return jaccard(ta, tb)
    }

    /** Chinese character bigram Jaccard (spaces stripped first). */
    fun charBigramJaccard(a: String, b: String): Double {
        val ba = bigrams(a)
        val bb = bigrams(b)
        if (ba.isEmpty() || bb.isEmpty()) return 0.0
        return jaccard(ba, bb)
    }

    private fun tokens(s: String): Set<String> =
        s.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .toSet()

    private fun bigrams(s: String): Set<String> {
        val t = s.replace(Regex("\\s+"), "")
        if (t.isEmpty()) return emptySet()
        if (t.length == 1) return setOf(t)
        return (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()
    }

    private fun <T> jaccard(a: Set<T>, b: Set<T>): Double {
        val union = a.union(b).size
        if (union == 0) return 0.0
        return a.intersect(b).size.toDouble() / union
    }
}
