package com.openminis.app.evolution

import com.openminis.app.text.BoundedText

data class ReflectDecision(
    val rule: LearnedItem,
    val evidence: String,
    val tightenTo: String?,
    val reason: String,
)

/**
 * Pure weekly-reflection policy. Never applies LEARNED.md itself —
 * the engine turns these into pending retract / tighten proposals.
 */
object WeeklyReflection {

    fun decide(
        rules: List<LearnedItem>,
        userTurns: List<String>,
        lastHitByBody: Map<String, Long>,
        now: Long,
        isTaskDiary: (String) -> Boolean,
    ): List<ReflectDecision> {
        val out = mutableListOf<ReflectDecision>()
        val seen = mutableSetOf<String>()
        for (rule in rules) {
            val key = rule.body.lowercase()
            if (key in seen) continue
            val hit = userTurns.firstOrNull { u ->
                !isTaskDiary(u) && BeliefMaintenance.contradicts(rule.body, u)
            }
            if (hit != null) {
                seen.add(key)
                val tighten = CorrectionDetector.extractHint(hit)?.let { LearnedPrefsStore.normalize(it) }
                val tightenBody = tighten?.let { LearnedPrefsStore.parseItem(it).body }
                out.add(
                    ReflectDecision(
                        rule = rule,
                        evidence = BoundedText.icuWindow(hit, 400).toString(),
                        tightenTo = tighten.takeIf {
                            tightenBody != null && !tightenBody.equals(rule.body, ignoreCase = true)
                        },
                        reason = "contradicted",
                    ),
                )
                continue
            }
            val lastHit = lastHitByBody[key] ?: continue
            if (now - lastHit < BeliefMaintenance.DECAY_MS) continue
            seen.add(key)
            val days = ((now - lastHit) / DAY_MS).coerceAtLeast(1)
            out.add(
                ReflectDecision(
                    rule = rule,
                    evidence = "Unused for $days days",
                    tightenTo = null,
                    reason = "unused",
                ),
            )
        }
        return out
    }

    fun fromLlm(
        items: List<EvolutionJson.ReflectItem>,
        rules: List<LearnedItem>,
    ): List<ReflectDecision> {
        val out = mutableListOf<ReflectDecision>()
        val seen = mutableSetOf<String>()
        for (item in items) {
            val action = item.action.lowercase()
            if (action == "keep" || action == "skip") continue
            val rule = matchRule(item.rule, rules) ?: continue
            val key = rule.body.lowercase()
            if (key in seen) continue
            seen.add(key)
            when (action) {
                "retract" -> out.add(
                    ReflectDecision(
                        rule = rule,
                        evidence = item.evidence ?: "weekly reflect",
                        tightenTo = null,
                        reason = "contradicted",
                    ),
                )
                "tighten" -> {
                    val repl = item.replacement?.let { LearnedPrefsStore.normalize(it) } ?: continue
                    if (LearnedPrefsStore.parseItem(repl).body.equals(rule.body, ignoreCase = true)) continue
                    out.add(
                        ReflectDecision(
                            rule = rule,
                            evidence = item.evidence ?: "weekly reflect",
                            tightenTo = repl,
                            reason = "contradicted",
                        ),
                    )
                }
            }
        }
        return out
    }

    private fun matchRule(needle: String, rules: List<LearnedItem>): LearnedItem? {
        val n = LearnedPrefsStore.parseItem(
            LearnedPrefsStore.normalize(needle) ?: "- $needle",
        ).body
        return rules.find { it.body.equals(n, ignoreCase = true) }
            ?: rules.find {
                it.body.contains(n, ignoreCase = true) || n.contains(it.body, ignoreCase = true)
            }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
