package com.openminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SkillMatcher 的相似度与 bestMatch 规则：中英文两条打分通路、阈值 0.22、
 * 同名/同指令 1.0、stale 永不匹配。
 */
class SkillMatcherTest {

    private fun skill(
        id: String = "a11y-1",
        name: String = "test",
        instruction: String = "test instruction",
        stale: Boolean = false,
        lastUsedAt: Long = 1L,
    ): A11ySkillStore.Skill = A11ySkillStore.Skill(
        id = id,
        name = name,
        instruction = instruction,
        packageName = "com.example",
        steps = listOf(step()),
        createdAt = 1L,
        lastUsedAt = lastUsedAt,
        stale = stale,
    )

    private fun step() = A11ySkillStore.Step(
        kind = "TAP", packageName = "com.example", text = "OK",
        viewId = null, contentDescription = null, xy = "10,20", atMs = 0L,
    )

    // ─── 相似度基线 ───────────────────────────────────────────────────────

    @Test
    fun `chinese paraphrase with shared intent stays at or above threshold`() {
        // 同一意图的不同表述：共享大量 bigram（打开/抖音/搜索/华为/手机）
        val a = "打开抖音搜索华为手机"
        val b = "在抖音里搜华为手机"
        val s = SkillMatcher.similarity(a, b)
        assertTrue("expected >= 0.22 but was $s", s >= SkillMatcher.MATCH_THRESHOLD)
        // bestMatch 端到端同样要过阈值
        val m = SkillMatcher.bestMatch(b, listOf(skill(instruction = a)))
        assertNotNull(m)
    }

    @Test
    fun `chinese unrelated instructions stay below threshold`() {
        val a = "打开抖音搜索华为手机"
        val b = "把屏幕亮度调到最高"
        val s = SkillMatcher.similarity(a, b)
        assertTrue("expected < 0.22 but was $s", s < SkillMatcher.MATCH_THRESHOLD)
        assertNull(SkillMatcher.bestMatch(b, listOf(skill(instruction = a))))
    }

    @Test
    fun `english token jaccard drives latin similarity`() {
        val s = SkillMatcher.similarity("open camera and take a photo", "open camera take photo")
        assertTrue("expected >= 0.22 but was $s", s >= SkillMatcher.MATCH_THRESHOLD)
        val unrelated = SkillMatcher.similarity("open camera and take a photo", "format sd card now")
        assertTrue("expected < 0.22 but was $unrelated", unrelated < SkillMatcher.MATCH_THRESHOLD)
    }

    @Test
    fun `exact name and exact instruction dominate at 1 dot 0`() {
        val s = skill(name = "刷视频", instruction = "打开抖音刷视频")
        assertEquals(1.0, SkillMatcher.bestMatch("刷视频", listOf(s))!!.score, 1e-9)
        assertEquals(1.0, SkillMatcher.bestMatch("打开抖音刷视频", listOf(s))!!.score, 1e-9)
        // 大小写不敏感的英文同名
        val en = skill(name = "OpenCamera")
        assertEquals(1.0, SkillMatcher.bestMatch("opencamera", listOf(en))!!.score, 1e-9)
    }

    @Test
    fun `empty query never matches`() {
        assertNull(SkillMatcher.bestMatch("   ", listOf(skill())))
        assertNull(SkillMatcher.bestMatch("", listOf(skill())))
        // 空串对空串的相似度：token/bigram 通路都为 0
        assertEquals(0.0, SkillMatcher.similarity("", ""), 1e-9)
    }

    @Test
    fun `stale skills never match even on exact instruction`() {
        val s = skill(name = "exact", instruction = "打开抖音搜索华为手机", stale = true)
        assertNull(SkillMatcher.bestMatch("打开抖音搜索华为手机", listOf(s)))
        assertNull(SkillMatcher.bestMatch("exact", listOf(s)))
    }

    @Test
    fun `best match picks the highest score skill`() {
        val weak = skill(id = "w", instruction = "把屏幕亮度调到最高") // 与查询无关 → 低于阈值
        val strong = skill(id = "s", instruction = "打开抖音搜索华为手机")
        val m = SkillMatcher.bestMatch("在抖音里搜华为手机", listOf(weak, strong))
        assertEquals("s", m!!.skill.id)
        assertTrue(m.score >= SkillMatcher.MATCH_THRESHOLD)
    }

    @Test
    fun `threshold constant is pinned at 0 dot 22`() {
        assertEquals(0.22, SkillMatcher.MATCH_THRESHOLD, 1e-9)
    }
}
