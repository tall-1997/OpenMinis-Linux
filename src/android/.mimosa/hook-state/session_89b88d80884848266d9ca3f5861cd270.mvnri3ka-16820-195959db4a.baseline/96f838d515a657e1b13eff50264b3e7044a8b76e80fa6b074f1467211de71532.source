package com.openminis.app.evolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReflectionTest {

    private fun rule(body: String, scene: SceneTag = SceneTag.GENERAL): LearnedItem {
        val raw = LearnedPrefsStore.formatBullet(body, scene, core = false)!!
        return LearnedPrefsStore.parseItem(raw)
    }

    @Test
    fun retractsContradictedRule() {
        val r = rule("Prefer bash over zsh")
        val out = WeeklyReflection.decide(
            rules = listOf(r),
            userTurns = listOf("不对，不要再用 bash over zsh"),
            lastHitByBody = emptyMap(),
            now = 1_000L,
            isTaskDiary = { false },
        )
        assertEquals(1, out.size)
        assertEquals("contradicted", out[0].reason)
        assertEquals(r.body, out[0].rule.body)
        assertTrue(out[0].evidence.contains("bash"))
    }

    @Test
    fun skipsTaskDiaryContradiction() {
        val r = rule("Prefer bash over zsh")
        val out = WeeklyReflection.decide(
            rules = listOf(r),
            userTurns = listOf("继续调研下一步"),
            lastHitByBody = mapOf(r.body.lowercase() to 1_000L),
            now = 2_000L,
            isTaskDiary = { t -> listOf("调研", "下一步").any { t.contains(it) } },
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun retractsUnusedAfterDecayWindow() {
        val r = rule("Prefer apt-get")
        val t0 = 1_000L
        val out = WeeklyReflection.decide(
            rules = listOf(r),
            userTurns = emptyList(),
            lastHitByBody = mapOf(r.body.lowercase() to t0),
            now = t0 + BeliefMaintenance.DECAY_MS,
            isTaskDiary = { false },
        )
        assertEquals(1, out.size)
        assertEquals("unused", out[0].reason)
        assertNull(out[0].tightenTo)
    }

    @Test
    fun fromLlmRetractAndTighten() {
        val r = rule("Prefer vim")
        val items = listOf(
            EvolutionJson.ReflectItem(
                action = "tighten",
                rule = "Prefer vim",
                replacement = "- Prefer nvim for terminal editing",
                evidence = "don't use vim",
            ),
        )
        val out = WeeklyReflection.fromLlm(items, listOf(r))
        assertEquals(1, out.size)
        assertEquals("- Prefer nvim for terminal editing", out[0].tightenTo)
    }
}
